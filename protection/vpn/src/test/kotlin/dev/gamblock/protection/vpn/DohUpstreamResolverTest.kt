package dev.gamblock.protection.vpn

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import org.junit.Test

/**
 * The DoH path must fail closed. A fallback to plaintext UDP would hand the
 * hostname to the carrier anyway while the UI claimed protection, so every
 * failure has to stay a failure.
 */
class DohUpstreamResolverTest {

    /** Records what was sent and replies with a caller-supplied body. */
    private class FakeTransport(
        private val respond: (ByteArray) -> ByteArray,
    ) : DohTransport {
        var lastUrl: String? = null
        var lastBody: ByteArray? = null
        var callCount = 0

        override fun post(url: String, body: ByteArray): ByteArray {
            callCount++
            lastUrl = url
            lastBody = body
            return respond(body)
        }
    }

    private class ExplodingTransport(private val message: String) : DohTransport {
        override fun post(url: String, body: ByteArray): ByteArray = throw IOException(message)
    }

    private fun resolved(result: DohUpstreamResolver.DohResult): ByteArray {
        assertThat(result).isInstanceOf(DohUpstreamResolver.DohResult.Resolved::class.java)
        return (result as DohUpstreamResolver.DohResult.Resolved).response
    }

    private fun reason(result: DohUpstreamResolver.DohResult): String {
        assertThat(result).isInstanceOf(DohUpstreamResolver.DohResult.Failed::class.java)
        return (result as DohUpstreamResolver.DohResult.Failed).reason
    }

    private fun roundTrip(provider: DohProvider = DohProvider.QUAD9): DohUpstreamResolver {
        val transport = FakeTransport { DnsMessageBuilder.response(it) }
        return DohUpstreamResolver(provider, transport)
    }

    // ---- happy path ----

    @Test
    fun `resolves a standard A query`() {
        val query = DnsMessageBuilder.query("example.com")
        val transport = FakeTransport { DnsMessageBuilder.response(it) }
        val resolver = DohUpstreamResolver(DohProvider.QUAD9, transport)

        val response = resolved(resolver.resolve(query))

        assertThat(response.size).isGreaterThan(12)
        assertThat(response[2].toInt() and 0x80).isNotEqualTo(0)
    }

    @Test
    fun `posts to the configured provider url`() {
        val query = DnsMessageBuilder.query("example.com")
        val transport = FakeTransport { DnsMessageBuilder.response(it) }

        DohUpstreamResolver(DohProvider.CLOUDFLARE, transport).resolve(query)

        assertThat(transport.lastUrl).isEqualTo("https://cloudflare-dns.com/dns-query")
    }

    @Test
    fun `sends the query bytes verbatim`() {
        val query = DnsMessageBuilder.query("verbatim.example")
        val transport = FakeTransport { DnsMessageBuilder.response(it) }

        DohUpstreamResolver(DohProvider.QUAD9, transport).resolve(query)

        assertThat(transport.lastBody!!.toList()).isEqualTo(query.toList())
    }

    @Test
    fun `uses post so the hostname never enters a url`() {
        val query = DnsMessageBuilder.query("secret.example")
        val transport = FakeTransport { DnsMessageBuilder.response(it) }

        DohUpstreamResolver(DohProvider.QUAD9, transport).resolve(query)

        // The URL is the fixed provider endpoint; the name travels in the body only.
        assertThat(transport.lastUrl).doesNotContain("secret")
        // The body carries the name as length-prefixed labels on the wire.
        assertThat(String(transport.lastBody!!, Charsets.ISO_8859_1)).contains("secret")
    }

    @Test
    fun `preserves a non default transaction id`() {
        val query = DnsMessageBuilder.query("txid.example", transactionId = 0xBEEF)
        val transport = FakeTransport { DnsMessageBuilder.response(it) }

        val response = resolved(DohUpstreamResolver(DohProvider.QUAD9, transport).resolve(query))

        assertThat(response[0].toInt() and 0xFF).isEqualTo(0xBE)
        assertThat(response[1].toInt() and 0xFF).isEqualTo(0xEF)
    }

    @Test
    fun `resolves an AAAA query`() {
        val query = DnsMessageBuilder.query("v6.example", type = DnsMessageBuilder.TYPE_AAAA)
        val transport = FakeTransport { DnsMessageBuilder.response(it) }

        resolved(DohUpstreamResolver(DohProvider.QUAD9, transport).resolve(query))
    }

    @Test
    fun `resolves a single label hostname`() {
        val query = DnsMessageBuilder.query("localhost")
        val transport = FakeTransport { DnsMessageBuilder.response(it) }

        resolved(DohUpstreamResolver(DohProvider.QUAD9, transport).resolve(query))
    }

