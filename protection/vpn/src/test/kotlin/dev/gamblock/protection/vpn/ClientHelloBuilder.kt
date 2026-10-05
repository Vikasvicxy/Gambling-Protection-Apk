package dev.gamblock.protection.vpn

import java.io.ByteArrayOutputStream

/**
 * Builds real TLS ClientHello byte sequences for parser tests.
 *
 * Everything is assembled from explicit lengths so a test can produce deliberately
 * inconsistent structures (a record that claims more bytes than exist, an extension
 * that overruns the list) rather than trusting a real capture.
 */
object ClientHelloBuilder {

    fun builder(): Builder = Builder()

    /**
     * Byte offset of the session_id length byte in a built packet.
     * record(5) + handshake header(4) + legacy_version(2) + random(32) = 43.
     */
    const val SESSION_ID_LENGTH_OFFSET = 43

    /** Offset of the cipher_suites length field for a packet with this session id length. */
    fun cipherLengthOffset(sessionIdLength: Int): Int = 44 + sessionIdLength

    /**
     * Offset of the first byte of [hostname] inside [bytes]. Lets a test corrupt one
     * exact byte while leaving every length field self-consistent, so the assertion
     * exercises the sanitiser rather than a bounds check.
     */
    fun hostnameOffset(bytes: ByteArray, hostname: String): Int {
        val needle = hostname.toByteArray(Charsets.US_ASCII)
        for (i in 0..bytes.size - needle.size) {
            var match = true
            for (j in needle.indices) {
                if (bytes[i + j] != needle[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        error("hostname '$hostname' not found in the built packet")
    }

    class Builder {
        private var sessionId = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        private var cipherSuites = intArrayOf(0x1301, 0x1302, 0xC02B, 0xC02F)
        private val compression = byteArrayOf(0x00)
        private val extensions = ArrayList<ByteArray>()

        fun sessionId(id: ByteArray) = apply { sessionId = id }
        fun sessionIdForTest(): ByteArray = sessionId
        fun cipherSuites(suites: IntArray) = apply { cipherSuites = suites }
        fun noExtensions() = apply { extensions.clear() }

        fun sessionIdLength(): Int = sessionId.size

        /** Adds a raw extension with explicit type and length (length may be wrong on purpose). */
        fun rawExtension(type: Int, data: ByteArray) = apply {
            extensions.add(concat(u16(type), u16(data.size), data))
        }

        /** Adds a well-formed server_name extension for [hostname]. */
        fun serverName(hostname: String): Builder = apply {
            val name = hostname.toByteArray(Charsets.US_ASCII)
            val entry = concat(byteArrayOf(SNI_HOST_NAME.toByte()), u16(name.size), name)
            val list = concat(u16(entry.size), entry)
            rawExtension(EXT_SERVER_NAME, list)
        }

        /** Builds a server_name extension whose entry name bytes come from a raw array. */
        fun rawServerNameBytes(nameType: Int, name: ByteArray, declaredLength: Int = name.size): Builder = apply {
            val entry = concat(byteArrayOf(nameType.toByte()), u16(declaredLength), name)
            val list = concat(u16(entry.size), entry)
            rawExtension(EXT_SERVER_NAME, list)
        }

        /** Adds a server_name extension carrying an entry with a deliberately wrong length. */
        fun rawServerName(nameType: Int, name: ByteArray, declaredLength: Int): Builder = apply {
            val entry = concat(byteArrayOf(nameType.toByte()), u16(declaredLength), name)
            val list = concat(u16(entry.size), entry)
            rawExtension(EXT_SERVER_NAME, list)
        }

        /** Adds a server_name extension whose list length overruns the extension body. */
        fun serverNameWithBadListLength(hostname: String, declaredListLength: Int): Builder = apply {
            val name = hostname.toByteArray(Charsets.US_ASCII)
            val entry = concat(byteArrayOf(SNI_HOST_NAME.toByte()), u16(name.size), name)
            val list = concat(u16(declaredListLength), entry)
            rawExtension(EXT_SERVER_NAME, list)
        }

        /** Adds an extension whose declared length exceeds the bytes actually written. */
        fun extensionWithInflatedLength(type: Int, data: ByteArray): Builder = apply {
            extensions.add(concat(u16(type), u16(data.size + 32), data))
        }

        fun build(): ByteArray {
            val hello = ByteArrayOutputStream()
            hello.write(u16(0x0303)) // legacy_version TLS 1.2
            repeat(32) { hello.write(byteArrayOf(0x5A)) } // deterministic random
            hello.write(byteArrayOf(sessionId.size.toByte()))
            hello.write(sessionId)

            val cipherBytes = ByteArrayOutputStream()
            cipherSuites.forEach { cipherBytes.write(u16(it)) }
            val cb = cipherBytes.toByteArray()
            hello.write(u16(cb.size))
            hello.write(cb)

            hello.write(byteArrayOf(compression.size.toByte()))
            hello.write(compression)

            val ext = ByteArrayOutputStream()
            extensions.forEach { ext.write(it) }
            val eb = ext.toByteArray()
            hello.write(u16(eb.size))
            hello.write(eb)

            val helloBody = hello.toByteArray()
            val handshake = concat(byteArrayOf(0x01), uint24(helloBody.size), helloBody)
            return record(handshake)
        }

        /** Wraps handshake bytes in a TLS record with a correct length. */
        fun record(handshake: ByteArray, contentType: Int = 0x16, major: Int = 0x03, minor: Int = 0x01): ByteArray =
            concat(byteArrayOf(contentType.toByte(), major.toByte(), minor.toByte()), u16(handshake.size), handshake)

        /** Record header claims [claimedLength] while carrying [handshake] verbatim. */
        fun recordWithLength(handshake: ByteArray, claimedLength: Int): ByteArray =
            concat(byteArrayOf(0x16, 0x03, 0x01), u16(claimedLength), handshake)

        private fun u16(value: Int): ByteArray = byteArrayOf(
            ((value shr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte(),
        )

        private fun uint24(value: Int): ByteArray = byteArrayOf(
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte(),
        )

        private fun concat(vararg parts: ByteArray): ByteArray =
            parts.fold(ByteArray(0)) { acc, part -> acc + part }
    }

    const val EXT_SERVER_NAME = 0x0000
    const val SNI_HOST_NAME = 0x00
}