package dev.gamblock.protection.vpn

import java.net.InetAddress

/**
 * Pure selection logic for DNS forward targets: tries the transport's physical
 * resolvers first, then falls back to well-known public Do53 resolvers when the
 * carrier resolver is unreliable. All fallbacks are IPv4 literals so we never
 * bootstrap via DNS (which would deadlock).
 */
object UpstreamFallbackResolver {

    val PUBLIC_FALLBACKS: List<String> = listOf("1.1.1.1", "9.9.9.9", "8.8.8.8")

    /** Parses a dotted-quad IPv4 string into an [InetAddress] without network IO. */
    fun ipv4Literal(address: String): InetAddress? = try {
        val parts = address.split('.')
        if (parts.size != 4) return null
        val octets = parts.map { it.toInt() }
        if (octets.any { it !in 0..255 }) return null
        InetAddress.getByAddress(octets.map { it.toByte() }.toByteArray())
    } catch (_: Throwable) {
        null
    }

    /**
     * Physical resolvers first (deduped, length-ordered), then the public
     * fallbacks. Never contains Shield's tun address because [physical] is
     * already guaranteed non-VPN by the upstream provider.
     */
    fun ordered(physical: List<InetAddress>): List<InetAddress> {
        val seen = HashSet<InetAddress>()
        return buildList {
            physical.forEach { if (seen.add(it)) add(it) }
            PUBLIC_FALLBACKS.mapNotNull { ipv4Literal(it) }.forEach { if (seen.add(it)) add(it) }
        }
    }
}