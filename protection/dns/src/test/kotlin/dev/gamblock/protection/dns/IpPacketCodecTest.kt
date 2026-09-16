package dev.gamblock.protection.dns

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class IpPacketCodecTest {

    private val ipv4Src = byteArrayOf(10, 0, 2, 15)
    private val ipv4Dst = byteArrayOf(10, 147.toByte(), 2, 1)
    private val ipv6Src = ByteArray(16).also { it[15] = 1 }
    private val ipv6Dst = ByteArray(16).also { it[15] = 2 }
    private val payload = DnsResponseFactory.encodeName("bet-example.test") + byteArrayOf(0, 0, 1, 0, 1)

    private fun ipv4Packet(proto: Int = IpPacketCodec.PROTOCOL_UDP, udpPayload: ByteArray = payload): ByteArray {
        val udpLen = 8 + udpPayload.size
        val totalLength = 20 + udpLen
        val buf = ByteArray(totalLength)
        buf[0] = 0x45.toByte()
        buf[2] = ((totalLength shr 8) and 0xFF).toByte()
        buf[3] = (totalLength and 0xFF).toByte()
        buf[8] = 64.toByte()
        buf[9] = proto.toByte()
        System.arraycopy(ipv4Src, 0, buf, 12, 4)
        System.arraycopy(ipv4Dst, 0, buf, 16, 4)
        buf[20] = 0
        buf[21] = 53
        buf[22] = 0
        buf[23] = 0x23 // ephemeral port
        buf[24] = ((udpLen shr 8) and 0xFF).toByte()
        buf[25] = (udpLen and 0xFF).toByte()
        System.arraycopy(udpPayload, 0, buf, 28, udpPayload.size)
        val check = InternetChecksum.compute(buf, 0, 20)
        buf[10] = ((check shr 8) and 0xFF).toByte()
        buf[11] = (check and 0xFF).toByte()
        return buf
    }

    private fun validIpv4Checksum(packet: ByteArray): Boolean {
        val stored = ((packet[10].toInt() and 0xFF) shl 8) or (packet[11].toInt() and 0xFF)
        val zeroed = packet.copyOf()
        zeroed[10] = 0
        zeroed[11] = 0
        return InternetChecksum.compute(zeroed, 0, 20) == stored
    }

    private fun validIpv6UdpChecksum(packet: ByteArray): Boolean {
        // Independent reconstruction: pseudo-header + UDP header (checksum zeroed) + payload.
        var sum = 0L
        fun add(b0: Byte, b1: Byte) {
            sum += ((b0.toInt() and 0xFF) shl 8) or (b1.toInt() and 0xFF)
        }
        var i = 8
        while (i < 40) { add(packet[i], packet[i + 1]); i += 2 }
        val udpLen = packet.size - 40
        sum += udpLen.toLong()
        add(0, 17)
        var k = 40
        while (k + 1 < packet.size) {
            val b0 = if (k == 46) 0 else packet[k]
            val b1 = if (k + 1 == 47) 0 else packet[k + 1]
            add(b0, b1)
            k += 2
        }
        if (k < packet.size) sum += (packet[k].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        val stored = ((packet[46].toInt() and 0xFF) shl 8) or (packet[47].toInt() and 0xFF)
        return (sum.inv() and 0xFFFF).toInt() == stored
    }

    @Test
    fun `parses an IPv4 UDP DNS packet`() {
        val packet = ipv4Packet()
        val udp = IpPacketCodec.parseUdp(packet)
        assertThat(udp).isNotNull()
        assertThat(udp!!.family).isEqualTo(IpPacketCodec.IPV4)
        assertThat(udp.srcAddress.toList()).isEqualTo(ipv4Src.toList())
        assertThat(udp.dstAddress.toList()).isEqualTo(ipv4Dst.toList())
        assertThat(udp.srcPort).isEqualTo(53)
        assertThat(udp.dstPort).isEqualTo(0x23)
        assertThat(udp.payload.toList()).isEqualTo(payload.toList())
    }

    @Test
    fun `parses an IPv6 UDP DNS packet`() {
        val udpLen = 8 + payload.size
        val buf = ByteArray(40 + udpLen)
        buf[0] = 0x60.toByte()
        buf[4] = ((udpLen shr 8) and 0xFF).toByte()
        buf[5] = (udpLen and 0xFF).toByte()
        buf[6] = IpPacketCodec.PROTOCOL_UDP.toByte()
        buf[7] = 64.toByte()
        System.arraycopy(ipv6Src, 0, buf, 8, 16)
        System.arraycopy(ipv6Dst, 0, buf, 24, 16)
        buf[40] = 0
        buf[41] = 53
        buf[42] = 0
        buf[43] = 0x35
        buf[44] = ((udpLen shr 8) and 0xFF).toByte()
        buf[45] = (udpLen and 0xFF).toByte()
        System.arraycopy(payload, 0, buf, 48, payload.size)

        val udp = IpPacketCodec.parseUdp(buf)
        assertThat(udp).isNotNull()
        assertThat(udp!!.family).isEqualTo(IpPacketCodec.IPV6)
        assertThat(udp.dstPort).isEqualTo(0x35)
        assertThat(udp.payload.toList()).isEqualTo(payload.toList())
    }

    @Test
    fun `ignores non-UDP packets`() {
        assertThat(IpPacketCodec.parseUdp(ipv4Packet(proto = 1))).isNull()
    }

    @Test
    fun `ignores truncated packets`() {
        assertThat(IpPacketCodec.parseUdp(ByteArray(19))).isNull()
        assertThat(IpPacketCodec.parseUdp(ByteArray(20))).isNull() // IPv4, IHL 5, but no IP validation otherwise
    }

    @Test
    fun `ignores too-short IPv6 packets`() {
        assertThat(IpPacketCodec.parseUdp(ByteArray(39))).isNull()
    }

    @Test
    fun `crafts a valid IPv4 response carrying the DNS payload`() {
        val crafted = IpPacketCodec.craftUdpResponse(
            family = IpPacketCodec.IPV4,
            sourceIp = ipv4Dst,
            destinationIp = ipv4Src,
            destinationPort = 0x23,
            dnsPayload = payload,
        )
        assertThat(validIpv4Checksum(crafted)).isTrue()
        val parsed = IpPacketCodec.parseUdp(crafted)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.srcPort).isEqualTo(53)
        assertThat(parsed.dstPort).isEqualTo(0x23)
        assertThat(parsed.payload.toList()).isEqualTo(payload.toList())
        assertThat(parsed.srcAddress.toList()).isEqualTo(ipv4Dst.toList())
        assertThat(parsed.dstAddress.toList()).isEqualTo(ipv4Src.toList())
    }

    @Test
    fun `crafts a valid IPv6 response with a UDP checksum`() {
        val crafted = IpPacketCodec.craftUdpResponse(
            family = IpPacketCodec.IPV6,
            sourceIp = ipv6Dst,
            destinationIp = ipv6Src,
            destinationPort = 0x35,
            dnsPayload = payload,
        )
        assertThat(validIpv6UdpChecksum(crafted)).isTrue()
        val parsed = IpPacketCodec.parseUdp(crafted)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.family).isEqualTo(IpPacketCodec.IPV6)
        assertThat(parsed.dstPort).isEqualTo(0x35)
        assertThat(parsed.payload.toList()).isEqualTo(payload.toList())
    }

    @Test
    fun `craftUdpResponse validates family and port`() {
        assertThrows(IllegalArgumentException::class.java) {
            IpPacketCodec.craftUdpResponse(9, ipv4Dst, ipv4Src, 53, payload)
        }
        assertThrows(IllegalArgumentException::class.java) {
            IpPacketCodec.craftUdpResponse(IpPacketCodec.IPV4, ipv4Dst, ipv4Src, -1, payload)
        }
    }

    @Test
    fun `craftUdpResponse enforces the MTU limit`() {
        val big = ByteArray(1500)
        assertThrows(IllegalArgumentException::class.java) {
            IpPacketCodec.craftUdpResponse(IpPacketCodec.IPV4, ipv4Dst, ipv4Src, 53, big, mtuLimit = 800)
        }
    }
}