package dev.gamblock.core.accountability

import dev.gamblock.core.model.DeviceReplacementRequest
import dev.gamblock.core.model.DeviceReplacementState
import dev.gamblock.core.model.ReplacementDecision
import dev.gamblock.core.model.ReplacementPolicy

/**
 * Device replacement / recovery policy.
 *
 * A replacement request lets a new device inherit legitimate commitment state WITHOUT
 * allowing trivial tenure bypass. The engine applies local rules (cap, positive tenure,
 * expiry); partner/parent confirmation is enforced by the surrounding flow
 * ([PartnerRelationshipManager] capability gating) and server-side.
 */
class ReplacementService {

    sealed interface ConfirmResult {
        data class Success(val request: DeviceReplacementRequest) : ConfirmResult
        data class Rejected(val reason: String) : ConfirmResult
    }

    fun evaluate(
        remainingCommitmentMillis: Long,
        transferCapMillis: Long = ReplacementPolicy.MAX_TENURE_TRANSFER_MILLIS,
    ): ReplacementDecision = ReplacementPolicy.evaluate(remainingCommitmentMillis, transferCapMillis)

    fun isExpired(request: DeviceReplacementRequest, nowEpochMs: Long): Boolean =
        ReplacementPolicy.isExpired(request.requestedAtEpochMs, nowEpochMs)

    /**
     * Confirm a replacement. [actingDeviceId] must be a confirmed-capable partner/parent
     * (checked via granted capability upstream). Replay / self-confirmation is rejected.
     */
    fun confirm(
        request: DeviceReplacementRequest,
        actingDeviceId: String,
        nowEpochMs: Long,
    ): ConfirmResult {
        if (actingDeviceId == request.oldDeviceId) {
            return ConfirmResult.Rejected("old device cannot confirm its own replacement")
        }
        if (actingDeviceId == request.newDeviceId) {
            return ConfirmResult.Rejected("new device cannot confirm its own replacement")
        }
        if (request.status != DeviceReplacementState.PENDING &&
            request.status != DeviceReplacementState.AWAITING_CONFIRMATION
        ) {
            return ConfirmResult.Rejected("replacement not awaiting confirmation")
        }
        if (isExpired(request, nowEpochMs)) {
            return ConfirmResult.Rejected("replacement request expired")
        }
        val newGranted = request.confirmedBy + actingDeviceId
        val confirmed = newGranted.size >= request.confirmsNeeded
        return ConfirmResult.Success(
            request.copy(
                confirmedBy = newGranted,
                status = if (confirmed)
                    DeviceReplacementState.CONFIRMED
                else
                    DeviceReplacementState.AWAITING_CONFIRMATION,
                confirmedAtEpochMs = if (confirmed) nowEpochMs else null,
            ),
        )
    }
}