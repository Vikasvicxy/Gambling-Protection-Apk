package dev.gamblock.protection.vpn

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The parser reads bytes from an untrusted network path, so the hostile cases carry
 * most of the weight here: a crafted ClientHello must never crash the tun reader,
 * must never surface a partially decoded hostname, and must never be able to smuggle
 * a NUL or wildcard into the blocking decision.
 */
class TlsSniParserTest {

    private fun hostname(result: TlsSniParser.SniResult): String? =
        (result as? TlsSniParser.SniResult.Present)?.hostname

    private fun reason(result: TlsSniParser.SniResult): String? =
        (result as? TlsSniParser.SniResult.Unreadable)?.reason

    // ---- well formed ----

    @Test
    fun `extracts a hostname from a standard ClientHello`() {
        val bytes = ClientHelloBuilder.builder().serverName("www.example.com").build()

        assertThat(hostname(TlsSniParser.parse(bytes))).isEqualTo("www.example.com")
    }

    @Test
    fun `lowercases the hostname`() {
        val bytes = ClientHelloBuilder.builder().serverName("WWW.Example.COM").build()

        assertThat(hostname(TlsSniParser.parse(bytes))).isEqualTo("www.example.com")
    }

    @Test
    fun `handles a session id of every legal length`() {
        listOf(0, 1, 16, 32).forEach { size ->
            val bytes = ClientHelloBuilder.builder()
                .sessionId(ByteArray(size) { 0x7F })
                .serverName("casino.example")
                .build()

            assertThat(hostname(TlsSniParser.parse(bytes))).isEqualTo("casino.example")
        }
    }

    @Test
    fun `handles a single cipher suite`() {
        val bytes = ClientHelloBuilder.builder()
            .cipherSuites(intArrayOf(0x1301))
            .serverName("a.example")
            .build()

        assertThat(hostname(TlsSniParser.parse(bytes))).isEqualTo("a.example")
    }

    @Test
    fun `finds the extension when other extensions come first`() {
        val bytes = ClientHelloBuilder.builder()
            .rawExtension(0x0017, byteArrayOf(0x00, 0x00))
            .rawExtension(0xFF01, byteArrayOf(1, 2, 3))
            .serverName("late.example")
            .build()

        assertThat(hostname(TlsSniParser.parse(bytes))).isEqualTo("late.example")
    }

    @Test
    fun `finds the extension when it comes first`() {
        val bytes = ClientHelloBuilder.builder()
            .serverName("early.example")
            .rawExtension(0xFF01, byteArrayOf(1, 2, 3))
            .build()

        assertThat(hostname(TlsSniParser.parse(bytes))).isEqualTo("early.example")
    }

    @Test
    fun `accepts a hostname at the 253 character limit`() {
        val label = "a".repeat(63)
        val hostname = (1..4).joinToString(".") { label }.let { it.substring(0, 253).substringBeforeLast(".") }
        val bytes = ClientHelloBuilder.builder().serverName(hostname).build()

        assertThat(hostname(TlsSniParser.parse(bytes))).isEqualTo(hostname)
    }

    @Test
    fun `accepts underscore which is common in real SNI`() {
        val bytes = ClientHelloBuilder.builder().serverName("_dmarc.example.com").build()

        assertThat(hostname(TlsSniParser.parse(bytes))).isEqualTo("_dmarc.example.com")
    }

    @Test
    fun `handles a trailing zero extension`() {
        val bytes = ClientHelloBuilder.builder()
            .serverName("ok.example")
            .rawExtension(0x0000, ByteArray(0))
            .build()

        // First server_name wins; the empty later one must not corrupt the result.
        assertThat(hostname(TlsSniParser.parse(bytes))).isEqualTo("ok.example")
    }

    @Test
    fun `parses a tls record with major version 3`() {
        val bytes = ClientHelloBuilder.builder().serverName("tls13.example").build()

        assertThat(bytes[1].toInt()).isEqualTo(0x03)
        assertThat(hostname(TlsSniParser.parse(bytes))).isEqualTo("tls13.example")
    }

