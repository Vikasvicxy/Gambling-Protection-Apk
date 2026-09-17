package dev.gamblock.protection.tamper

/** Appends and verifies tamper-evidence records against the persistent store. */
class TamperRecorder(
    private val store: TamperEvidenceStore,
    private val hmac: EvidenceHmac,
    private val clock: () -> Long,
) {

    /** Loads the tail of the chain (or null when empty). */
    suspend fun tail(): TamperEvidence? = store.load().lastOrNull()

    /** Loads the full chain. */
    suspend fun records(): List<TamperEvidence> = store.load()

    /**
     * Appends a signed record and persists the chain. Returns the new tail.
     * Editing existing records, reordering, or trimming the middle of the log
     * breaks verification; dropping the newest tail is only detectable with an
     * external anchor and is documented as a known hardening follow-up.
     */
    suspend fun record(category: TamperCategory, detail: Map<String, String> = emptyMap()): TamperEvidence {
        val current = store.load()
        val tail = current.lastOrNull()
        val next = TamperEvidenceChain.append(hmac, tail, category, clock(), detail)
        store.save(current + next)
        return next
    }

    /** Records every active signal category produced by [probe]. */
    suspend fun recordSignalProbe(probe: TamperSignalProbe, prefix: String = "probe"): List<TamperEvidence> =
        probe.current().activeCategories().map { category ->
            record(category, mapOf("source" to prefix, "detectedAt" to clock().toString()))
        }

    suspend fun verifyChain(): ChainVerification =
        TamperEvidenceChain.verify(hmac, store.load())

    suspend fun overview(): TamperOverview {
        val current = store.load()
        val chain = TamperEvidenceChain.verify(hmac, current)
        val counts = current.groupingBy { it.category.name }.eachCount()
        return TamperOverview(
            totalEvidence = current.size,
            chainValid = chain.valid,
            generatedAtEpochMs = clock(),
            categoryCounts = counts,
            lastHmac = current.lastOrNull()?.hmac ?: "",
            lastCategory = current.lastOrNull()?.category,
        )
    }
}