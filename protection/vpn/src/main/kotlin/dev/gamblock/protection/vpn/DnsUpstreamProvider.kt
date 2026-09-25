package dev.gamblock.protection.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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

/**
 * Supplies the current carrier/Wi-Fi resolver list used to forward allowed DNS
 * queries.
 *
 * Android quirk handled here: once Shield's tunnel is the default network,
 * [ConnectivityManager.getActiveNetwork] resolves to Shield's own VPN network and
 * [android.net.LinkProperties.dnsServers] on that network reports the private tun
 * address we advertise via `VpnService.Builder.addDnsServer`. Forwarding to that
 * address would re-enter the tunnel (or be unroutable), blackholing every allowed
 * lookup. We therefore keep a cache of the *physical* underlying network (any
 * network that carries the INTERNET capability and is NOT a VPN transport) and its
 * DNS servers, refreshed from an all-networks callback, so allowed queries always
 * leave via the real connection and can never loop back into the tunnel.
 */
@Singleton
class DnsUpstreamProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: ShieldLogger,
) {
    private val connectivityManager: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile
    private var cached: DnsUpstream = DnsUpstream(network = null, servers = emptyList())

    @Volatile
    private var cacheRefreshedAtMs: Long = 0L

    private val refreshLock = Any()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh(force = true)
        override fun onLost(network: Network) = refresh(force = true)
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = refresh(force = true)
        override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) = refresh(force = true)
    }

    init {
        try {
            connectivityManager.registerNetworkCallback(
                NetworkRequest.Builder().build(), // all networks, incl. physical underneath our VPN
                networkCallback,
            )
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

    /**
     * The physical resolver target for allowed queries: the `Network` to bind the
     * forwarding socket to (null falls back to `VpnService.protect` only) plus the
     * DNS servers to query on it. Never contains Shield's own tun address.
     */
    fun currentUpstream(): DnsUpstream {
        if (cached.servers.isEmpty()) {
            synchronized(refreshLock) {
                if (cached.servers.isEmpty()) {
                    val found = scanPhysical()
                    if (found.servers.isNotEmpty()) {
                        cached = found
                        cacheRefreshedAtMs = System.currentTimeMillis()
                    }
                }
            }
        }
        return cached
    }

    /** Compatibility accessor used by older call sites. */
    fun currentServers(): List<InetAddress> = currentUpstream().servers

    private fun refresh(force: Boolean) {
        val now = System.currentTimeMillis()
        synchronized(refreshLock) {
            if (!force && now - cacheRefreshedAtMs < REFRESH_INTERVAL_MS) return
            val found = scanPhysical()
            if (found.servers.isNotEmpty()) {
                cached = found
                cacheRefreshedAtMs = now
            } else if (force) {
                // The physical network is gone; drop stale resolvers so the next
                // query re-discovers instead of hammering a dead resolver.
                cached = DnsUpstream(network = null, servers = emptyList())
            }
        }
    }

    /** Best non-VPN network with the INTERNET capability, plus its resolvers. */
    private fun scanPhysical(): DnsUpstream {
        var bestNetwork: Network? = null
        var bestScore = -1
        val networks: List<Network> = try {
            connectivityManager.allNetworks.toList()
        } catch (t: Throwable) {
            logger.w(TAG, "allNetworks scan failed: ${t.message}")
            emptyList()
        }
        for (network in networks) {
            val score = physicalScore(network)
            if (score > bestScore) {
                bestScore = score
                bestNetwork = network
            }
        }
        val servers = bestNetwork?.let { resolversOf(it) } ?: emptyList()

        return DnsUpstream(
            network = bestNetwork.takeIf { servers.isNotEmpty() },
            servers = servers,
        )
    }

    private fun physicalScore(network: Network): Int {
        val capabilities = try {
            connectivityManager.getNetworkCapabilities(network)
        } catch (t: Throwable) {
            null
        } ?: return -1
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return -1
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return -1
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 30
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 20
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 10
            else -> 1
        }
    }

    private fun resolversOf(network: Network): List<InetAddress> {
        val resolvers = try {
            connectivityManager.getLinkProperties(network)?.dnsServers.orEmpty()
        } catch (t: Throwable) {
            logger.w(TAG, "resolver fetch failed: ${t.message}")
            emptyList()
        }
        return usables(resolvers)
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 30_000L
        private const val TAG = "DnsUpstreamProvider"

        /** Only real routable resolvers qualify; the tun address is never a loopback anyway. */
        fun usables(servers: List<InetAddress>): List<InetAddress> =
            servers.filterNot { it.isAnyLocalAddress || it.isLoopbackAddress }
    }
}

/**
 * A forward target for allowed DNS queries: the physical [network] to pin the
 * socket to (bypasses the tunnel, preventing loops) and the [servers] to query.
 */
data class DnsUpstream(
    val network: Network?,
    val servers: List<InetAddress>,
)