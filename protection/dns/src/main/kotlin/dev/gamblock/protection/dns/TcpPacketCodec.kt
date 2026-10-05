package dev.gamblock.protection.dns

/**
 * IPv4/IPv6 + TCP header parsing and reset synthesis for the SNI interceptor.
 *
 * Scope and limits
 * ----------------
 * This decodes enough of a TCP segment to read the payload of a client's first
 * flight (the TLS ClientHello) and to synthesise a RST when that flight names a
 * blocked host. It is deliberately NOT a TCP stack: there is no handshake, no
 * sequence-space ownership, no window management and no retransmission.
 *
 * Every offset is validated against the caller supplied [length] before it is
 * trusted, so a truncated or hostile segment yields null instead of an
 * exception or an out-of-bounds read.
 */
const val PROTOCOL_TCP = 6

/** TCP control bits, in the byte 13 of the TCP header. */
object TcpFlags {
    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10
    const val URG = 0x20
}

/**
 * A decoded IPv4/IPv6 + TCP segment.
 *
 * [sequenceNumber] and [ackNumber] are the raw 32-bit TCP values kept in host
 * int form; arithmetic that must wrap is done with [wrapSequence].
 */
data class TcpPacket(
    val family: Int,
    val srcAddress: ByteArray,
    val dstAddress: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    val sequenceNumber: Int,
    val ackNumber: Int,
    val flags: Int,
    val window: Int,
    val payload: ByteArray,
) {
    val isRst: Boolean get() = flags and TcpFlags.RST != 0
    val isSyn: Boolean get() = flags and TcpFlags.SYN != 0

    /**
     * True when this segment carries no bytes. A bare ACK or a FIN has no
     * payload, so there is no ClientHello to read.
     */
    val hasPayload: Boolean get() = payload.isNotEmpty()

    /** 32-bit TCP sequence arithmetic, kept unsigned so it wraps like the wire. */
    companion object {
        /** Adds [delta] in 32-bit TCP sequence space, wrapping like the wire. */
        fun advance(sequence: Int, delta: Int): Int = sequence + delta

        /**
         * Signed distance from [from] to [to] in TCP sequence space.
         *
         * Int subtraction is used deliberately: it is exactly the 32-bit modular
         * arithmetic the wire uses, so 0xFFFFFFFE -> 1 yields +3 across the wrap
         * instead of the -4294967293 a widened subtraction would produce.
         */
        fun sequenceDistance(from: Int, to: Int): Long = (to - from).toLong()
    }
}

object TcpPacketCodec {

    /** Parses a raw tun packet (IP header included) into a [TcpPacket], or null. */
    fun parse(packet: ByteArray): TcpPacket? = parse(packet, packet.size)

    /**
     * Parses a raw tun packet honoring [length] as the effective packet size, so a
     * shared reusable buffer can be inspected in place without a per-packet copy.
     */
    fun parse(packet: ByteArray, length: Int): TcpPacket? {
        val limit = length.coerceIn(0, packet.size)
        if (limit < 20) return null
        return when ((packet[0].toInt() ushr 4) and 0x0F) {
            IpPacketCodec.IPV4 -> parseIpv4(packet, limit)
            IpPacketCodec.IPV6 -> parseIpv6(packet, limit)
            else -> null
        }
    }

