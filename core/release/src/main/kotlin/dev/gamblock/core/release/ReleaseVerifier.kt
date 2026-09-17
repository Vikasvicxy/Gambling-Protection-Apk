package dev.gamblock.core.release

/**
 * Client-side end-to-end verification pipeline. Each step fails closed with a
 * machine-readable [VerificationResult]; the caller rejects the update on any failure
 * and continues to use the last-known-good database.
 */
object ReleaseVerifier {

    data class VerifiedRelease(
        val envelope: SignedReleaseEnvelope,
        val records: List<ReleaseDomainRecord>,
        val viaDelta: Boolean,
    )

    fun parseEnvelope(manifestText: String): SignedReleaseEnvelope =
        CanonicalCodec.json.decodeFromString(SignedReleaseEnvelope.serializer(), manifestText)

    fun encodeEnvelope(envelope: SignedReleaseEnvelope): String =
        CanonicalCodec.json.encodeToString(SignedReleaseEnvelope.serializer(), envelope)

    /**
     * Decodes + semantically validates a full payload that has already passed
     * signature/hash verification. Throws [PayloadException] / [ReleaseValidationException].
     */
    fun decodeVerifiedPayload(
        fullPayload: ByteArray,
        maxUncompressedBytes: Long = PayloadCodec.MAX_UNCOMPRESSED_BYTES,
    ): List<ReleaseDomainRecord> {
        val records = PayloadCodec.decodeRecords(fullPayload, maxUncompressedBytes)
        ReleaseValidator.validateRecords(records)
        return records
    }

    /**
     * Verifies a signed full-payload release:
     *  signature -> policy -> artifact hash/size -> decode -> semantic validation.
     */
    fun verifyFull(
        manifestText: String,
        fullPayload: ByteArray,
        keyRing: TrustedKeyRing,
        installedVersion: Int,
        currentAppVersionCode: Int,
        maxObservedVersion: Int,
        maxUncompressedBytes: Long = PayloadCodec.MAX_UNCOMPRESSED_BYTES,
    ): VerificationResult {
        val envelope = try {
            parseEnvelope(manifestText)
        } catch (e: Exception) {
            return VerificationResult.fail("manifest parse failed: ${e.message}")
        }
        return verifyFullEnvelope(envelope, fullPayload, keyRing, installedVersion, currentAppVersionCode, maxObservedVersion, maxUncompressedBytes)
    }

    fun verifyFullEnvelope(
        envelope: SignedReleaseEnvelope,
        fullPayload: ByteArray,
        keyRing: TrustedKeyRing,
        installedVersion: Int,
        currentAppVersionCode: Int,
        maxObservedVersion: Int,
        maxUncompressedBytes: Long = PayloadCodec.MAX_UNCOMPRESSED_BYTES,
    ): VerificationResult {
        keyRing.verify(envelope).let { if (!it.ok) return it }
        val policy = VersionPolicy.evaluateUpgrade(
            candidate = envelope.manifest,
            installedVersion = installedVersion,
            currentAppVersionCode = currentAppVersionCode,
            maxObservedVersion = maxObservedVersion,
        )
        if (!policy.allowed) return VerificationResult.fail("version policy: ${policy.reason}")
        val artifact = ReleaseValidator.verifyArtifact(envelope.manifest.full, fullPayload)
        if (!artifact.ok) return artifact
        return try {
            val records = PayloadCodec.decodeRecords(fullPayload, maxUncompressedBytes)
            ReleaseValidator.validateRecords(records)
            VerificationResult.ok("signed full release verified (${records.size} records)")
        } catch (e: ReleaseValidationException) {
            VerificationResult.fail("payload validation: ${e.message}")
        } catch (e: PayloadException) {
            VerificationResult.fail("payload decode: ${e.message}")
        }
    }

    /** Verifies a signed delta release. The payload is validated but NOT applied here. */
    fun verifyDelta(
        envelope: SignedReleaseEnvelope,
        deltaPayload: ByteArray,
        keyRing: TrustedKeyRing,
        installedVersion: Int,
        currentAppVersionCode: Int,
        maxObservedVersion: Int,
        maxUncompressedBytes: Long = PayloadCodec.MAX_UNCOMPRESSED_BYTES,
    ): VerificationResult {
        keyRing.verify(envelope).let { if (!it.ok) return it }
        val policy = VersionPolicy.evaluateUpgrade(
            candidate = envelope.manifest,
            installedVersion = installedVersion,
            currentAppVersionCode = currentAppVersionCode,
            maxObservedVersion = maxObservedVersion,
        )
        if (!policy.allowed) return VerificationResult.fail("version policy: ${policy.reason}")
        val delta = envelope.manifest.delta ?: return VerificationResult.fail("manifest carries no delta artifact")
        if (delta.baseVersion != installedVersion) {
            return VerificationResult.fail("delta base ${delta.baseVersion} != installed $installedVersion (fall back to full)")
        }
        val artifact = ReleaseValidator.verifyArtifact(delta, deltaPayload)
        if (!artifact.ok) return artifact
        return try {
            val ops = DeltaCodec.decode(deltaPayload, maxUncompressedBytes)
            DeltaEngine.validateAndGroup(ops, delta)
            VerificationResult.ok("signed delta verified (base ${delta.baseVersion})")
        } catch (e: ReleaseValidationException) {
            VerificationResult.fail("delta validation: ${e.message}")
        } catch (e: PayloadException) {
            VerificationResult.fail("delta decode: ${e.message}")
        }
    }
}