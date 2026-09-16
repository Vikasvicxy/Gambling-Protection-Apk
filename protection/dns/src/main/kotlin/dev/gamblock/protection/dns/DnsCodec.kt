package dev.gamblock.protection.dns

/**
 * Pure Kotlin DNS wire-format helpers. No Android dependencies so the codec is
 * unit-testable on the host JVM and safe to fuzz.
 */
object DnsConstants {
    // Resource record types
    const val TYPE_A = 1
    const val TYPE_NS = 2
    const val TYPE_CNAME = 5
    const val TYPE_SOA = 6
    const val TYPE_PTR = 12
    const val TYPE_MX = 15
    const val TYPE_TXT = 16
    const val TYPE_AAAA = 28
    const val TYPE_SRV = 33
    const val TYPE_OPT = 41
    // Query classes
    const val CLASS_IN = 1
    const val CLASS_CH = 3
    // Header flags
    const val FLAG_QR = 0x8000
    const val FLAG_AA = 0x0400
    const val FLAG_TC = 0x0200
    const val FLAG_RD = 0x0100
    const val FLAG_RA = 0x0080
    const val MASK_RCODE = 0x000F
    // Response codes
    const val RCODE_OK = 0
    const val RCODE_FORMAT_ERROR = 1
    const val RCODE_SERVER_FAILURE = 2
    const val RCODE_NAME_ERROR = 3 // NXDOMAIN
    const val RCODE_NOT_IMPLEMENTED = 4
    const val RCODE_REFUSED = 5
}

/** Decoded DNS question. */
data class DnsQuestion(
    val name: String,
    val type: Int,
    val recordClass: Int,
)

/** A parsed IP header + UDP payload carried inside a tun packet. */
data class UdpPacket(
    val family: Int, // 4 or 6
    val srcAddress: ByteArray,
    val dstAddress: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    val payload: ByteArray,
)

object DnsParser {

    /**
     * Reads a (possibly compressed) DNS name starting at [offset].
     * Returns the name without the trailing dot, and the offset just past the
     * encoded name (the position after the name's own bytes, not the jump target).
     */
    fun readName(data: ByteArray, offset: Int): Pair<String, Int> {
        var pos = offset
        val sb = StringBuilder()
        var jumped = false
        var continuePos = offset
        var iterations = 0

        while (iterations < 128) {
            iterations++
            val len = data[pos].toInt() and 0xFF
            when {
                len == 0 -> {
                    pos++
                    if (!jumped) continuePos = pos
                    return sb.toString().removeSuffix(".") to continuePos
                }
                len and 0xC0 == 0xC0 -> {
                    if (pos + 1 >= data.size) throw IllegalArgumentException("truncated compression pointer")
                    val pointer = ((len and 0x3F) shl 8) or (data[pos + 1].toInt() and 0xFF)
                    if (!jumped) {
                        continuePos = pos + 2
                        jumped = true
                    }
                    pos = pointer
                }
                len and 0xC0 != 0 -> throw IllegalArgumentException("unsupported label type")
                else -> {
                    if (pos + 1 + len > data.size) throw IllegalArgumentException("truncated label")
                    sb.append(String(data, pos + 1, len, Charsets.ISO_8859_1))
                    sb.append('.')
                    pos += 1 + len
                }
            }
        }
        throw IllegalArgumentException("name loop detected")
    }

    fun parseQuestion(data: ByteArray, packetOffset: Int = 12): DnsQuestion {
        require(data.size >= packetOffset + 5) { "packet too short for question" }
        val (name, next) = readName(data, packetOffset)
        require(next + 4 <= data.size) { "packet too short for qtype/qclass" }
        val type = ((data[next].toInt() and 0xFF) shl 8) or (data[next + 1].toInt() and 0xFF)
        val clazz = ((data[next + 2].toInt() and 0xFF) shl 8) or (data[next + 3].toInt() and 0xFF)
        return DnsQuestion(name, type, clazz)
    }

    fun headerId(data: ByteArray): Int =
        ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)

    fun questionCount(data: ByteArray): Int =
        ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
}

object DnsResponseFactory {

    /**
     * Builds a minimal successful (or empty) DNS response. Used for syntactically
     * invalid queries: REFUSED.
     */
    fun refused(query: ByteArray): ByteArray {
        val id = DnsParser.headerId(query)
        val flags = DnsConstants.FLAG_QR or DnsConstants.RCODE_REFUSED
        return header(id, flags, 0, 0, 0, 0)
    }

    /**
     * Builds an NXDOMAIN response that echoes the original question.
     * [blockMessageEnabled] is kept for future block-page experimentation.
     */
    fun blocked(query: ByteArray, blockMessageEnabled: Boolean = false): ByteArray {
        val id = DnsParser.headerId(query)
        val qd = DnsParser.questionCount(query).coerceAtMost(1)
        val base = header(id, DnsConstants.FLAG_QR or DnsConstants.RCODE_NAME_ERROR, qd, 0, 0, 0)
        if (qd == 0) return base

        // Append the original question section verbatim (safe because pointers in
        // queries are relative to the whole message which we preserve).
        val questionLength = questionBytes(query) ?: return base
        val question = query.copyOfRange(12, 12 + questionLength)
        return base + question
    }

    private fun questionBytes(query: ByteArray): Int? {
        var pos = 12
        var iterations = 0
        while (iterations < 128) {
            iterations++
            if (pos >= query.size) return null
            val len = query[pos].toInt() and 0xFF
            when {
                len == 0 -> {
                    pos++
                    break
                }
                len and 0xC0 == 0xC0 -> throw IllegalArgumentException("compressed qname in query")
                else -> pos += 1 + len
            }
        }
        if (pos + 4 > query.size) return null
        return pos - 12 + 4
    }

    /** 12-byte DNS header. */
    fun header(id: Int, flags: Int, qd: Int, an: Int, ns: Int, ar: Int): ByteArray =
        byteArrayOf(
            ((id shr 8) and 0xFF).toByte(),
            (id and 0xFF).toByte(),
            ((flags shr 8) and 0xFF).toByte(),
            (flags and 0xFF).toByte(),
            ((qd shr 8) and 0xFF).toByte(),
            (qd and 0xFF).toByte(),
            ((an shr 8) and 0xFF).toByte(),
            (an and 0xFF).toByte(),
            ((ns shr 8) and 0xFF).toByte(),
            (ns and 0xFF).toByte(),
            ((ar shr 8) and 0xFF).toByte(),
            (ar and 0xFF).toByte(),
        )

    /** Encodes a hostname as a DNS wire name (no trailing zero). */
    fun encodeName(host: String): ByteArray {
        val labels = host.split('.')
        val out = ArrayList<Byte>(host.length + 4)
        for (label in labels) {
            if (label.isEmpty()) continue
            val bytes = label.toByteArray(Charsets.UTF_8)
            out.add(bytes.size.toByte())
            bytes.forEach { out.add(it) }
        }
        return out.toByteArray()
    }
}

/** One's complement Internet checksum used by IP and UDP for IPv4/IPv6. */
object InternetChecksum {
    fun compute(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var end = offset + length
        var i = offset
        while (i + 1 < end) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while (sum ushr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv() and 0xFFFF
    }
}