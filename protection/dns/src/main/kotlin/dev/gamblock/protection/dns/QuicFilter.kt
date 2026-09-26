package dev.gamblock.protection.dns

import dev.gamblock.core.model.EncryptedDnsPolicy

/**
 * QUIC / HTTP-3 leak filter. Browsers negotiate QUIC over UDP 443; dropping it
 * forces fallback to TCP 443 so name resolution still traverses the local DNS proxy.
 * Only UDP 443 is affected: every other protocol, port, and TCP flow is untouched.
 */
object QuicFilter {

    const val DROP_QUIC_UDP: Boolean = true

    fun shouldDrop(udp: UdpPacket, blockEncryptedBrowsers: Boolean = true): Boolean {
        if (!blockEncryptedBrowsers) return false
        if (!DROP_QUIC_UDP) return false
        return EncryptedDnsPolicy.isQuicDestinationPort(udp.dstPort)
    }

    fun shouldDropPacket(packet: ByteArray, length: Int = packet.size): Boolean {
        val udp = IpPacketCodec.parseUdp(packet, length) ?: return false
        return shouldDrop(udp)
    }

    fun classifyPort(port: Int): PortVerdict =
        if (EncryptedDnsPolicy.isQuicDestinationPort(port)) PortVerdict.QUIC_DROP else PortVerdict.PASS

    enum class PortVerdict { QUIC_DROP, PASS }
}