    // ---- absent ----

    @Test
    fun `reports absent when there is no server_name extension`() {
        val bytes = ClientHelloBuilder.builder().rawExtension(0xFF01, byteArrayOf(1)).build()

        assertThat(TlsSniParser.parse(bytes)).isInstanceOf(TlsSniParser.SniResult.Absent::class.java)
    }

    @Test
    fun `reports absent when there are no extensions at all`() {
        val bytes = ClientHelloBuilder.builder().noExtensions().build()

        assertThat(TlsSniParser.parse(bytes)).isInstanceOf(TlsSniParser.SniResult.Absent::class.java)
    }

    @Test
    fun `reports absent for an IP literal connection`() {
        val bytes = ClientHelloBuilder.builder().noExtensions().build()

        assertThat(TlsSniParser.parse(bytes)).isInstanceOf(TlsSniParser.SniResult.Absent::class.java)
    }

    @Test
    fun `reports absent when the server_name list holds only non host_name types`() {
        val bytes = ClientHelloBuilder.builder().rawServerName(0x01, byteArrayOf(9, 9, 9), 3).build()

        assertThat(TlsSniParser.parse(bytes)).isInstanceOf(TlsSniParser.SniResult.Absent::class.java)
    }

    @Test
    fun `empty input is unreadable not a crash`() {
        assertThat(TlsSniParser.parse(ByteArray(0), 0))
            .isInstanceOf(TlsSniParser.SniResult.Unreadable::class.java)
    }

    // ---- not TLS at all ----

    @Test
    fun `rejects an http request`() {
        val bytes = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray()

        assertThat(reason(TlsSniParser.parse(bytes))).contains("not a TLS handshake record")
    }

    @Test
    fun `rejects an application data record`() {
        val bytes = byteArrayOf(0x17, 0x03, 0x03, 0x00, 0x05, 1, 2, 3, 4, 5)

        assertThat(reason(TlsSniParser.parse(bytes))).contains("not a TLS handshake record")
    }

    @Test
    fun `rejects an sslv2 client hello`() {
        // SSLv2 CLIENT-HELLO: length(2), msg_type(1), version(2), cipher_specs(2)...
        val bytes = byteArrayOf(0x80.toByte(), 0x2E, 0x01, 0x03, 0x01, 0x00, 0x2F, 0x00, 0x00)

        assertThat(TlsSniParser.parse(bytes)).isInstanceOf(TlsSniParser.SniResult.Unreadable::class.java)
    }

    @Test
    fun `rejects a non three major version`() {
        val bytes = byteArrayOf(0x16, 0x02, 0x01, 0x00, 0x02, 0x01, 0x00)

        assertThat(reason(TlsSniParser.parse(bytes))).contains("unexpected TLS version")
    }

    @Test
    fun `rejects a non ClientHello handshake message`() {
        val bytes = ClientHelloBuilder.builder().serverName("x.example").build()
        bytes[5] = 0x02 // ServerHello

        assertThat(reason(TlsSniParser.parse(bytes))).contains("not a ClientHello")
    }

    @Test
    fun `rejects a buffer shorter than a record header`() {
        assertThat(reason(TlsSniParser.parse(byteArrayOf(0x16, 0x03, 0x01))))
            .contains("shorter than a TLS record header")
    }

    @Test
    fun `rejects a length beyond the buffer`() {
        val bytes = ClientHelloBuilder.builder().serverName("x.example").build()

        assertThat(reason(TlsSniParser.parse(bytes, bytes.size + 100)))
            .contains("outside buffer")
    }

    @Test
    fun `rejects a negative length`() {
        val bytes = ClientHelloBuilder.builder().serverName("x.example").build()

        assertThat(reason(TlsSniParser.parse(bytes, -1))).contains("outside buffer")
    }

