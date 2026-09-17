package dev.gamblock.data.accountability.backend

import dev.gamblock.core.model.AcceptPairingRequest
import dev.gamblock.core.model.AcceptPairingResponse
import dev.gamblock.core.model.ApiError
import dev.gamblock.core.model.ApproveRequest
import dev.gamblock.core.model.ApproveResponse
import dev.gamblock.core.model.BackendResult
import dev.gamblock.core.model.CreateApprovalRequestRequest
import dev.gamblock.core.model.CreateApprovalRequestResponse
import dev.gamblock.core.model.CreatePairingRequest
import dev.gamblock.core.model.CreatePairingResponse
import dev.gamblock.core.model.HeartbeatUploadRequest
import dev.gamblock.core.model.HeartbeatUploadResponse
import dev.gamblock.core.model.RegisterDeviceRequest
import dev.gamblock.core.model.RegisterDeviceResponse
import dev.gamblock.core.model.RegisterNotificationTokenRequest
import dev.gamblock.core.model.RegisterNotificationTokenResponse
import dev.gamblock.core.model.RelayEventRequest
import dev.gamblock.core.model.RelayEventResponse
import dev.gamblock.core.model.ShieldBackendClient
import dev.gamblock.core.model.SubmitReportRequest
import dev.gamblock.core.model.SubmitReportResponse
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Hosting-agnostic HTTP implementation of [ShieldBackendClient].
 *
 * No browsing traffic ever traverses this client: only pairing/heartbeat/event/report/
 * notification-registration requests. Endpoints are consumed but never contacted for the
 * normal DNS/VPN path.
 */
class OkHttpBackendClient(
    private val baseUrl: String,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val http: OkHttpClient = defaultClient(),
) : ShieldBackendClient {

    override suspend fun registerDevice(request: RegisterDeviceRequest): RegisterDeviceResponse =
        post("/api/devices/register", request)

    override suspend fun uploadHeartbeat(request: HeartbeatUploadRequest): HeartbeatUploadResponse =
        post("/api/heartbeats", request)

    override suspend fun createPairing(request: CreatePairingRequest): CreatePairingResponse =
        post("/api/pairing/invites", request)

    override suspend fun acceptPairing(request: AcceptPairingRequest): AcceptPairingResponse =
        post("/api/pairing/accept", request)

    override suspend fun relayEvent(request: RelayEventRequest): RelayEventResponse =
        post("/api/events", request)

    override suspend fun createApprovalRequest(
        request: CreateApprovalRequestRequest,
    ): CreateApprovalRequestResponse =
        post("/api/approvals", request)

    override suspend fun decideApproval(request: ApproveRequest): ApproveResponse =
        post("/api/approvals/decide", request)

    override suspend fun submitReport(request: SubmitReportRequest): SubmitReportResponse =
        post("/api/reports", request)

    override suspend fun registerNotificationToken(
        request: RegisterNotificationTokenRequest,
    ): RegisterNotificationTokenResponse =
        post("/api/notifications/token", request)

    private suspend inline fun <reified B : Any, reified T> post(path: String, body: B): T =
        withContext(Dispatchers.IO) {
            val payload = json.encodeToString(body)
            val req = Request.Builder()
                .url("$baseUrl$path")
                .header("Content-Type", "application/json")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                require(resp.isSuccessful) { "backend error ${resp.code}: $text" }
                json.decodeFromString<T>(text)
            }
        }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}

/** Source-of-truth constant for the backend deployment channel (editable outside VCS). */
object BackendConfig {
    const val DEFAULT_BASE_URL = "https://api.shield.gamblock.dev"
}

/** Convenience wrapper converting exceptions into [BackendResult]. */
suspend fun <T> shieldCall(block: suspend () -> T): BackendResult<T> = try {
    BackendResult.Success(block())
} catch (e: Exception) {
    BackendResult.NetworkError
}