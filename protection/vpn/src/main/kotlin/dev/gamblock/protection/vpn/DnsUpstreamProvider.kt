package dev.gamblock.protection.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gamblock.core.common.logging.ShieldLogger
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Supplies the current carrier DNS resolver list while the app is NOT connected
 * through our VPN (calling ConnectivityManager from inside the tunnel would
 * self-refer). Cached and refreshed periodically.
 */
@Singleton
class DnsUpstreamProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: ShieldLogger,
) {
    private val connectivityManager: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile
    private var cached: List<InetAddress> = emptyList()

    @Volatile
    private var cacheRefreshedAtMs: Long = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = invalidate()
        override fun onLost(network: Network) = invalidate()
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = invalidate()
    }

    init {
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
        } catch (t: Throwable) {
            logger.w(TAG, "network callback registration failed: ${t.message}")
        }
        scope.launch {
            refresh(force = true)
            while (isActive) {
                delay(REFRESH_INTERVAL_MS)
                refresh(force = false)
            }
        }
    }

    /** Drop the cached resolver list so the next query re-discovers on the active network. */
    private fun invalidate() {
        cached = emptyList()
    }

    fun currentServers(): List<InetAddress> {
        if (cached.isEmpty()) refresh(force = true)
        return cached
    }

    private fun refresh(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - cacheRefreshedAtMs < REFRESH_INTERVAL_MS) return
        val found = discoverServers()
        if (found.isNotEmpty()) {
            cached = found
            cacheRefreshedAtMs = now
        }
    }

    private fun discoverServers(): List<InetAddress> {
        return try {
            val network: Network = connectivityManager.activeNetwork ?: return emptyList()
            val capabilities: NetworkCapabilities = connectivityManager.getNetworkCapabilities(network)
                ?: return emptyList()
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return emptyList()
            val linkProps = connectivityManager.getLinkProperties(network) ?: return emptyList()
            linkProps.dnsServers
                .filterNot { it.isAnyLocalAddress || it.isLoopbackAddress }
        } catch (t: Throwable) {
            logger.w(TAG, "DNS upstream discovery failed: ${t.message}")
            emptyList()
        }
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 30_000L
        private const val TAG = "DnsUpstreamProvider"
    }
}