    @Test
    fun `resolves a deep hostname`() {
        val query = DnsMessageBuilder.query("a.b.c.d.e.f.g.example.com")
        val transport = FakeTransport { DnsMessageBuilder.response(it) }

        resolved(DohUpstreamResolver(DohProvider.QUAD9, transport).resolve(query))
    }

    @Test
    fun `resolves a hostname with a hyphen and digits`() {
        val query = DnsMessageBuilder.query("my-site-01.example.co.uk")
        val transport = FakeTransport { DnsMessageBuilder.response(it) }

        resolved(DohUpstreamResolver(DohProvider.QUAD9, transport).resolve(query))
    }

    @Test
    fun `accepts every configured provider`() {
        DohProvider.entries.forEach { provider ->
            val transport = FakeTransport { DnsMessageBuilder.response(it) }
            resolved(DohUpstreamResolver(provider, transport).resolve(DnsMessageBuilder.query("p.example")))
        }
    }

    // ---- fails closed ----

    @Test
    fun `never falls back to plaintext when the transport fails`() {
        val resolver = DohUpstreamResolver(DohProvider.QUAD9, ExplodingTransport("connection refused"))

        val result = resolver.resolve(DnsMessageBuilder.query("down.example"))

        assertThat(result).isInstanceOf(DohUpstreamResolver.DohResult.Failed::class.java)
        assertThat(reason(result)).contains("connection refused")
    }

    @Test
    fun `reports a transport timeout as a failure`() {
        val resolver = DohUpstreamResolver(DohProvider.QUAD9, ExplodingTransport("timeout"))

        assertThat(resolver.resolve(DnsMessageBuilder.query("slow.example")))
            .isInstanceOf(DohUpstreamResolver.DohResult.Failed::class.java)
    }

    @Test
    fun `rejects a response for a different transaction id`() {
        val query = DnsMessageBuilder.query("mixup.example", transactionId = 0x1111)
        val transport = FakeTransport { DnsMessageBuilder.response(it, transactionId = 0x2222) }

        val result = DohUpstreamResolver(DohProvider.QUAD9, transport).resolve(query)

        assertThat(reason(result)).contains("transaction id")
    }

    @Test
    fun `rejects a response answering a different name`() {
        val query = DnsMessageBuilder.query("asked.example")
        val transport = FakeTransport { DnsMessageBuilder.response(DnsMessageBuilder.query("other.example")) }

        val result = DohUpstreamResolver(DohProvider.QUAD9, transport).resolve(query)

        assertThat(reason(result)).contains("question section")
    }

    @Test
    fun `rejects an html error page served as dns`() {
        val query = DnsMessageBuilder.query("html.example")
        val transport = FakeTransport { "<html>502 Bad Gateway</html>".toByteArray() }

        val result = DohUpstreamResolver(DohProvider.QUAD9, transport).resolve(query)

        assertThat(result).isInstanceOf(DohUpstreamResolver.DohResult.Failed::class.java)
    }

    @Test
    fun `rejects a truncated response`() {
        val query = DnsMessageBuilder.query("short.example")
        val transport = FakeTransport { ByteArray(5) }

        assertThat(reason(DohUpstreamResolver(DohProvider.QUAD9, transport).resolve(query)))
            .contains("shorter than a DNS header")
    }

    @Test
    fun `rejects a query shorter than a dns header`() {
        val resolver = roundTrip()

        assertThat(reason(resolver.resolve(ByteArray(11)))).contains("shorter than a DNS header")
    }

    @Test
    fun `rejects an empty query`() {
        assertThat(reason(roundTrip().resolve(ByteArray(0)))).contains("shorter than a DNS header")
    }

    @Test
    fun `rejects an oversized query`() {
        val resolver = DohUpstreamResolver(
            DohProvider.QUAD9,
            FakeTransport { DnsMessageBuilder.response(it) },
            maxQueryBytes = 32,
        )

        val big = DnsMessageBuilder.query("a-very-long-name-indeed.example.com")

        assertThat(reason(resolver.resolve(big))).contains("exceeds")
    }

    @Test
    fun `rejects a query that is already a response`() {
        val query = DnsMessageBuilder.query("weird.example")
        query[2] = (query[2].toInt() or 0x80.toInt()).toByte()

        assertThat(reason(roundTrip().resolve(query))).contains("QR bit")
    }

    @Test
    fun `rejects a non standard opcode`() {
        val query = DnsMessageBuilder.query("opcode.example")
        query[2] = ((query[2].toInt() and 0xF8.toInt()) or (2 shl 3)).toByte() // opcode 2 = STATUS

        assertThat(reason(roundTrip().resolve(query))).contains("opcode")
    }

    @Test
    fun `rejects a query with zero questions`() {
        val query = DnsMessageBuilder.query("noq.example")
        query[5] = 0

        assertThat(reason(roundTrip().resolve(query))).contains("exactly one question")
    }

