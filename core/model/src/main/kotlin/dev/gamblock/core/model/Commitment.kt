package dev.gamblock.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How a user is committed to protection. Phase 1 supports [SELF_PROTECTION] only. */
enum class ProtectionMode {
    SELF_PROTECTION,

    /** Future phases only. Must never be assumed implemented. */
    ACCOUNTABILITY,

    /** Future phases only (genuine parent/guardian flows). */
    PARENTAL,
}

enum class CommitmentState {
    NONE,
    ACTIVE,
    COMPLETED,
}

/** Protection strength. Phase 1 enforces DNS/domain blocking. */
enum class ProtectionLevel {
    DNS_DOMAIN_BLOCKING,
}

/** Duration choices offered by the commitment UI. */
@Serializable
enum class CommitmentDurationOption(
    val label: String,
    val durationMillis: Long,
) {
    @SerialName("h24") H24("24 hours", 24L * 60 * 60 * 1000),
    @SerialName("d3") D3("3 days", 3L * 24 * 60 * 60 * 1000),
    @SerialName("d7") D7("7 days", 7L * 24 * 60 * 60 * 1000),
    @SerialName("d14") D14("14 days", 14L * 24 * 60 * 60 * 1000),
    @SerialName("d30") D30("30 days", 30L * 24 * 60 * 60 * 1000),
    @SerialName("m3") M3("3 months", 3L * 30 * 24 * 60 * 60 * 1000),
    @SerialName("m6") M6("6 months", 6L * 30 * 24 * 60 * 60 * 1000),
    @SerialName("y1") Y1("1 year", 365L * 24 * 60 * 60 * 1000),
    @SerialName("custom") CUSTOM("Custom date", -1L),
    ;

    companion object {
        fun isValidDuration(millis: Long): Boolean =
            millis >= MIN_DURATION_MILLIS && millis <= MAX_DURATION_MILLIS

        const val MIN_DURATION_MILLIS: Long = 60 * 60 * 1000L
        const val MAX_DURATION_MILLIS: Long = 2L * 366L * 24L * 60L * 60L * 1000L
    }
}

/**
 * A persisted protection commitment.
 *
 * Clock manipulation resistance: the authoritative accounting of consumed time is stored as
 * [accumulatedElapsedMillis] (monotonic `elapsedRealtime` deltas, reboot-aware). The wall-clock
 * fields are used for display and as a secondary sanity signal only.
 */
@Serializable
data class Commitment(
    val id: String,
    val mode: ProtectionMode = ProtectionMode.SELF_PROTECTION,
    val level: ProtectionLevel = ProtectionLevel.DNS_DOMAIN_BLOCKING,
    val state: CommitmentState,
    val createdAtEpochMs: Long,
    /** Wall-clock start of the commitment (display). */
    val startEpochMs: Long,
    /** Wall-clock end shown in UI. Not authoritative on its own. */
    val endEpochMs: Long,
    /** Intended duration - never shrinks except through genuine use of time. */
    val intendedDurationMs: Long,
    /** Accumulated monotonic elapsed time spent committed. */
    val accumulatedElapsedMillis: Long,
    /** ElapsedRealtime() at the last accounting action (scoped to current boot session). */
    val lastAccountedElapsedMs: Long,
    /** Number of device boot sessions observed while the commitment has existed. */
    val bootCountAtCreation: Int,
    /** Number of times this commitment was extended. */
    val extensionCount: Int = 0,
    /** True once the genuine end conditions have been met; the user may then finish. */
    val canFinish: Boolean = false,
) {
    /** Wall-clock remaining for display only. */
    val displayedRemainingMillis: Long
        get() = (endEpochMs - System.currentTimeMillis()).coerceAtLeast(0L)
}

/** Result of an attempt to start a commitment. */
sealed interface CommitmentStartResult {
    data class Success(val commitment: Commitment) : CommitmentStartResult
    data class Rejected(val reason: String) : CommitmentStartResult
}

/** Result of an attempt to extend a commitment. */
sealed interface CommitmentExtendResult {
    data class Success(val commitment: Commitment) : CommitmentExtendResult
    data object NoActiveCommitment : CommitmentExtendResult
    data class Rejected(val reason: String) : CommitmentExtendResult
}