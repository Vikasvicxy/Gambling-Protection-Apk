package dev.gamblock.data.update

import android.content.Context
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

            val manifestText = fetchManifest() ?: return@withContext "manifest fetch failed"
            val envelope = runCatching { ReleaseVerifier.parseEnvelope(manifestText) }.getOrElse { e ->
                fail("manifest parse failed: ${e.message}")
                return@withContext "manifest parse failed: ${e.message}"
            }
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
                val deltaPayload = fetchArtifact(delta.fileName)
                if (deltaPayload != null) {
                    val applied = applyDelta(envelope, manifest, deltaPayload, keyRing, installed, appVersionCode, maxObserved)
                    if (applied) {
                        finishSuccess(manifest, keyRing.keyIds.first(), viaDelta = true)
                        return@withContext "applied delta v${manifest.version} from v$installed"
                    }
                }
            }

            // 4) Full artifact fallback, or first-install / rollback delivery.
            applyFull(envelope, manifest, keyRing, installed, appVersionCode, maxObserved)
        }

    private suspend fun fetchManifest(): String? =
        try {
            fetcher.fetch(config, config.manifestUrl, config.maxManifestBytes).decodeToString()
        } catch (e: Exception) {
            fail("manifest fetch failed: ${e.message}")
            logger.w(Logs.DB, "update manifest fetch failed", e)
            null
        }

    private suspend fun fetchArtifact(fileName: String): ByteArray? =
        try {
            fetcher.fetch(config, config.artifactUrl(fileName), config.maxArtifactBytes)
        } catch (e: Exception) {
            logger.w(Logs.DB, "artifact fetch failed for $fileName", e)
            null
        }

    private suspend fun applyFull(
        envelope: SignedReleaseEnvelope,
        manifest: SignedReleaseManifest,
        keyRing: TrustedKeyRing,
        installed: Int,
        appVersionCode: Int,
        maxObserved: Int,
    ): String {
        val payload = fetchArtifact(manifest.full.fileName)
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
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
    } catch (e: Exception) {
        0
    }
}