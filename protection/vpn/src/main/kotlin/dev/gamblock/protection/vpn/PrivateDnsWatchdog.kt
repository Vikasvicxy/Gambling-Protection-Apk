package dev.gamblock.protection.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gamblock.core.common.clock.WallClock
import dev.gamblock.core.common.dispatcher.DispatchersProvider
import dev.gamblock.core.common.logging.Logs
import dev.gamblock.core.common.logging.ShieldLogger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PrivateDnsStatus(
    val active: Boolean = false,
    val serverName: String? = null,
    val observedAtEpochMs: Long = 0L,
) {
    val warningMessage: String
        get() = if (active) {
            "Private DNS is active in Android Settings. This can bypass your shield."
        } else {
            "Private DNS is off. Android system DNS is used, so Shield can filter every lookup."
        }
}

/**
 * Watches Android's DoT (Private DNS) mode. When a private DNS server is
 * configured, system resolvers are bypassed by apps that honour it, which
 * removes their lookups from Shield's local filter. Detected purely locally
 * via ConnectivityManager; nothing is transmitted.
 */
@Singleton
class PrivateDnsWatchdog @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wallClock: WallClock,
    private val dispatchers: DispatchersProvider,
    private val logger: ShieldLogger,
) {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)

    private val _status = MutableStateFlow(PrivateDnsStatus())
    val status: StateFlow<PrivateDnsStatus> = _status.asStateFlow()

    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        if (callback != null) return
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (manager == null) {
            logger.w(Logs.VPN, "private DNS watchdog: connectivity service unavailable")
            return
        }
        refreshNow()
        val registered = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                apply(linkProperties)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                    scope.launch { refreshNow() }
                }
            }

            override fun onLost(network: Network) {
                scope.launch { refreshNow() }
            }

            override fun onAvailable(network: Network) {
                scope.launch { refreshNow() }
            }
        }
        try {
            manager.registerDefaultNetworkCallback(registered)
            callback = registered
        } catch (t: Throwable) {
            logger.w(Logs.VPN, "private DNS watchdog registration failed: ${t.message}")
        }
    }

    fun stop() {
        val registered = callback ?: return
        callback = null
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        runCatching { manager?.unregisterNetworkCallback(registered) }
    }

    fun refreshNow() {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val linkProperties = runCatching { manager?.activeNetwork?.let(manager::getLinkProperties) }.getOrNull()
        apply(linkProperties)
    }

    private fun apply(linkProperties: LinkProperties?) {
        val name = readPrivateDnsServerName(linkProperties)
        val next = PrivateDnsStatus(
            active = !name.isNullOrBlank(),
            serverName = name?.takeIf { it.isNotBlank() },
            observedAtEpochMs = wallClock.nowEpochMillis(),
        )
        if (next.active != _status.value.active || next.serverName != _status.value.serverName) {
            logger.i(Logs.VPN, "private DNS watchdog: active=${next.active} server=${next.serverName ?: "none"}")
        }
        _status.value = next
    }

    /**
     * [LinkProperties.getPrivateDnsServerName] only exists from Android 10, and
     * Shield supports Android 8, so the call is guarded. On older releases the
     * value is simply unavailable and the UI reports Private DNS as not
     * observed rather than risking a crash on a security screen.
     */
    private fun readPrivateDnsServerName(linkProperties: LinkProperties?): String? {
        if (linkProperties == null) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching { linkProperties.privateDnsServerName }.getOrNull()
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }
}
