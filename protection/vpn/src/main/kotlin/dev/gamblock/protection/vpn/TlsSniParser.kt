package dev.gamblock.protection.vpn

/**
 * Minimal, allocation-light TLS ClientHello SNI reader.
 *
 * Scope and limits
 * ----------------
 * This parses the plaintext ClientHello a client sends as the first flight of a TLS
 * handshake. That is enough to read the Server Name Indication (RFC 6066) and drop
 * connections to blocked domains before any TLS session exists, which is what defeats
 * Chrome's own DoH: the browser's queries never reach our resolver, but its SNI still
 * passes through in the clear unless the client has enabled Encrypted ClientHello.
 *
 * It deliberately does NOT implement TLS, terminate handshakes, or hold any keys. It
 * reads one message and returns a hostname or nothing.
 *
 * Why this is currently inert
 * ---------------------------
 * Shield runs a DNS-only tunnel (198.18.0.1/32), so no TCP packet reaches this code.
 * Real interception additionally requires a full-tunnel route plus a userspace TCP/IP
 * stack to forward allowed traffic. See docs/ARCHITECTURE.md. The parser is built and
 * tested now so that work has a tested foundation rather than starting from scratch.
 *
 * Every length is validated against the actual remaining bytes before it is trusted.
 * A malformed or hostile ClientHello returns [SniResult.Unreadable], never an
 * exception and never a partially parsed hostname.
 */
object TlsSniParser {

    private const val CONTENT_TYPE_HANDSHAKE = 0x16
    private const val HANDSHAKE_TYPE_CLIENT_HELLO = 0x01
    private const val EXTENSION_SERVER_NAME = 0x0000
    private const val SNI_HOST_NAME = 0x00

    /** A record layer maximum: 16 KiB plus the 5 byte record header. */
    private const val MAX_RECORD_BYTES = 16 * 1024 + 5

    sealed interface SniResult {
        data class Present(val hostname: String) : SniResult

        /** No SNI extension: an IP-literal connection, or ECH, or a malformed message. */
        data object Absent : SniResult

        /** Bytes were not a ClientHello we can safely parse. Never throws. */
        data class Unreadable(val reason: String) : SniResult
    }

    /**
     * Reads the SNI hostname from a raw TCP payload beginning at the TLS record header.
     *
     * @param packet bytes starting at the TLS record layer (content type byte first).
     * @param length number of valid bytes in [packet].
     */
    fun parse(packet: ByteArray, length: Int = packet.size): SniResult {
        if (length <= 0 || length > packet.size) {
            return SniResult.Unreadable("length $length outside buffer")
        }
        if (length < 5) return SniResult.Unreadable("shorter than a TLS record header")

        // --- record layer ---
        if (packet[0].toInt() and 0xFF != CONTENT_TYPE_HANDSHAKE) {
            return SniResult.Unreadable("not a TLS handshake record")
        }
        val major = packet[1].toInt() and 0xFF
        val minor = packet[2].toInt() and 0xFF
        // TLS 1.0-1.3 use 0x03 for the major version. SSLv2 has a different layout and
        // is not accepted here.
        if (major != 0x03) return SniResult.Unreadable("unexpected TLS version 0x${major}%02x.${minor}")
        val recordLength = ((packet[3].toInt() and 0xFF) shl 8) or (packet[4].toInt() and 0xFF)
        if (recordLength == 0) return SniResult.Unreadable("empty handshake record")
        if (recordLength > MAX_RECORD_BYTES) return SniResult.Unreadable("record length $recordLength too large")

        val bodyStart = 5
        val bodyEnd = bodyStart + recordLength
        if (bodyEnd > length) return SniResult.Unreadable("record claims $recordLength bytes, only ${length - bodyStart} present")
        val body = packet
        var p = bodyStart

        // --- handshake header: msg_type(1) + length(3) ---
        if (p + 4 > bodyEnd) return SniResult.Unreadable("truncated handshake header")
        if (body[p].toInt() and 0xFF != HANDSHAKE_TYPE_CLIENT_HELLO) {
            return SniResult.Unreadable("not a ClientHello")
        }
        val handshakeLength = readUint24(body, p + 1)
        p += 4
        if (p + handshakeLength > bodyEnd) {
            return SniResult.Unreadable("ClientHello claims $handshakeLength bytes past the record")
        }
        val helloEnd = p + handshakeLength

        // --- ClientHello body ---
        if (p + 2 > helloEnd) return SniResult.Unreadable("truncated ClientHello version")
        p += 2 // legacy_version
        if (p + 32 > helloEnd) return SniResult.Unreadable("truncated random")
        p += 32 // random
        if (p >= helloEnd) return SniResult.Absent // no session id
        val sessionIdLength = body[p].toInt() and 0xFF
        p++
        if (p + sessionIdLength > helloEnd) return SniResult.Unreadable("session id overruns ClientHello")
        p += sessionIdLength

        // cipher suites
        if (p + 2 > helloEnd) return SniResult.Unreadable("truncated cipher suite length")
        val cipherLength = ((body[p].toInt() and 0xFF) shl 8) or (body[p + 1].toInt() and 0xFF)
        p += 2
        if (cipherLength % 2 != 0) return SniResult.Unreadable("odd cipher suite length $cipherLength")
        if (p + cipherLength > helloEnd) return SniResult.Unreadable("cipher suites overrun ClientHello")
        p += cipherLength

        // compression methods
        if (p + 1 > helloEnd) return SniResult.Absent
        val compressionLength = body[p].toInt() and 0xFF
        p++
        if (p + compressionLength > helloEnd) return SniResult.Unreadable("compression methods overrun ClientHello")
        p += compressionLength

        // A ClientHello without extensions is legal (TLS 1.2 and earlier).
        if (p >= helloEnd) return SniResult.Absent
        if (p + 2 > helloEnd) return SniResult.Unreadable("truncated extensions length")
        val extensionsLength = ((body[p].toInt() and 0xFF) shl 8) or (body[p + 1].toInt() and 0xFF)
        p += 2
        if (p + extensionsLength > helloEnd) return SniResult.Unreadable("extensions overrun ClientHello")

        return scanExtensions(body, p, p + extensionsLength)
    }