    @Test
    fun `rejects a query with two questions`() {
        val query = DnsMessageBuilder.query("twoq.example")
        query[5] = 2

        assertThat(reason(roundTrip().resolve(query))).contains("exactly one question")
    }

    @Test
    fun `rejects a query whose name label runs past the message`() {
        val query = DnsMessageBuilder.query("truncated.example").copyOfRange(0, 16)

        assertThat(roundTrip().resolve(query)).isInstanceOf(DohUpstreamResolver.DohResult.Failed::class.java)
    }

    @Test
    fun `never sends a request when the query is malformed`() {
        val transport = FakeTransport { DnsMessageBuilder.response(it) }
        val resolver = DohUpstreamResolver(DohProvider.QUAD9, transport)

        resolver.resolve(ByteArray(4))

        // Rejecting locally is what keeps a hostile packet from reaching the network.
        assertThat(transport.callCount).isEqualTo(0)
    }

    @Test
    fun `survives a transport that throws a non io exception`() {
        val resolver = DohUpstreamResolver(
            DohProvider.QUAD9,
            object : DohTransport {
                override fun post(url: String, body: ByteArray): ByteArray = throw OutOfMemoryError("boom")
            },
        )

        assertThat(resolver.resolve(DnsMessageBuilder.query("oops.example")))
            .isInstanceOf(DohUpstreamResolver.DohResult.Failed::class.java)
    }

    @Test
    fun `handles a provider that returns an empty body`() {
        val transport = FakeTransport { ByteArray(0) }

        assertThat(reason(DohUpstreamResolver(DohProvider.QUAD9, transport).resolve(DnsMessageBuilder.query("e.example"))))
            .contains("shorter than a DNS header")
    }

    // ---- providers ----

    @Test
    fun `every provider uses https`() {
        DohProvider.entries.forEach { assertThat(it.url).startsWith("https://") }
        DohProvider.entries.forEach { assertThat(it.isEncrypted).isTrue() }
    }

    @Test
    fun `provider urls are the documented dns-query endpoints`() {
        assertThat(DohProvider.QUAD9.url).isEqualTo("https://dns.quad9.net/dns-query")
        assertThat(DohProvider.CLOUDFLARE.url).isEqualTo("https://cloudflare-dns.com/dns-query")
        assertThat(DohProvider.CLOUDFLARE_SECURITY.url).isEqualTo("https://security.cloudflare-dns.com/dns-query")
        DohProvider.entries.forEach { assertThat(it.url).endsWith("/dns-query") }
    }

    @Test
    fun `provider urls are unique`() {
        assertThat(DohProvider.entries.map { it.url }.toSet()).hasSize(DohProvider.entries.size)
    }

    @Test
    fun `filtered providers declare what they filter`() {
        assertThat(DohProvider.QUAD9.filtersMalware).isTrue()
        assertThat(DohProvider.QUAD9.filtersContent).isFalse()
        assertThat(DohProvider.CLOUDFLARE_SECURITY.filtersMalware).isTrue()
        assertThat(DohProvider.CLOUDFLARE.filtersMalware).isFalse()
    }

    @Test
    fun `description states the filtering behaviour`() {
        DohProvider.entries.forEach {
            assertThat(it.description).isNotEmpty()
            assertThat(it.description).contains("Encrypted over HTTPS")
        }
        assertThat(DohProvider.QUAD9.description).contains("malware")
        assertThat(DohProvider.CLOUDFLARE.description).contains("No filtering")
    }

    @Test
    fun `default provider is filtered`() {
        assertThat(DohProvider.default).isEqualTo(DohProvider.QUAD9)
        assertThat(DohProvider.default.filtersMalware).isTrue()
    }

    @Test
    fun `fromName round trips`() {
        DohProvider.entries.forEach { assertThat(DohProvider.fromNameOrDefault(it.name)).isEqualTo(it) }
    }

    @Test
    fun `fromName falls back to the default for unknown input`() {
        assertThat(DohProvider.fromNameOrDefault("NOPE")).isEqualTo(DohProvider.default)
        assertThat(DohProvider.fromNameOrDefault(null)).isEqualTo(DohProvider.default)
    }

    @Test
    fun `display names are unique and non empty`() {
        val names = DohProvider.entries.map { it.displayName }
        assertThat(names.toSet()).hasSize(names.size)
        names.forEach { assertThat(it).isNotEmpty() }
    }

    // ---- response identity ----

    @Test
    fun `resolved responses compare by content`() {
        val a = DohUpstreamResolver.DohResult.Resolved(byteArrayOf(1, 2, 3))
        val b = DohUpstreamResolver.DohResult.Resolved(byteArrayOf(1, 2, 3))
        val c = DohUpstreamResolver.DohResult.Resolved(byteArrayOf(9, 9, 9))

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
        assertThat(a).isNotEqualTo(c)
    }
}