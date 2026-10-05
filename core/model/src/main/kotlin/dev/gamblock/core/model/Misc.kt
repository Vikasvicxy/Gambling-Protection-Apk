package dev.gamblock.core.model

import kotlinx.serialization.Serializable

/** Runtime state of the local VPN service, shared across UI and health. */
@Serializable
data class VpnRuntimeState(
    val isRunning: Boolean = false,
    val startedAtEpochMs: Long? = null,
    val queriesHandled: Long = 0L,
    val queriesBlocked: Long = 0L,
    val queriesAllowed: Long = 0L,
    /** Queries the block engine would have blocked but a custom user exception let through. */
    val exceptionsApplied: Long = 0L,
    /** QUIC / HTTP-3 UDP 443 packets silently dropped to force TCP fallback. */
    val quicDrops: Long = 0L,
    /** AAAA queries answered with an empty NOERROR to force IPv4. */
    val ipv6Suppressed: Long = 0L,
    /** Queries recognised as going to a search engine we can offer SafeSearch for. */
    val searchEngineQueries: Long = 0L,
    /** Allowed queries resolved through DNS-over-HTTPS instead of plaintext UDP. */
    val encryptedUpstreamQueries: Long = 0L,
    /** DoH attempts that failed. These fail closed: the client is told SERVFAIL. */
    val encryptedUpstreamFailures: Long = 0L,
    val failure: VpnFailure? = null,
    /** True when the VPN service was explicitly stopped by the user request. */
    val userStopped: Boolean = false,
) {
    enum class VpnFailure {
        AUTH_NOT_GRANTED,
        ESTABLISH_FAILED,
        STARTUP_ERROR,
        REVOKED,
        IN_SCHEDULE_PAUSE,
    }
}

/** A snapshot used by the dashboard. */
@Serializable
data class DashboardSummary(
    val hasCommitment: Boolean,
    val commitmentActive: Boolean,
    val protectionActive: Boolean,
    val health: HealthStatus,
    val vpnRunning: Boolean,
    val blocklistVersion: Int,
    val protectedDomainCount: Int,
    val lastUpdateEpochMs: Long,
    val totalBlockedAttempts: Int,
    val isOnline: Boolean,
    val scheduleEnabled: Boolean,
    val scheduleNowActive: Boolean,
)

@Serializable
data class InstallIdentity(
    val id: String,
    val createdAtEpochMs: Long,
)

@Serializable
data class AppVersionInfo(
    val versionName: String,
    val versionCode: Int,
)