package dev.gamblock.data.update

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gamblock.core.common.dispatcher.DispatchersProvider
import dev.gamblock.core.common.logging.Logs
import dev.gamblock.core.common.logging.ShieldLogger
import dev.gamblock.core.model.UpdateState
import dev.gamblock.core.release.DeltaCodec
import dev.gamblock.core.release.DeltaEngine
import dev.gamblock.core.release.ReleaseVerifier
import dev.gamblock.core.release.ReleaseValidator
import dev.gamblock.core.release.SignedReleaseEnvelope
import dev.gamblock.core.release.SignedReleaseManifest
import dev.gamblock.core.release.TrustedKeyRing
import dev.gamblock.core.release.VersionPolicy
import dev.gamblock.data.repository.UpdateRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Device-side orchestrator for the signed update pipeline.
 *
 * Contract: NOTHING is ever applied unless (a) the manifest signature verifies against
 * the embedded public key, (b) the version policy passes (forward-only, replay/rollback
 * safe), and (c) the artifact SHA-256 pinned in the signed manifest matches the downloaded
 * bytes byte-for-byte. Every failure path leaves the last-known-good database untouched.
 */
@Singleton
class BlocklistUpdateEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val config: UpdateConfig,
    private val signingKeyProvider: SigningKeySource,
    private val fetcher: UpdateFetcher,
    private val state: UpdateStateRepository,
    private val applier: BlocklistApplier,
    private val updateRepository: UpdateRepository,
    private val dispatchers: DispatchersProvider,
    private val logger: ShieldLogger,
) {

    val updateState: StateFlow<UpdateState> get() = state.state

    /** Runs a full check: fetch manifest -> verify -> (delta when possible) -> apply. */
    suspend fun checkForUpdate(preferDelta: Boolean = config.preferDelta): String =
        withContext(dispatchers.io) {
            val keyRing = signingKeyProvider.keyRingOrNull()
            if (keyRing == null) {
                state.recordFailed("signing key asset unavailable")
                updateRepository.publish(UpdateState.FAILED, "signing key unavailable")
                return@withContext "signing key unavailable"
            }

            state.beginCheck()
            val installed = state.installedVersion()
            val maxObserved = state.maxObservedVersion()
            val appVersionCode = appVersionCode()

            val fetched = when (val outcome = fetchManifest()) {
                is ManifestFetch.Failed -> return@withContext outcome.reason
                is ManifestFetch.Ok -> outcome.value
            }
            val envelope = fetched.envelope
            val manifest = envelope.manifest

            // 1) Signature first: trust nothing before the envelope verifies.
            val signatureResult = keyRing.verify(envelope)
            if (!signatureResult.ok) {
                fail("signature rejected: ${signatureResult.reason}")
                return@withContext "signature rejected: ${signatureResult.reason}"
            }

            // 2) Version policy: forward-only; signed emergency rollbacks allowed.
            val policy = VersionPolicy.evaluateUpgrade(manifest, installed, appVersionCode, maxObserved)
            if (!policy.allowed) {
                // Manifest for a version we already have is not an error - it is "up to date".
                val upToDate = manifest.version <= installed
                state.recordFailed(policy.reason)
                updateRepository.publish(if (upToDate) UpdateState.UP_TO_DATE else UpdateState.FAILED, policy.reason)
                return@withContext if (upToDate) "already up to date (v$installed)" else "policy rejected: ${policy.reason}"
            }

            // 3) Delta-first when it exists and its base matches our installed version.
            val delta = manifest.delta
            if (delta != null && preferDelta && delta.baseVersion == installed) {
                val deltaPayload = fetchArtifact(delta.fileName, fetched.originBase)
                if (deltaPayload != null) {
                    val applied = applyDelta(envelope, manifest, deltaPayload, keyRing, installed, appVersionCode, maxObserved)
                    if (applied) {
                        finishSuccess(manifest, keyRing.keyIds.first(), viaDelta = true)
                        return@withContext "applied delta v${manifest.version} from v$installed"
                    }
                }
            }

            // 4) Full artifact fallback, or first-install / rollback delivery.
            applyFull(envelope, manifest, keyRing, installed, appVersionCode, maxObserved, fetched.originBase)
        }

    /** A manifest that parsed, plus the origin that served it. */
    private data class FetchedManifest(
        val envelope: SignedReleaseEnvelope,
        val originBase: String,
    )

    private sealed interface ManifestFetch {
        data class Ok(val value: FetchedManifest) : ManifestFetch
        data class Failed(val reason: String) : ManifestFetch
    }

    /**
     * Fetches and parses the manifest, walking the configured origins until one answers with
     * something that is actually a release envelope.
     *
     * Both an unreachable host and a 200 that carries an unparseable body are treated as "this
     * origin cannot serve us today", because they are the same from the caller's point of view: an
     * HTML error page or a truncated response is just as useless as a 404, and abandoning the
     * update over one bad origin is precisely the single point of failure this walk removes.
     *
     * Parsing here does not weaken anything. The signature is still verified immediately after, on
     * the bytes of whichever origin answered, so a mirror returning garbage can only move the
     * failure later in the pipeline - it can never make an unsigned manifest acceptable.
     */
    private suspend fun fetchManifest(): ManifestFetch {
        val origins = config.candidateBaseUrls()
        var lastDetail: String? = null
        var sawParseFailure = false
        for ((index, base) in origins.withIndex()) {
            val url = "${base}manifest.json"
            val text = try {
                fetcher.fetch(config, url, config.maxManifestBytes).decodeToString()
            } catch (e: Exception) {
                lastDetail = e.message ?: e::class.java.simpleName
                logger.w(Logs.DB, "manifest origin unavailable: $url", e)
                continue
            }
            val envelope = runCatching { ReleaseVerifier.parseEnvelope(text) }.getOrElse { e ->
                sawParseFailure = true
                lastDetail = e.message ?: e::class.java.simpleName
                logger.w(Logs.DB, "manifest from $url is not a release envelope", e)
                return@getOrElse null
            } ?: continue
            if (index > 0) {
                logger.i(Logs.DB, "manifest served by origin ${index + 1}/${origins.size} after earlier origin(s) failed")
            }
            return ManifestFetch.Ok(FetchedManifest(envelope, base))
        }
        val summary = lastDetail ?: "no origins configured"
        val reason = if (sawParseFailure) "manifest parse failed on all ${origins.size} origin(s)" else "manifest fetch failed on all ${origins.size} origin(s)"
        fail("$reason: $summary")
        logger.w(Logs.DB, "$reason: $summary")
        return ManifestFetch.Failed(reason)
    }

    /**
     * Fetches an artifact, trying the origin that served the manifest first and then every other
     * configured origin.
     *
     * Artifacts are the large transfer, so this is where a single flaky host would otherwise cost
     * the most. Callers treat null as unavailable and either try the other artifact kind or leave
     * the last-known-good database untouched; a payload is never applied on the strength of where
     * it came from, only on its signature and hash.
     */
    private suspend fun fetchArtifact(fileName: String, preferredBase: String?): ByteArray? {
        val configured = config.candidateBaseUrls()
        val ordered = buildList {
            preferredBase?.let { add(it) }
            addAll(configured)
        }.distinct()
        val clean = fileName.removePrefix("/")
        var lastDetail: String? = null
        for (base in ordered) {
            val bytes = try {
                fetcher.fetch(config, "${base}$clean", config.maxArtifactBytes)
            } catch (e: Exception) {
                lastDetail = e.message ?: e::class.java.simpleName
                logger.w(Logs.DB, "artifact $clean unavailable at $base", e)
                continue
            }
            return bytes
        }
        logger.w(Logs.DB, "artifact $clean unavailable on all ${ordered.size} origin(s): $lastDetail")
        return null
    }

    private suspend fun applyFull(
        envelope: SignedReleaseEnvelope,
        manifest: SignedReleaseManifest,
        keyRing: TrustedKeyRing,
        installed: Int,
        appVersionCode: Int,
        maxObserved: Int,
        originBase: String?,
    ): String {
        val payload = fetchArtifact(manifest.full.fileName, originBase)
        if (payload == null) {
            fail("full artifact fetch failed")
            return "full artifact fetch failed"
        }
        val verified = ReleaseVerifier.verifyFullEnvelope(envelope, payload, keyRing, installed, appVersionCode, maxObserved)
        if (!verified.ok) {
            fail("full verify failed: ${verified.reason}")
            return "full verify failed: ${verified.reason}"
        }
        val records = runCatching { ReleaseVerifier.decodeVerifiedPayload(payload) }.getOrElse { e ->
            fail("payload decode failed: ${e.message}")
            return "payload decode failed: ${e.message}"
        }
        return try {
            applier.apply(records, manifest.version, manifest.releaseId, keyRing.keyIds.first(), viaDelta = false, rollback = manifest.rollback)
            finishSuccess(manifest, keyRing.keyIds.first(), viaDelta = false)
            "applied full v${manifest.version} (${records.size} rules)"
        } catch (e: Exception) {
            fail("apply failed: ${e.message}")
            logger.w(Logs.DB, "full apply failed", e)
            "apply failed: ${e.message}"
        }
    }

    /**
     * Applies a verified delta against the current on-device rows and writes the FULL
     * resulting set (deltas re-apply into the canonical table; never partial writes).
     */
    private suspend fun applyDelta(
        envelope: SignedReleaseEnvelope,
        manifest: SignedReleaseManifest,
        deltaPayload: ByteArray,
        keyRing: TrustedKeyRing,
        installed: Int,
        appVersionCode: Int,
        maxObserved: Int,
    ): Boolean {
        val delta = manifest.delta ?: return false
        val verified = ReleaseVerifier.verifyDelta(envelope, deltaPayload, keyRing, installed, appVersionCode, maxObserved)
        if (!verified.ok) return false
        return try {
            val ops = DeltaCodec.decode(deltaPayload)
            val plan = DeltaEngine.validateAndGroup(ops, delta)
            val base = applier.currentRecords().associate { it.normalizedDomain to it.toReleaseRecord() }
            val target = DeltaEngine.applyTo(base, plan)
            val records = target
                .map { (_, r) -> if (r.databaseVersion == installed) r.copy(databaseVersion = manifest.version) else r }
                .sortedBy { it.normalizedDomain }
            ReleaseValidator.validateRecords(records)
            applier.apply(records, manifest.version, manifest.releaseId, keyRing.keyIds.first(), viaDelta = true)
            true
        } catch (e: Exception) {
            logger.w(Logs.DB, "delta apply failed; falling back to full", e)
            false
        }
    }

    private suspend fun finishSuccess(manifest: SignedReleaseManifest, signingKeyId: String, viaDelta: Boolean) {
        val newMaxObserved = maxOf(manifest.version, state.maxObservedVersion())
        state.recordSuccess(manifest.version, newMaxObserved, manifest.releaseId, signingKeyId, viaDelta)
        updateRepository.publish(UpdateState.UP_TO_DATE, "applied v${manifest.version}")
    }

    private suspend fun fail(reason: String) {
        state.recordFailed(reason)
        updateRepository.publish(UpdateState.FAILED, reason)
    }

    private fun appVersionCode(): Int = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            // getLongVersionCode does not exist before API 28 and would throw
            // NoSuchMethodError; @Suppress is scoped to the verified branch.
            @Suppress("DEPRECATION")
            info.versionCode
        }
    } catch (e: Exception) {
        0
    }
}