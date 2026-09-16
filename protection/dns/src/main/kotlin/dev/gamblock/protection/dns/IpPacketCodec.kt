package dev.gamblock.protection.dns

/**
 * Parsing/crafting of the IPv4/IPv6 + UDP headers used to carry DNS between the
 * tun interface and the local DNS proxy. Only UDP is handled (DNS is UDP/TCP;
 * Phase 1 answers UDP only and drops TCP DNS to 53 which browsers rarely use).
 */
object IpPacketCodec {

    const val PROTOCOL_UDP = 17
    const val IPV4 = 4
    const val IPV6 = 6

    /** Parses a raw tun packet (with IP header) into a [UdpPacket]. Returns null if unsupported. */
    fun parseUdp(packet: ByteArray): UdpPacket? {
        if (packet.size < 20) return null
        val version = (packet[0].toInt() ushr 4) and 0x0F
        return when (version) {
            IPV4 -> parseIpv4(packet)
            IPV6 -> parseIpv6(packet)
            else -> null
        }
    }

    private fun parseIpv4(packet: ByteArray): UdpPacket? {
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < 20 || packet.size < ihl) return null
        if ((packet[9].toInt() and 0xFF) != PROTOCOL_UDP) return null
        val src = packet.copyOfRange(12, 16)
        val dst = packet.copyOfRange(16, 20)
        return parseUdpPayload(packet, ihl, src, dst)
    }

    private fun parseIpv6(packet: ByteArray): UdpPacket? {
        if (packet.size < 40) return null
        var nextHeader = packet[6].toInt() and 0xFF
        var offset = 40
        var iterations = 0
        while (nextHeader != PROTOCOL_UDP && nextHeader != 59 && iterations < 8) {
            iterations++
            if (offset + 8 > packet.size) return null
            nextHeader = packet[offset].toInt() and 0xFF
            offset += 8 + ((packet[offset + 1].toInt() and 0xFF) * 8)
        }
        if (nextHeader != PROTOCOL_UDP || offset + 8 > packet.size) return null
        val src = packet.copyOfRange(8, 24)
        val dst = packet.copyOfRange(24, 40)
        return parseUdpPayload(packet, offset, src, dst)
    }

    private fun parseUdpPayload(packet: ByteArray, udpOffset: Int, srcIp: ByteArray, dstIp: ByteArray): UdpPacket? {
        if (udpOffset + 8 > packet.size) return null
        val srcPort = ((packet[udpOffset].toInt() and 0xFF) shl 8) or (packet[udpOffset + 1].toInt() and 0xFF)
        val dstPort = ((packet[udpOffset + 2].toInt() and 0xFF) shl 8) or (packet[udpOffset + 3].toInt() and 0xFF)
        val len = ((packet[udpOffset + 4].toInt() and 0xFF) shl 8) or (packet[udpOffset + 5].toInt() and 0xFF)
        val payloadLen = (len - 8).coerceAtMost(packet.size - udpOffset - 8)
        if (payloadLen <= 0) return null
        val payload = packet.copyOfRange(udpOffset + 8, udpOffset + 8 + payloadLen)
        return UdpPacket(
            family = if (srcIp.size == 4) IPV4 else IPV6,
            srcAddress = srcIp,
            dstAddress = dstIp,
            srcPort = srcPort,
            dstPort = dstPort,
            payload = payload,
        )
    }

    /**
     * Crafts an IP+UDP packet containing [dnsPayload] from our DNS endpoint
     * [sourceIp]/53 back to [destinationIp]/[destinationPort].
     */
    fun craftUdpResponse(
        family: Int,
        sourceIp: ByteArray,
        destinationIp: ByteArray,
        destinationPort: Int,
        dnsPayload: ByteArray,
        mtuLimit: Int = 2048,
    ): ByteArray {
        require(destinationPort in 0..65535)
        return when (family) {
            IPV4 -> craftIpv4(sourceIp, destinationIp, destinationPort, dnsPayload, mtuLimit)
            IPV6 -> craftIpv6(sourceIp, destinationIp, destinationPort, dnsPayload, mtuLimit)
            else -> throw IllegalArgumentException("unsupported family $family")
        }
    }

    private fun craftIpv4(src: ByteArray, dst: ByteArray, dport: Int, dns: ByteArray, mtu: Int): ByteArray {
        require(src.size == 4 && dst.size == 4)
        val udpLen = 8 + dns.size
        val totalLen = 20 + udpLen
        require(totalLen <= mtu) { "response too large" }
        val buf = ByteArray(totalLen)
        buf[0] = (0x40 or 0x05).toByte() // IPv4, IHL=5
        buf[1] = 0
        buf[2] = ((totalLen shr 8) and 0xFF).toByte()
        buf[3] = (totalLen and 0xFF).toByte()
        buf[8] = 64.toByte() // TTL
        buf[9] = PROTOCOL_UDP.toByte()
        System.arraycopy(src, 0, buf, 12, 4)
        System.arraycopy(dst, 0, buf, 16, 4)

        val udpOffset = 20
        buf[udpOffset] = 0
        buf[udpOffset + 1] = 53 // source port = DNS
        buf[udpOffset + 2] = ((dport shr 8) and 0xFF).toByte()
        buf[udpOffset + 3] = (dport and 0xFF).toByte()
        buf[udpOffset + 4] = ((udpLen shr 8) and 0xFF).toByte()
        buf[udpOffset + 5] = (udpLen and 0xFF).toByte()
        buf[udpOffset + 6] = 0
        buf[udpOffset + 7] = 0 // checksum placeholder
        System.arraycopy(dns, 0, buf, udpOffset + 8, dns.size)

        val check = InternetChecksum.compute(buf, 0, 20)
        buf[10] = ((check shr 8) and 0xFF).toByte()
        buf[11] = (check and 0xFF).toByte()
        return buf
    }

    private fun craftIpv6(src: ByteArray, dst: ByteArray, dport: Int, dns: ByteArray, mtu: Int): ByteArray {
        require(src.size == 16 && dst.size == 16)
        val udpLen = 8 + dns.size
        val totalLen = 40 + udpLen
        require(totalLen <= mtu.takeIf { it > 0 } ?: 2048) { "response too large" }
        val buf = ByteArray(totalLen)
        buf[0] = (0x60).toByte() // version 6
        buf[1] = 0 // traffic class
        buf[2] = 0
        buf[3] = 0 // flow label
        buf[4] = ((udpLen shr 8) and 0xFF).toByte()
        buf[5] = (udpLen and 0xFF).toByte()
        buf[6] = PROTOCOL_UDP.toByte()
        buf[7] = 64.toByte() // hop limit
        System.arraycopy(src, 0, buf, 8, 16)
        System.arraycopy(dst, 0, buf, 24, 16)

        val udpOffset = 40
        buf[udpOffset] = 0
        buf[udpOffset + 1] = 53
        buf[udpOffset + 2] = ((dport shr 8) and 0xFF).toByte()
        buf[udpOffset + 3] = (dport and 0xFF).toByte()
        buf[udpOffset + 4] = ((udpLen shr 8) and 0xFF).toByte()
        buf[udpOffset + 5] = (udpLen and 0xFF).toByte()
        buf[udpOffset + 6] = 0
        buf[udpOffset + 7] = 0
        System.arraycopy(dns, 0, buf, udpOffset + 8, dns.size)

        // UDP checksum required for IPv6: pseudo-header + UDP header + payload.
        val checksum = ipv6UdpChecksum(src, dst, buf, udpOffset, dns)
        buf[udpOffset + 6] = ((checksum shr 8) and 0xFF).toByte()
        buf[udpOffset + 7] = (checksum and 0xFF).toByte()
        return buf
    }

    private fun ipv6UdpChecksum(src: ByteArray, dst: ByteArray, packet: ByteArray, udpOffset: Int, dns: ByteArray): Int {
        var sum = 0L
        fun addPair(b0: Byte, b1: Byte) {
            sum += ((b0.toInt() and 0xFF) shl 8) or (b1.toInt() and 0xFF)
        }
        var i = 0
        while (i < 16) {
            addPair(src[i], src[i + 1]); i += 2
        }
        var j = 0
        while (j < 16) {
            addPair(dst[j], dst[j + 1]); j += 2
        }
        val udpLen = packet.size - udpOffset
        sum += udpLen.toLong()
        addPair(0, PROTOCOL_UDP.toByte())
        var k = udpOffset
        val end = packet.size
        while (k + 1 < end) {
            addPair(packet[k], packet[k + 1]); k += 2
        }
        if (k < end) sum += (packet[k].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }
}