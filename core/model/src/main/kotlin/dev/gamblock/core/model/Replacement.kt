package dev.gamblock.core.model

import kotlinx.serialization.Serializable

/**
 * Phase 3 Device replacement / recovery models.
 *
 * Replacement lets a protected device's legitimate commitment/tenure state survive
 * migration to a new device when a trusted partner (or parent, where appropriate)
 * confirms it. The flow guards against trivially bypassing tenure by rolling to a fresh
 * install.
 */

@Serializable
enum class DeviceReplacementState {
    /** Requested by the old device / protected user. */
    PENDING,
    /** Awaiting partner/parent confirmation. */
    AWAITING_CONFIRMATION,
    /** Confirmed; new device may inherit state. */
    CONFIRMED,
    /** Rejected. */
    REJECTED,
    /** Expired (e.g. exceeded a time limit). */
    EXPIRED,
}

/** A request to migrate commitment state from an old device to a new one. */
@Serializable
data class DeviceReplacementRequest(
    val id: String,
    /** Pseudonymous id of the old device. */
    val oldDeviceId: String,
    /** Pseudonymous id of the replacement device. */
    val newDeviceId: String,
    /** Remaining commitment at request time (e.g. 487 days). */
    val remainingCommitmentMillis: Long,
    val requestedAtEpochMs: Long,
    val status: DeviceReplacementState = DeviceReplacementState.PENDING,
    val confirmsNeeded: Int = 1,
    val confirmedBy: Set<String> = emptySet(),
    val confirmedAtEpochMs: Long? = null,
    /** If true, the old device's blocklist DB may be re-verified on the new device. */
    val carryBlocklist: Boolean = true,
) {
    companion object {
        const val MAX_TENURE_TRANSFER_MILLIS = 2L * 366L * 24L * 60L * 60L * 1000L
    }
}

/** Result of evaluating whether a replacement may proceed. */
sealed interface ReplacementDecision {
    data class Allowed(val canProceed: Boolean = true) : ReplacementDecision
    data class Denied(val reason: String) : ReplacementDecision
}

/** Immutable rule for tenure transfer. Mirrored by server-side policy. */
object ReplacementPolicy {
    const val MAX_TENURE_TRANSFER_MILLIS = DeviceReplacementRequest.MAX_TENURE_TRANSFER_MILLIS
    const val MAX_PENDING_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000L

    /**
     * A replacement may not create MORE tenure than legitimately lived time, and the
     * old device must still be enrolled. Additional server checks (partner confirmation)
     * are enforced in the backend layer.
     */
    fun evaluate(
        remainingCommitmentMillis: Long,
        transferCapMillis: Long = MAX_TENURE_TRANSFER_MILLIS,
    ): ReplacementDecision {
        if (remainingCommitmentMillis < 0L) return ReplacementDecision.Denied("negative remaining tenure")
        if (remainingCommitmentMillis > transferCapMillis) {
            return ReplacementDecision.Denied("remaining tenure exceeds transfer cap")
        }
        return ReplacementDecision.Allowed()
    }

    /** Pending requests older than this are expired/automatically voided. */
    fun isExpired(requestedAtEpochMs: Long, nowEpochMs: Long): Boolean =
        nowEpochMs - requestedAtEpochMs > MAX_PENDING_AGE_MILLIS
}