    private fun parseIpv4(packet: ByteArray, limit: Int): TcpPacket? {
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < 20 || limit < ihl) return null
        if ((packet[9].toInt() and 0xFF) != PROTOCOL_TCP) return null
        // Fragmented segments cannot be read for SNI without reassembly; offsets
        // are non-zero for any fragment beyond the first.
        val fragOffset = ((packet[6].toInt() and 0x1F) shl 8) or (packet[7].toInt() and 0xFF)
        if (fragOffset != 0) return null
        val src = packet.copyOfRange(12, 16)
        val dst = packet.copyOfRange(16, 20)
        return parseTcpPayload(packet, limit, ihl, src, dst)
    }

    private fun parseIpv6(packet: ByteArray, limit: Int): TcpPacket? {
        if (limit < 40) return null
        var nextHeader = packet[6].toInt() and 0xFF
        var offset = 40
        var iterations = 0
        while (nextHeader != PROTOCOL_TCP && nextHeader != 59 && iterations < 8) {
            iterations++
            if (offset + 8 > limit) return null
            nextHeader = packet[offset].toInt() and 0xFF
            offset += 8 + ((packet[offset + 1].toInt() and 0xFF) * 8)
        }
        if (nextHeader != PROTOCOL_TCP || offset + 20 > limit) return null
        val src = packet.copyOfRange(8, 24)
        val dst = packet.copyOfRange(24, 40)
        return parseTcpPayload(packet, limit, offset, src, dst)
    }

    private fun parseTcpPayload(
        packet: ByteArray,
        limit: Int,
        tcpOffset: Int,
        srcIp: ByteArray,
        dstIp: ByteArray,
    ): TcpPacket? {
        if (tcpOffset + 20 > limit) return null
        val srcPort = u16(packet, tcpOffset)
        val dstPort = u16(packet, tcpOffset + 2)
        val seq = u32(packet, tcpOffset + 4)
        val ack = u32(packet, tcpOffset + 8)
        val dataOffset = ((packet[tcpOffset + 12].toInt() and 0xF0) ushr 4) * 4
        // dataOffset < 20 is malformed; a value that overruns the packet would let
        // us read attacker-controlled bytes as payload.
        if (dataOffset < 20 || tcpOffset + dataOffset > limit) return null
        val flags = packet[tcpOffset + 13].toInt() and 0x3F
        val window = u16(packet, tcpOffset + 14)
        val payloadLength = limit - (tcpOffset + dataOffset)
        val payload = if (payloadLength > 0) {
            packet.copyOfRange(tcpOffset + dataOffset, limit)
        } else {
            ByteArray(0)
        }
        return TcpPacket(
            family = if (srcIp.size == 4) IpPacketCodec.IPV4 else IpPacketCodec.IPV6,
            srcAddress = srcIp,
            dstAddress = dstIp,
            srcPort = srcPort,
            dstPort = dstPort,
            sequenceNumber = seq,
            ackNumber = ack,
            flags = flags,
            window = window,
            payload = payload,
        )
    }

    /**
     * Synthesises the TCP RST that refuses [offender], an outbound segment we
     * intercepted from the tunnel client.
     *
     * The reset is mirrored: it appears to come from the server the client was
     * dialling, so it is indistinguishable on the wire from a real server-side
     * teardown. `seq` echoes the client's ACK and `ack` covers the bytes it just
     * sent, which is the form RFC 793 requires in response to an established
     * segment, so the client's stack discards it rather than replying to a reset.
     */
    fun craftReset(offender: TcpPacket, mtuLimit: Int = 1500): ByteArray {
        require(offender.srcAddress.size == offender.dstAddress.size) { "address family mismatch" }
        val payloadEnd = TcpPacket.advance(offender.sequenceNumber, offender.payload.size)
        return when (offender.family) {
            IpPacketCodec.IPV4 -> craftIpv4Reset(offender, payloadEnd, mtuLimit)
            IpPacketCodec.IPV6 -> craftIpv6Reset(offender, payloadEnd, mtuLimit)
            else -> throw IllegalArgumentException("unsupported family ${offender.family}")
        }
    }

    private fun craftIpv4Reset(offender: TcpPacket, ackNumber: Int, mtu: Int): ByteArray {
        val tcpLength = 20
        val totalLength = 20 + tcpLength
        require(totalLength <= mtu) { "reset exceeds mtu $mtu" }
        val buf = ByteArray(totalLength)
        buf[0] = (0x40 or 0x05).toByte() // IPv4, IHL=5
        buf[2] = ((totalLength shr 8) and 0xFF).toByte()
        buf[3] = (totalLength and 0xFF).toByte()
        buf[8] = 64.toByte() // TTL
        buf[9] = PROTOCOL_TCP.toByte()
        System.arraycopy(offender.dstAddress, 0, buf, 12, 4) // appear to come from the server
        System.arraycopy(offender.srcAddress, 0, buf, 16, 4) // back to the client
        writeU16(buf, 20, offender.dstPort)
        writeU16(buf, 22, offender.srcPort)
        writeU32(buf, 24, offender.ackNumber)
        writeU32(buf, 28, ackNumber)
        buf[32] = 0x50.toByte() // data offset 5 words, no reserved bits
        buf[33] = (TcpFlags.RST or TcpFlags.ACK).toByte()
        writeU16(buf, 34, 0) // zero window: nothing may follow the reset
        buf[36] = 0; buf[37] = 0 // checksum placeholder

        val checksum = tcpChecksum(
            src = offender.dstAddress,
            dst = offender.srcAddress,
            tcp = buf,
            tcpOffset = 20,
            tcpLength = tcpLength,
        )
        writeU16(buf, 36, checksum)
        return buf
    }

    private fun craftIpv6Reset(offender: TcpPacket, ackNumber: Int, mtu: Int): ByteArray {
        val tcpLength = 20
        val totalLength = 40 + tcpLength
        require(totalLength <= mtu) { "reset exceeds mtu $mtu" }
        val buf = ByteArray(totalLength)
        buf[0] = 0x60.toByte() // version 6
        buf[4] = 0; buf[5] = tcpLength.toByte()
        buf[6] = PROTOCOL_TCP.toByte()
        buf[7] = 64.toByte() // hop limit
        System.arraycopy(offender.dstAddress, 0, buf, 8, 16)
        System.arraycopy(offender.srcAddress, 0, buf, 24, 16)

        val tcpOffset = 40
        writeU16(buf, tcpOffset, offender.dstPort)
        writeU16(buf, tcpOffset + 2, offender.srcPort)
        writeU32(buf, tcpOffset + 4, offender.ackNumber)
        writeU32(buf, tcpOffset + 8, ackNumber)
        buf[tcpOffset + 12] = 0x50.toByte()
        buf[tcpOffset + 13] = (TcpFlags.RST or TcpFlags.ACK).toByte()
        writeU16(buf, tcpOffset + 14, 0)
        buf[tcpOffset + 16] = 0; buf[tcpOffset + 17] = 0

        val checksum = tcpChecksum(
            src = offender.dstAddress,
            dst = offender.srcAddress,
            tcp = buf,
            tcpOffset = tcpOffset,
            tcpLength = tcpLength,
        )
        writeU16(buf, tcpOffset + 16, checksum)
        return buf
    }

    /**
     * TCP checksum over the IPv4/IPv6 pseudo header plus the segment. Unlike UDP,
     * a TCP checksum is mandatory on both families.
     */
    private fun tcpChecksum(
        src: ByteArray,
        dst: ByteArray,
        tcp: ByteArray,
        tcpOffset: Int,
        tcpLength: Int,
    ): Int {
        var sum = 0L
        fun addPair(b0: Byte, b1: Byte) {
            sum += ((b0.toInt() and 0xFF) shl 8) or (b1.toInt() and 0xFF)
        }
        var i = 0
        while (i < src.size) {
            val j = i + 1
            addPair(
                src[i],
                if (j < src.size) src[j] else 0.toByte(),
            )
            i += 2
        }
        var j = 0
        while (j < dst.size) {
            val k = j + 1
            addPair(
                dst[j],
                if (k < dst.size) dst[k] else 0.toByte(),
            )
            j += 2
        }
        // Pseudo header trailer: upper-layer length, zero, protocol.
        sum += tcpLength.toLong()
        addPair(0, PROTOCOL_TCP.toByte())
        var k = tcpOffset
        val end = tcpOffset + tcpLength
        while (k + 1 < end) {
            addPair(tcp[k], tcp[k + 1]); k += 2
        }
        if (k < end) addPair(tcp[k], 0)
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun u16(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or
            ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or
            (b[o + 3].toInt() and 0xFF)

    private fun writeU16(b: ByteArray, o: Int, v: Int) {
        b[o] = ((v shr 8) and 0xFF).toByte()
        b[o + 1] = (v and 0xFF).toByte()
    }

    private fun writeU32(b: ByteArray, o: Int, v: Int) {
        b[o] = ((v shr 24) and 0xFF).toByte()
        b[o + 1] = ((v shr 16) and 0xFF).toByte()
        b[o + 2] = ((v shr 8) and 0xFF).toByte()
        b[o + 3] = (v and 0xFF).toByte()
    }
}