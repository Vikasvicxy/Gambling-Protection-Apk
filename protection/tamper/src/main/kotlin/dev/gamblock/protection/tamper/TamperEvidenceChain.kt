package dev.gamblock.protection.tamper

import dev.gamblock.core.model.util.sha256Hex

/**
 * Pure append-only, tamper-evident evidence chain logic.
 *
 * Thread-safety is the caller's responsibility (the store is the mutex). All
 * methods here are deterministic functions of their inputs, which keeps the
 * core cryptographically testable without Android.
 */
object TamperEvidenceChain {

    /** 64 zero chars used as the linkage root of the chain head. */
    const val ROOT_HASH = "0000000000000000000000000000000000000000000000000000000000000000"

    /**
     * Builds the canonical bytes that both [contentHash] and the HMAC cover.
     * Field order and detail sorting are fixed so replay is deterministic.
     */
    fun canonical(sequence: Long, category: TamperCategory, timestampEpochMs: Long, detail: Map<String, String>, prevHash: String): ByteArray {
        val detailText = detail.entries
            .sortedBy { it.key }
            .joinToString(";") { "${it.key}=${it.value}" }
        return "$sequence|$category|$timestampEpochMs|$detailText|$prevHash".toByteArray(Charsets.UTF_8)
    }

    /** HMACs the canonical bytes; also the chain-linkage value for the next record. */
    fun link(hmac: EvidenceHmac, sequence: Long, category: TamperCategory, timestampEpochMs: Long, detail: Map<String, String>, prevHash: String): String =
        hmac.sign(canonical(sequence, category, timestampEpochMs, detail, prevHash))

    /** Full hash of the canonical bytes stored inside the record. */
    fun contentHash(sequence: Long, category: TamperCategory, timestampEpochMs: Long, detail: Map<String, String>, prevHash: String): String =
        sha256Hex(canonical(sequence, category, timestampEpochMs, detail, prevHash))

    /**
     * Produces the next signed record given the current tail (or null for a
     * fresh chain). Sequence numbers start at 1.
     */
    fun append(
        hmac: EvidenceHmac,
        tail: TamperEvidence?,
        category: TamperCategory,
        timestampEpochMs: Long,
        detail: Map<String, String> = emptyMap(),
    ): TamperEvidence {
        val sequence = (tail?.sequence ?: 0L) + 1L
        val prevHash = tail?.hmac ?: ROOT_HASH
        val theHash = contentHash(sequence, category, timestampEpochMs, detail, prevHash)
        return TamperEvidence(
            sequence = sequence,
            category = category,
            timestampEpochMs = timestampEpochMs,
            detail = detail,
            contentHash = theHash,
            prevHash = prevHash,
            hmac = link(hmac, sequence, category, timestampEpochMs, detail, prevHash),
        )
    }

    /** Replays [records] from the head, verifying hashes, links and sequences. */
    fun verify(hmac: EvidenceHmac, records: List<TamperEvidence>): ChainVerification {
        records.forEachIndexed { index, record ->
            val previous = records.getOrNull(index - 1)
            val expectedPrevHash = previous?.hmac ?: ROOT_HASH
            if (record.prevHash != expectedPrevHash) {
                return ChainVerification(false, index, index, "link mismatch at #$index")
            }
            if (record.sequence != (previous?.sequence ?: 0L) + 1L) {
                return ChainVerification(false, index, index, "sequence gap at #$index")
            }
            val expectedHash = contentHash(
                record.sequence,
                record.category,
                record.timestampEpochMs,
                record.detail,
                record.prevHash,
            )
            if (record.contentHash != expectedHash) {
                return ChainVerification(false, index, index, "content hash mismatch at #$index")
            }
            val expectedHmac = link(
                hmac,
                record.sequence,
                record.category,
                record.timestampEpochMs,
                record.detail,
                record.prevHash,
            )
            if (record.hmac != expectedHmac) {
                return ChainVerification(false, index, index, "hmac mismatch at #$index")
            }
        }
        return ChainVerification(true, records.size)
    }
}