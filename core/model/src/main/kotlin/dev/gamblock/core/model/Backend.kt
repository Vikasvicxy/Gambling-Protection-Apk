package dev.gamblock.core.model

import kotlinx.serialization.Serializable

/**
 * Phase 3 backend DTOs.
 *
 * These are the wire contracts for the serverless backend. They are intentionally
 * hosting-agnostic: the same DTOs serialize to JSON for a Cloudflare Worker, a plain
 * HTTP service, or a future E2E-capable transport. The backend NEVER sits in the normal
 * browsing-trust path: only pairing/heartbeat/event/report/admin traffic touches it.
 */

@Serializable
data class ApiError(
    val code: String,
    val message: String,
    val status: Int = 400,
)

/** Pseudonymous device registration. */
@Serializable
data class RegisterDeviceRequest(
    val deviceId: String,
    val appVersion: String,
    val os: String = "android",
)

@Serializable
data class RegisterDeviceResponse(
    val registered: Boolean,
    val deviceId: String,
)

/** Heartbeat upload. */
@Serializable
data class HeartbeatUploadRequest(
    val deviceId: String,
    val heartbeat: HeartbeatState,
)

@Serializable
data class HeartbeatUploadResponse(
    val accepted: Boolean,
    val serverTimeEpochMs: Long,
)

/** Pairing endpoints. */
@Serializable
data class CreatePairingRequest(
    val deviceId: String,
    val kind: PairingKind,
    val capabilities: List<String>,
    val ttlMillis: Long,
    val label: String? = null,
    /**
     * Device-generated token specifics: the protected device already minted a
     * high-entropy token; the server registers ONLY the SHA-256 hash so the shared
     * secret never travels through backend storage in plain form.
     */
    val tokenId: String,
    val tokenSecretHash: String,
)

@Serializable
data class CreatePairingResponse(
    val tokenId: String,
    val expiresAtEpochMs: Long,
)

@Serializable
data class AcceptPairingRequest(
    val deviceId: String,
    val tokenId: String,
    val tokenSecret: String,
)

@Serializable
data class AcceptPairingResponse(
    val relationshipId: String,
    val role: String,
)

/** Event relay. */
@Serializable
data class RelayEventRequest(
    val deviceId: String,
    val event: AccountabilityEvent,
)

@Serializable
data class RelayEventResponse(
    val accepted: Boolean,
    val suppressedByCooldown: Boolean = false,
)

/** Approval flow. */
@Serializable
data class CreateApprovalRequestRequest(
    val deviceId: String,
    val relationshipId: String,
    val change: SensitiveChange,
    val description: String,
)

@Serializable
data class CreateApprovalRequestResponse(
    val request: ApprovalRequest,
)

@Serializable
data class ApproveRequest(
    val deviceId: String,
    val requestId: String,
    val approved: Boolean,
)

@Serializable
data class ApproveResponse(
    val request: ApprovalRequest,
)

/** Reports. */
@Serializable
data class SubmitReportRequest(
    val deviceId: String,
    val type: InboundReport.ReportType,
    val domain: String,
    val note: String? = null,
)

@Serializable
data class SubmitReportResponse(
    val accepted: Boolean,
    val reportId: String,
)

/** Notification registration. */
@Serializable
data class RegisterNotificationTokenRequest(
    val deviceId: String,
    val token: String,
)

@Serializable
data class RegisterNotificationTokenResponse(
    val registered: Boolean,
)

/** Unified backend client interface (hosting-agnostic). */
interface ShieldBackendClient {
    suspend fun registerDevice(request: RegisterDeviceRequest): RegisterDeviceResponse
    suspend fun uploadHeartbeat(request: HeartbeatUploadRequest): HeartbeatUploadResponse
    suspend fun createPairing(request: CreatePairingRequest): CreatePairingResponse
    suspend fun acceptPairing(request: AcceptPairingRequest): AcceptPairingResponse
    suspend fun relayEvent(request: RelayEventRequest): RelayEventResponse
    suspend fun createApprovalRequest(request: CreateApprovalRequestRequest): CreateApprovalRequestResponse
    suspend fun decideApproval(request: ApproveRequest): ApproveResponse
    suspend fun submitReport(request: SubmitReportRequest): SubmitReportResponse
    suspend fun registerNotificationToken(request: RegisterNotificationTokenRequest): RegisterNotificationTokenResponse
}

/** Convenience result type used by the backend client. */
sealed interface BackendResult<out T> {
    data class Success<T>(val value: T) : BackendResult<T>
    data class Failure(val error: ApiError) : BackendResult<Nothing>
    data object NetworkError : BackendResult<Nothing>
}