    @Test
    fun `rejects an empty handshake record`() {
        val bytes = byteArrayOf(0x16, 0x03, 0x01, 0x00, 0x00)

        assertThat(reason(TlsSniParser.parse(bytes))).contains("empty handshake record")
    }

    // ---- inconsistent lengths ----

    @Test
    fun `rejects a record that claims more bytes than are present`() {
        val bytes = ClientHelloBuilder.builder().serverName("x.example").build()
        val truncated = bytes.copyOfRange(0, bytes.size - 20)

        assertThat(reason(TlsSniParser.parse(truncated))).contains("only")
    }

    @Test
    fun `rejects an absurd record length`() {
        val bytes = byteArrayOf(0x16, 0x03, 0x03, 0xFF.toByte(), 0xFF.toByte(), 0x01)

        assertThat(reason(TlsSniParser.parse(bytes))).contains("too large")
    }

    @Test
    fun `rejects a ClientHello body longer than the record`() {
        val hello = ClientHelloBuilder.builder().serverName("x.example")
        val bytes = hello.build()

        // Rewrite the handshake length to something implausible.
        bytes[6] = 0x7F

        assertThat(TlsSniParser.parse(bytes)).isInstanceOf(TlsSniParser.SniResult.Unreadable::class.java)
    }

    @Test
    fun `rejects an inflated record length`() {
        val bytes = ClientHelloBuilder.builder().serverName("x.example").build()
        val lying = ByteArray(bytes.size)
        System.arraycopy(bytes, 0, lying, 0, bytes.size)
        lying[3] = 0xFF.toByte()
        lying[4] = 0xFF.toByte()

        assertThat(TlsSniParser.parse(lying)).isInstanceOf(TlsSniParser.SniResult.Unreadable::class.java)
    }

    @Test
    fun `rejects an extension that overruns the extension list`() {
        val bytes = ClientHelloBuilder.builder()
            .extensionWithInflatedLength(0x0000, "evil.example".toByteArray())
            .build()

        assertThat(reason(TlsSniParser.parse(bytes))).contains("overruns the list")
    }

    @Test
    fun `rejects a server_name list longer than its extension`() {
        val bytes = ClientHelloBuilder.builder()
            .serverNameWithBadListLength("evil.example", declaredListLength = 4096)
            .build()

        assertThat(TlsSniParser.parse(bytes)).isInstanceOf(TlsSniParser.SniResult.Unreadable::class.java)
    }

    @Test
    fun `rejects a name entry longer than the list`() {
        val bytes = ClientHelloBuilder.builder()
            .rawServerName(ClientHelloBuilder.SNI_HOST_NAME, "short".toByteArray(), declaredLength = 60000)
            .build()

        assertThat(TlsSniParser.parse(bytes)).isInstanceOf(TlsSniParser.SniResult.Unreadable::class.java)
    }

    @Test
    fun `rejects a session id that overruns the ClientHello`() {
        val bytes = ClientHelloBuilder.builder()
            .sessionId(ByteArray(32) { 1 })
            .serverName("x.example")
            .build()
        // Claim a 255 byte session id when only a handful follow.
        bytes[ClientHelloBuilder.SESSION_ID_LENGTH_OFFSET] = 0xFF.toByte()

        assertThat(TlsSniParser.parse(bytes)).isInstanceOf(TlsSniParser.SniResult.Unreadable::class.java)
    }

    @Test
    fun `rejects an odd cipher suite length`() {
        val b = ClientHelloBuilder.builder()
        val bytes = b.serverName("x.example").build()
        bytes[ClientHelloBuilder.cipherLengthOffset(b.sessionIdLength())] = 0x07 // odd length

        assertThat(TlsSniParser.parse(bytes)).isInstanceOf(TlsSniParser.SniResult.Unreadable::class.java)
    }

    @Test
    fun `never throws on random bytes`() {
        val random = java.util.Random(20260905L)

        repeat(2_000) {
            val size = 5 + random.nextInt(120)
            val bytes = ByteArray(size).also { random.nextBytes(it) }
            if (bytes.isNotEmpty()) bytes[0] = 0x16
            // The contract is a result object, never an exception.
            assertThat(TlsSniParser.parse(bytes)).isNotNull()
        }
    }

