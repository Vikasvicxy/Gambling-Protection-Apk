package dev.gamblock.core.release

import dev.gamblock.protection.domainengine.DomainNormalizer
import java.security.MessageDigest

class ReleaseValidationException(message: String) : Exception(message)

/** Structural + semantic validation of a decoded release payload. */
object ReleaseValidator {

    private val ALLOWED_CATEGORIES = setOf(
        "GAMBLING", "CASINO", "SPORTSBOOK", "POKER", "LOTTERY", "BINGO",
        "CRYPTO_GAMBLING", "SKIN_GAMBLING", "AFFILIATE", "PREDICTION_MARKETS", "UNKNOWN",
    )
    private val ALLOWED_STATUSES = setOf("CANDIDATE", "VERIFIED", "ACTIVE", "ALLOWLISTED", "FALSE_POSITIVE", "DISABLED")
    private val ALLOWED_CONFIDENCE = setOf("HIGH", "MEDIUM", "LOW", "UNKNOWN")
    private val ALLOWED_RISK = setOf("CRITICAL", "HIGH", "MEDIUM", "LOW", "UNKNOWN")

    /**
     * Validates a decoded payload. Returns the unique normalized domains in input order.
     * Throws [ReleaseValidationException] on the first structural problem.
     */
    fun validateRecords(records: List<ReleaseDomainRecord>): List<String> {
        val seen = HashSet<String>(records.size)
        val ordered = ArrayList<String>(records.size)
        var index = 0
        for (record in records) {
            index++
            val normalized = DomainNormalizer.normalize(record.normalizedDomain)
            if (normalized == null || normalized != record.normalizedDomain) {
                throw ReleaseValidationException("record #$index: invalid normalizedDomain '${record.normalizedDomain}'")
            }
            if (record.category !in ALLOWED_CATEGORIES) throw ReleaseValidationException("record #$index: bad category '${record.category}'")
            if (record.status !in ALLOWED_STATUSES) throw ReleaseValidationException("record #$index: bad status '${record.status}'")
            if (record.confidence !in ALLOWED_CONFIDENCE) throw ReleaseValidationException("record #$index: bad confidence '${record.confidence}'")
            if (record.riskLevel !in ALLOWED_RISK) throw ReleaseValidationException("record #$index: bad riskLevel '${record.riskLevel}'")
            if (!seen.add(normalized)) throw ReleaseValidationException("record #$index: duplicate domain '$normalized'")
            ordered.add(normalized)
        }
        return ordered
    }

    /** Decodes + validates a compressed full payload in one step. */
    fun decodeAndValidate(
        compressed: ByteArray,
        maxUncompressedBytes: Long = PayloadCodec.MAX_UNCOMPRESSED_BYTES,
    ): List<ReleaseDomainRecord> {
        val records = PayloadCodec.decodeRecords(compressed, maxUncompressedBytes)
        validateRecords(records)
        return records
    }

    /** Verifies artifact integrity: byte-for-byte SHA-256 + size as pinned by the manifest. */
    fun verifyArtifact(artifact: FullArtifact, payload: ByteArray): VerificationResult {
        val actual = sha256Hex(payload)
        return when {
            payload.size.toLong() != artifact.sizeBytes ->
                VerificationResult.fail("size mismatch: manifest ${artifact.sizeBytes} != actual ${payload.size}")
            actual != artifact.sha256 ->
                VerificationResult.fail("sha256 mismatch: manifest ${artifact.sha256.take(12)} != actual ${actual.take(12)}")
            else -> VerificationResult.ok("artifact matches manifest hash and size")
        }
    }

    fun verifyArtifact(artifact: DeltaArtifact, payload: ByteArray): VerificationResult {
        val actual = sha256Hex(payload)
        return when {
            payload.size.toLong() != artifact.sizeBytes ->
                VerificationResult.fail("delta size mismatch: manifest ${artifact.sizeBytes} != actual ${payload.size}")
            actual != artifact.sha256 ->
                VerificationResult.fail("delta sha256 mismatch: manifest ${artifact.sha256.take(12)} != actual ${actual.take(12)}")
            else -> VerificationResult.ok("delta artifact matches manifest hash and size")
        }
    }

    fun sha256Hex(payload: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
}