    /** Walks the extension list looking for type 0x0000, then reads the host_name entry. */
    private fun scanExtensions(body: ByteArray, start: Int, end: Int): SniResult {
        var p = start
        while (p + 4 <= end) {
            val type = ((body[p].toInt() and 0xFF) shl 8) or (body[p + 1].toInt() and 0xFF)
            val extensionLength = ((body[p + 2].toInt() and 0xFF) shl 8) or (body[p + 3].toInt() and 0xFF)
            p += 4
            if (p + extensionLength > end) return SniResult.Unreadable("extension 0x$type overruns the list")
            if (type == EXTENSION_SERVER_NAME) {
                return parseServerNameExtension(body, p, p + extensionLength)
            }
            p += extensionLength
        }
        return SniResult.Absent
    }

    /** Parses the server_name_list: a 2 byte list length, then name_type/length/name entries. */
    private fun parseServerNameExtension(body: ByteArray, start: Int, end: Int): SniResult {
        if (start + 2 > end) return SniResult.Unreadable("truncated server_name list length")
        val listLength = ((body[start].toInt() and 0xFF) shl 8) or (body[start + 1].toInt() and 0xFF)
        var p = start + 2
        val listEnd = p + listLength
        if (listEnd > end) return SniResult.Unreadable("server_name list overruns the extension")

        while (p + 3 <= listEnd) {
            val nameType = body[p].toInt() and 0xFF
            val nameLength = ((body[p + 1].toInt() and 0xFF) shl 8) or (body[p + 2].toInt() and 0xFF)
            p += 3
            if (p + nameLength > listEnd) return SniResult.Unreadable("server name overruns the list")
            if (nameType == SNI_HOST_NAME) {
                if (nameLength == 0) return SniResult.Unreadable("empty host_name")
                return decodeHostname(body, p, nameLength)
            }
            p += nameLength
        }
        return SniResult.Absent
    }

    /**
     * Decodes the raw host_name bytes. Anything that is not a plausible ASCII hostname is
     * rejected rather than passed upward: a hostile ClientHello must not be able to inject
     * a NUL, a wildcard or an over-long label into the blocking decision.
     */
    private fun decodeHostname(body: ByteArray, start: Int, length: Int): SniResult {
        // RFC 1035 caps a hostname at 253 characters.
        if (length > 253) return SniResult.Unreadable("host_name of $length bytes exceeds 253")
        val out = StringBuilder(length)
        for (i in start until start + length) {
            val c = body[i].toInt() and 0xFF
            if (c < 0x21 || c > 0x7E) return SniResult.Unreadable("non-printable byte in host_name")
            if (c == '.'.code.toInt() && (i == start || i == start + length - 1)) {
                return SniResult.Unreadable("host_name has a leading or trailing dot")
            }
            out.append(c.toChar())
        }
        val hostname = out.toString().lowercase()
        if (!isPlausibleHostname(hostname)) return SniResult.Unreadable("host_name is not a plausible hostname")
        return SniResult.Present(hostname)
    }

    private fun isPlausibleHostname(hostname: String): Boolean {
        if (hostname.isEmpty() || hostname.length > 253) return false
        for (label in hostname.split('.')) {
            if (label.isEmpty() || label.length > 63) return false
            for (c in label) {
                val ok = (c in 'a'..'z') || (c in '0'..'9') || c == '-' || c == '_'
                if (!ok) return false
            }
        }
        return true
    }

    private fun readUint24(body: ByteArray, offset: Int): Int =
        ((body[offset].toInt() and 0xFF) shl 16) or
            ((body[offset + 1].toInt() and 0xFF) shl 8) or
            (body[offset + 2].toInt() and 0xFF)
}