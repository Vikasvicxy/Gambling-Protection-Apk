package dev.gamblock.protection.tamper

import dev.gamblock.core.common.logging.Logs
import dev.gamblock.core.common.logging.ShieldLogger

/**
 * Orchestrates the tamper-evidence lifecycle: probe the environment, record a
 * signed, tamper-evident log of any findings, and verify the log's integrity.
 *
 * This is a *foundation*: it produces evidence only. It never blocks, tears
 * down protection, or requires Play Services. Downstream health/UI surfaces
 * consume [overview]/[verifyChain] and decide how to react.
 */
class TamperEngine(
    private val probe: TamperSignalProbe,
    private val recorder: TamperRecorder,
    private val logger: ShieldLogger,
) {

    /**
     * Runs one environment check and records any active signals. Returns the
     * recorder's overview after appending. Never throws from the probe.
     */
    suspend fun performCheck(prefix: String = "engine"): TamperOverview = try {
        recorder.recordSignalProbe(probe, prefix)
        val overview = recorder.overview()
        if (overview.totalEvidence == 0) {
            logger.i(Logs.SECURITY, "Tamper check clean: no signals recorded")
        } else {
            logger.i(Logs.SECURITY, "Tamper check recorded ${overview.categoryCounts}")
        }
        overview
    } catch (t: Exception) {
        logger.w(Logs.SECURITY, "Tamper check could not complete", t)
        TamperOverview(
            totalEvidence = recorder.records().size,
            chainValid = false,
            generatedAtEpochMs = System.currentTimeMillis(),
            categoryCounts = emptyMap(),
            lastHmac = "",
            lastCategory = TamperCategory.UNKNOWN,
        )
    }

    /** Replays the full evidence log and reports any integrity break. */
    suspend fun verifyEvidenceChain(): ChainVerification {
        val result = recorder.verifyChain()
        if (!result.valid) {
            logger.e(
                Logs.SECURITY,
                "Tamper-evidence chain broken at index ${result.brokenAtIndex}: ${result.reason}",
            )
        }
        return result
    }

    suspend fun overview(): TamperOverview = recorder.overview()
}