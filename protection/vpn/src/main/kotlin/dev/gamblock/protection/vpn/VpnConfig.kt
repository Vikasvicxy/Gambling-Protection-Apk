package dev.gamblock.protection.vpn

import java.net.InetAddress

/** Fixed tun addressing for the local DNS-only VPN (see DESIGN/ARCHITECTURE docs). */
object VpnConfig {
    const val TUN_ADDR = "10.147.2.1"
    const val TUN_ADDR_PREFIX = 32
    const val TUN_MTU = 1400
    const val DNS_PORT = 53

    val TUN_ADDR_BYTES: ByteArray =
        try {
            InetAddress.getByName(TUN_ADDR).address
        } catch (_: Exception) {
            byteArrayOf(10.toByte(), 147.toByte(), 2.toByte(), 1.toByte())
        }
}