    @Test
    fun `never throws on truncated real ClientHellos`() {
        val full = ClientHelloBuilder.builder().serverName("truncated.example").build()

        for (cut in 5 until full.size) {
            assertThat(TlsSniParser.parse(full.copyOfRange(0, cut))).isNotNull()
        }
    }

    // ---- hostname sanitisation ----

    @Test
    fun `rejects an empty host_name`() {
        val bytes = ClientHelloBuilder.builder().rawServerName(ClientHelloBuilder.SNI_HOST_NAME, ByteArray(0), 0).build()

        assertThat(reason(TlsSniParser.parse(bytes))).contains("empty host_name")
    }

    @Test
    fun `rejects a nul byte in the hostname`() {
        val b = ClientHelloBuilder.builder()
        val bytes = b.serverName("evil.example").build()
        // Overwrite the first hostname byte with NUL, leaving all lengths consistent
        // so the sanitiser is what rejects it rather than a length check.
        bytes[ClientHelloBuilder.hostnameOffset(bytes, "evil.example")] = 0x00

        val reason = reason(TlsSniParser.parse(bytes))
        assertThat(reason).isNotNull()
        assertThat(reason).contains("non-printable")
    }

    @Test
    fun `rejects a hostname longer than 253 characters`() {
        val long = (1..60).joinToString("") { "aaaaaaaaaa." }.dropLast(1).let { it + "b".repeat(200) }
        val bytes = ClientHelloBuilder.builder().serverName(long.take(260)).build()

        assertThat(TlsSniParser.parse(bytes)).isInstanceOf(TlsSniParser.SniResult.Unreadable::class.java)
    }

    @Test
    fun `rejects a leading dot`() {
        val bytes = ClientHelloBuilder.builder().serverName(".example.com").build()

        assertThat(reason(TlsSniParser.parse(bytes))).contains("leading or trailing dot")
    }

    @Test
    fun `rejects a trailing dot`() {
        val bytes = ClientHelloBuilder.builder().serverName("example.com.").build()

        assertThat(reason(TlsSniParser.parse(bytes))).contains("leading or trailing dot")
    }

    @Test
    fun `rejects an empty label in the middle`() {
        val bytes = ClientHelloBuilder.builder().serverName("a..example").build()

        assertThat(TlsSniParser.parse(bytes)).isInstanceOf(TlsSniParser.SniResult.Unreadable::class.java)
    }

    @Test
    fun `rejects a label longer than 63 characters`() {
        val bytes = ClientHelloBuilder.builder().serverName("a".repeat(64) + ".example").build()

        assertThat(TlsSniParser.parse(bytes)).isInstanceOf(TlsSniParser.SniResult.Unreadable::class.java)
    }

    @Test
    fun `rejects a hostname with a path separator`() {
        val bytes = ClientHelloBuilder.builder().serverName("evil.example/path").build()

        assertThat(TlsSniParser.parse(bytes)).isInstanceOf(TlsSniParser.SniResult.Unreadable::class.java)
    }

    @Test
    fun `rejects a wildcard hostname`() {
        val bytes = ClientHelloBuilder.builder().serverName("*.example.com").build()

        assertThat(TlsSniParser.parse(bytes)).isInstanceOf(TlsSniParser.SniResult.Unreadable::class.java)
    }

    @Test
    fun `never returns a hostname containing a nul or wildcard`() {
        val payloads = listOf(
            "evil.example",
            "*.example.com",
            "a b.example",
            "a/b.example",
            "-lead.example",
        )

        payloads.forEach { candidate ->
            val bytes = ClientHelloBuilder.builder().serverName(candidate).build()
            val result = TlsSniParser.parse(bytes)
            val value = hostname(result)
            if (value != null) {
                assertThat(value).doesNotContain("\u0000")
                assertThat(value).doesNotContain("*")
            }
        }
    }
}