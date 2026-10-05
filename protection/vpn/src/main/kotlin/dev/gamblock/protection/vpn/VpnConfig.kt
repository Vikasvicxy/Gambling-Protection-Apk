package dev.gamblock.protection.vpn

import java.net.InetAddress

/**
 * Fixed tun addressing and route scope for the local DNS-only VPN.
 *
 * The tunnel address deliberately sits in 198.18.0.0/15 (RFC 2544 benchmarking
 * range), NOT in 10.0.0.0/8. That range is reserved for benchmarking and is
 * never routed on the public internet, so it cannot collide with a real host,
 * and it leaves all three RFC 1918 private ranges free to be excluded from the
 * tunnel by [LAN_EXCLUSIONS].
 *
 * This matters: with the tunnel address inside 10.0.0.0/8, excluding the local
 * network from routing would also exclude the tunnel's own DNS address, and
 * every lookup would blackhole.
 */
object VpnConfig {
    const val TUN_ADDR = "198.18.0.1"
    const val TUN_ADDR_PREFIX = 32
    const val TUN_MTU = 1400
    const val DNS_PORT = 53

    val TUN_ADDR_BYTES: ByteArray =
        try {
            InetAddress.getByName(TUN_ADDR).address
        } catch (_: Exception) {
            byteArrayOf(198.toByte(), 18.toByte(), 0.toByte(), 1.toByte())
        }

    /**
     * RFC 1918 ranges kept out of the tunnel so Chromecast, printers, mDNS and
     * tethered clients still work while protection is on.
     *
     * These are passed to `addDisallowedRoute`, which subtracts them from the
     * tunnel's routing table rather than adding coverage. Must not overlap
     * [TUN_ADDR].
     */
    val LAN_EXCLUSIONS: List<LanRoute> = listOf(
        LanRoute("192.168.0.0", 16),
        LanRoute("10.0.0.0", 8),
        LanRoute("172.16.0.0", 12),
    )

    /**
     * Link-local and unique-local ranges. These are not strictly RFC 1918 but are
     * equally non-routable on the internet and equally needed for local discovery,
     * so they are excluded alongside them.
     */
    val LOCAL_EXCLUSIONS: List<LanRoute> = listOf(
        LanRoute("169.254.0.0", 16),
        LanRoute("fc00::", 7),
        LanRoute("fe80::", 10),
    )

    val ALL_EXCLUSIONS: List<LanRoute> = LAN_EXCLUSIONS + LOCAL_EXCLUSIONS
}

/** A single `addDisallowedRoute` entry. */
data class LanRoute(val address: String, val prefixLength: Int)