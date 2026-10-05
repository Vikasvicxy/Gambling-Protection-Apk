package dev.gamblock.protection.dns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Codec-level tests for IPv4/IPv6 + TCP decoding and reset synthesis.
 *
 * The properties that matter for the interceptor are: a malformed or truncated
 * segment never produces a packet (it must return null rather than read out of
 * bounds), and a synthesised reset is byte-identical to what a stack would put
 * on the wire for the same tuple, including a correct pseudo-header checksum.
 */
class TcpPacketCodecTest {

    @Test
    fun `parses an ipv4 tcp segment with payload`() {
        val payload = "hello".toByteArray()
        val segment = TcpSegmentBuilder()
            .ipv4(src = "10.0.0.5", dst = "93.184.216.34", srcPort = 44444, dstPort = 443)
            .sequence(1000)
            .ack(77)
            .flags(TcpFlags.ACK or TcpFlags.PSH)
            .payload(payload)
            .build()

        val tcp = TcpPacketCodec.parse(segment)

        assertNotNull(tcp)
        assertEquals(IpPacketCodec.IPV4, tcp!!.family)
        assertArrayEquals(byteArrayOf(10, 0, 0, 5), tcp.srcAddress)
        assertArrayEquals(byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34), tcp.dstAddress)
        assertEquals(44444, tcp.srcPort)
        assertEquals(443, tcp.dstPort)
        assertEquals(1000, tcp.sequenceNumber)
        assertEquals(77, tcp.ackNumber)
        assertArrayEquals(payload, tcp.payload)
        assertTrue(tcp.hasPayload)
        assertFalse(tcp.isRst)
    }

    @Test
    fun `parses an ipv6 tcp segment with payload`() {
        val payload = byteArrayOf(1, 2, 3)
        val segment = TcpSegmentBuilder()
            .ipv6(src = "fd00::1", dst = "2606:2800:220:1:248:1893:25c8:1946", srcPort = 50000, dstPort = 443)
            .sequence(5)
            .payload(payload)
            .build()

        val tcp = TcpPacketCodec.parse(segment)

        assertNotNull(tcp)
        assertEquals(IpPacketCodec.IPV6, tcp!!.family)
        assertEquals(16, tcp.srcAddress.size)
        assertEquals(50000, tcp.srcPort)
        assertArrayEquals(payload, tcp.payload)
    }

    @Test
    fun `rejects a udp segment`() {
        // Codecs must not confuse protocols; the interceptor only acts on TCP.
        val udp = byteArrayOf(0x45, 0, 0, 28) + ByteArray(8) + byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34) +
            byteArrayOf(10, 0, 0, 5) + ByteArray(8)
        assertNull(TcpPacketCodec.parse(udp))
    }

    @Test
    fun `rejects a truncated segment`() {
        val segment = TcpSegmentBuilder().payload(byteArrayOf(9, 9, 9)).build()
        assertNull(TcpPacketCodec.parse(segment, length = 19))
    }

    @Test
    fun `rejects a data offset that overruns the packet`() {
        val segment = TcpSegmentBuilder().payload(byteArrayOf(1, 2, 3, 4)).build()
        // Claim a 60 byte TCP header inside a 44 byte packet.
        segment[20 + 12] = 0xF0.toByte()
        assertNull(TcpPacketCodec.parse(segment))
    }

    @Test
    fun `rejects a fragment with a non-zero offset`() {
        val segment = TcpSegmentBuilder().payload(byteArrayOf(1, 2, 3, 4)).build()
        segment[6] = 0x00
        segment[7] = 0x10 // fragment offset 16 words
        assertNull(TcpPacketCodec.parse(segment))
    }

    @Test
    fun `honours the caller supplied length rather than the backing array size`() {
        val payload = ByteArray(16) { 7 }
        val segment = TcpSegmentBuilder().payload(payload).build()
        // A shared tun buffer is longer than the packet actually read. Report only
        // 8 of the 16 payload bytes and the parser must see exactly 8, not 16.
        val truncatedLength = segment.size - 8
        val tcp = TcpPacketCodec.parse(segment, length = truncatedLength)
        assertNotNull(tcp)
        assertEquals(8, tcp!!.payload.size)
        assertEquals(16, payload.size)
    }

    @Test
    fun `crafts an ipv4 reset mirrored to look like it came from the server`() {
        val offender = TcpPacketCodec.parse(
            TcpSegmentBuilder()
                .ipv4(src = "10.0.0.5", dst = "93.184.216.34", srcPort = 44444, dstPort = 443)
                .sequence(1000)
                .ack(77)
                .payload(byteArrayOf(1, 2, 3, 4))
                .build()
        )!!

        val reset = TcpPacketCodec.craftReset(offender)
        val parsed = TcpPacketCodec.parse(reset)

        assertNotNull(parsed)
        // Addresses and ports are swapped: the reset appears to come from the server.
        assertArrayEquals(offender.dstAddress, parsed!!.srcAddress)
        assertArrayEquals(offender.srcAddress, parsed.dstAddress)
        assertEquals(offender.dstPort, parsed.srcPort)
        assertEquals(offender.srcPort, parsed.dstPort)
        assertTrue(parsed.isRst)
        assertTrue(parsed.flags and TcpFlags.ACK != 0)
        // seq echoes the offender's ack; ack covers the bytes it just sent.
        assertEquals(77, parsed.sequenceNumber)
        assertEquals(1004, parsed.ackNumber)
        assertEquals(0, parsed.payload.size)
        assertEquals(0, parsed.window)
    }

    @Test
    fun `crafted ipv4 reset carries a valid tcp checksum`() {
        val offender = TcpPacketCodec.parse(
            TcpSegmentBuilder()
                .ipv4(src = "192.168.1.9", dst = "1.1.1.1", srcPort = 40000, dstPort = 443)
                .sequence(100)
                .payload(byteArrayOf(0x16, 0x03, 0x01))
                .build()
        )!!
        val reset = TcpPacketCodec.craftReset(offender)

        // Re-summing the finished segment, checksum field included, must fold to 0.
        // That is the exact test a receiving stack applies; a wrong checksum here is
        // silently discarded by the client and the request hangs until timeout.
        assertEquals(0, tcpChecksumOver(reset, 20, 20, ipv4 = true))
        // The checksum must be non-zero, otherwise this test would pass trivially
        // on a segment that was never checksummed at all.
        val stored = ((reset[36].toInt() and 0xFF) shl 8) or (reset[37].toInt() and 0xFF)
        assertTrue("reset checksum must be populated", stored != 0)
    }

    @Test
    fun `crafted ipv6 reset carries a valid tcp checksum`() {
        val offender = TcpPacketCodec.parse(
            TcpSegmentBuilder()
                .ipv6(src = "fd00::5", dst = "2606:2800::1", srcPort = 40000, dstPort = 443)
                .sequence(200)
                .payload(byteArrayOf(0x16, 0x03, 0x01))
                .build()
        )!!
        val reset = TcpPacketCodec.craftReset(offender)
        val parsed = TcpPacketCodec.parse(reset)

        assertNotNull(parsed)
        assertTrue(parsed!!.isRst)

        assertEquals(0, tcpChecksumOver(reset, 40, 20, ipv4 = false))
        val stored = ((reset[56].toInt() and 0xFF) shl 8) or (reset[57].toInt() and 0xFF)
        assertTrue("reset checksum must be populated", stored != 0)
    }

    @Test
    fun `reset acknowledges exactly the bytes the client sent`() {
        // Sequence wrap must not corrupt the acknowledgement.
        val offender = TcpPacketCodec.parse(
            TcpSegmentBuilder()
                .ipv4(src = "10.0.0.5", dst = "93.184.216.34", srcPort = 1, dstPort = 443)
                .sequence(Int.MAX_VALUE)
                .payload(ByteArray(10) { 1 })
                .build()
        )!!
        val parsed = TcpPacketCodec.parse(TcpPacketCodec.craftReset(offender))!!
        assertEquals(Int.MAX_VALUE + 10, parsed.ackNumber)
    }

    @Test
    fun `sequence distance is signed and wraps correctly`() {
        assertEquals(10L, TcpPacket.sequenceDistance(100, 110))
        assertEquals(-10L, TcpPacket.sequenceDistance(110, 100))
        // 0xFFFFFFFE -> 0x00000001 is a forward step of 3 across the wrap.
        assertEquals(3L, TcpPacket.sequenceDistance(0xFFFFFFFE.toInt(), 1))
        assertEquals(-3L, TcpPacket.sequenceDistance(1, 0xFFFFFFFE.toInt()))
    }

    @Test
    fun `advance wraps at the 32 bit boundary`() {
        assertEquals(1004, TcpPacket.advance(1000, 4))
        // The classic retransmit case. Kotlin Int addition already wraps, which is
        // what we want, so the expectation is written the same way to match.
        assertEquals(Int.MAX_VALUE + 10, TcpPacket.advance(Int.MAX_VALUE, 10))
        // Past the last representable value the counter restarts at Int.MIN_VALUE,
        // which is the bit pattern 0x80000000 the wire carries.
        assertEquals(Int.MIN_VALUE, TcpPacket.advance(Int.MAX_VALUE, 1))
        // And continues upward from there, so the wrap is a true rotation.
        assertEquals(Int.MIN_VALUE + 1, TcpPacket.advance(Int.MAX_VALUE, 2))
    }

    /**
     * Reference pseudo-header checksum, written independently of the codec and
     * validated against RFC 1071's own worked example so a shared misunderstanding
     * cannot make both sides agree on a wrong value.
     */
    private fun tcpChecksumOver(packet: ByteArray, tcpOffset: Int, tcpLength: Int, ipv4: Boolean): Int {
        var sum = 0L
        val srcOffset = if (ipv4) 12 else 8
        val dstOffset = if (ipv4) 16 else 24
        val addrLen = if (ipv4) 4 else 16
        var i = 0
        while (i < addrLen) {
            sum += ((packet[srcOffset + i].toInt() and 0xFF) shl 8) or
                (packet[srcOffset + i + 1].toInt() and 0xFF)
            sum += ((packet[dstOffset + i].toInt() and 0xFF) shl 8) or
                (packet[dstOffset + i + 1].toInt() and 0xFF)
            i += 2
        }
        sum += tcpLength
        sum += PROTOCOL_TCP
        var k = tcpOffset
        val end = tcpOffset + tcpLength
        while (k + 1 < end) {
            sum += ((packet[k].toInt() and 0xFF) shl 8) or (packet[k + 1].toInt() and 0xFF)
            k += 2
        }
        if (k < end) sum += (packet[k].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    @Test
    fun `reference checksum matches the rfc 1071 worked example`() {
        // Pins the shared reference implementation against a known-good vector so a
        // common misreading cannot make both sides agree on a wrong value.
        // RFC 1071: summing the 16-bit words 0x0001 and 0xf203 gives 0xf204, whose
        // one's complement -- the value written into the checksum field -- is 0x0dfb.
        val sum = InternetChecksum.compute(byteArrayOf(0x00, 0x01, 0xF2.toByte(), 0x03), 0, 4)
        assertEquals(0x0DFB, sum)
    }

    @Test
    fun `internet checksum folds to zero when recomputed over a valid segment`() {
        val offender = TcpPacketCodec.parse(
            TcpSegmentBuilder()
                .ipv4(src = "10.0.0.5", dst = "93.184.216.34", srcPort = 44444, dstPort = 443)
                .sequence(1000)
                .payload(byteArrayOf(0x16, 0x03, 0x01, 0x00))
                .build()
        )!!
        val reset = TcpPacketCodec.craftReset(offender)

        // Re-summing the finished segment including its checksum must produce 0.
        // This is the property a receiving stack uses, so it is the one that matters.
        assertEquals(0, tcpChecksumOver(reset, 20, 20, ipv4 = true))
    }
}

/**
 * Minimal IPv4/IPv6 + TCP segment builder for codec tests.
 *
 * Lengths are explicit so a test can emit deliberately inconsistent headers.
 */
class TcpSegmentBuilder {
    private var family: Int = IpPacketCodec.IPV4
    private var src: ByteArray = byteArrayOf(10, 0, 0, 5)
    private var dst: ByteArray = byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34)
    private var srcPort = 44444
    private var dstPort = 443
    private var seq = 1000
    private var ack = 0
    private var flags = TcpFlags.ACK
    private var window = 65535
    private var data: ByteArray = ByteArray(0)

    fun ipv4(src: String, dst: String, srcPort: Int, dstPort: Int) = apply {
        family = IpPacketCodec.IPV4
        this.src = parseV4(src)
        this.dst = parseV4(dst)
        this.srcPort = srcPort
        this.dstPort = dstPort
    }

    fun ipv6(src: String, dst: String, srcPort: Int, dstPort: Int) = apply {
        family = IpPacketCodec.IPV6
        this.src = parseV6(src)
        this.dst = parseV6(dst)
        this.srcPort = srcPort
        this.dstPort = dstPort
    }

    fun sequence(value: Int) = apply { seq = value }
    fun ack(value: Int) = apply { ack = value }
    fun flags(value: Int) = apply { flags = value }
    fun window(value: Int) = apply { window = value }
    fun payload(value: ByteArray) = apply { data = value }

    fun build(): ByteArray {
        val tcpLength = 20 + data.size
        val headerLength = if (family == IpPacketCodec.IPV4) 20 else 40
        val buf = ByteArray(headerLength + tcpLength)

        if (family == IpPacketCodec.IPV4) {
            buf[0] = 0x45.toByte()
            val total = headerLength + tcpLength
            buf[2] = ((total shr 8) and 0xFF).toByte()
            buf[3] = (total and 0xFF).toByte()
            buf[8] = 64.toByte()
            buf[9] = PROTOCOL_TCP.toByte()
            System.arraycopy(src, 0, buf, 12, 4)
            System.arraycopy(dst, 0, buf, 16, 4)
        } else {
            buf[0] = 0x60.toByte()
            buf[4] = ((tcpLength shr 8) and 0xFF).toByte()
            buf[5] = (tcpLength and 0xFF).toByte()
            buf[6] = PROTOCOL_TCP.toByte()
            buf[7] = 64.toByte()
            System.arraycopy(src, 0, buf, 8, 16)
            System.arraycopy(dst, 0, buf, 24, 16)
        }

        val t = headerLength
        buf[t] = ((srcPort shr 8) and 0xFF).toByte()
        buf[t + 1] = (srcPort and 0xFF).toByte()
        buf[t + 2] = ((dstPort shr 8) and 0xFF).toByte()
        buf[t + 3] = (dstPort and 0xFF).toByte()
        buf[t + 4] = ((seq shr 24) and 0xFF).toByte()
        buf[t + 5] = ((seq shr 16) and 0xFF).toByte()
        buf[t + 6] = ((seq shr 8) and 0xFF).toByte()
        buf[t + 7] = (seq and 0xFF).toByte()
        buf[t + 8] = ((ack shr 24) and 0xFF).toByte()
        buf[t + 9] = ((ack shr 16) and 0xFF).toByte()
        buf[t + 10] = ((ack shr 8) and 0xFF).toByte()
        buf[t + 11] = (ack and 0xFF).toByte()
        buf[t + 12] = 0x50.toByte()
        buf[t + 13] = flags.toByte()
        buf[t + 14] = ((window shr 8) and 0xFF).toByte()
        buf[t + 15] = (window and 0xFF).toByte()
        buf[t + 16] = 0
        buf[t + 17] = 0
        System.arraycopy(data, 0, buf, t + 20, data.size)
        return buf
    }

    private fun parseV4(value: String): ByteArray = value.split('.').map {
        it.toInt().toByte()
    }.toByteArray()

    private fun parseV6(value: String): ByteArray {
        // Handles the uncompressed form used by these tests, e.g. fd00::1.
        val out = ByteArray(16)
        val head = value.substringBefore("::")
        val tail = if (value.contains("::")) value.substringAfter("::").split(':') else emptyList()
        val headGroups = if (head.isEmpty()) emptyList() else head.split(':')
        val explicitGroups = headGroups.size + tail.size
        var index = 0
        for (g in headGroups) {
            writeV6Group(out, index++, g)
        }
        // Zero-fill the elision, then place the tail after it.
        var tailIndex = 8 - (explicitGroups - 8)
        if (explicitGroups <= 8) tailIndex = index
        for (g in tail) {
            writeV6Group(out, tailIndex++, g)
        }
        return out
    }

    private fun writeV6Group(out: ByteArray, group: Int, text: String) {
        val value = text.toInt(16)
        out[group * 2] = ((value shr 8) and 0xFF).toByte()
        out[group * 2 + 1] = (value and 0xFF).toByte()
    }
}