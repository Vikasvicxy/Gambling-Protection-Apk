package dev.gamblock.protection.vpn

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The transport seam for the encrypted upstream.
 *
 * Extracted so the wire-format logic below can be tested without a network, and
 * so the production implementation is the only place that touches TLS.
 */
interface DohTransport {
    /**
     * POSTs [body] to [url] with `Content-Type: application/dns-message` and
     * returns the raw response body.
     *
     * @throws IOException on any transport, TLS or non-200 outcome.
     */
    @Throws(IOException::class)
    fun post(url: String, body: ByteArray): ByteArray
}

/**
 * Synchronous DNS-over-HTTPS resolver (RFC 8484).
 *
 * RFC 8484 allows GET with a `dns=` parameter or POST with a bare message body.
 * This uses POST: a GET puts every hostname Shield resolves into the URL, where
 * it lands in proxy and server access logs, which is precisely the leak DoH
 * exists to prevent.
 *
 * Privacy properties, which are the whole point of this path:
 * - Only the provider named by the current [DohProvider] ever sees a query.
 *   There is no plaintext fallback, because a silent downgrade would hand the
 *   name to the carrier anyway while appearing protected.
 * - The platform trust store is used unmodified. No custom TrustManager is
 *   installed, so a TLS-intercepting proxy cannot become the resolver unless it
 *   already holds a CA the device trusts.
 * - No hostname is logged by this class.
 */
class DohUpstreamResolver(
    val provider: DohProvider = DohProvider.default,
    private val transport: DohTransport = OkHttpDohTransport(),
    private val maxQueryBytes: Int = MAX_QUERY_BYTES,
) {

    sealed interface DohResult {
        data class Resolved(val response: ByteArray) : DohResult {
            override fun equals(other: Any?): Boolean =
                this === other || (other is Resolved && response.contentEquals(other.response))

            override fun hashCode(): Int = response.contentHashCode()
        }

        /** Transport, HTTP, or payload failure. The caller must not retry in plaintext. */
        data class Failed(val reason: String) : DohResult
    }

    /**
     * @param query a complete DNS request message as it arrived on the tun.
     * @return the raw DNS response message.
     */
    fun resolve(query: ByteArray): DohResult {
        if (!provider.isEncrypted) return DohResult.Failed("provider ${provider.name} is not encrypted")

        val rejection = validateQuery(query)
        if (rejection != null) return DohResult.Failed(rejection)

        val body = try {
            transport.post(provider.url, query)
        } catch (t: Throwable) {
            return DohResult.Failed(t.message ?: t::class.java.simpleName)
        }

        if (body.size < 12) return DohResult.Failed("response of ${body.size} bytes is shorter than a DNS header")
        if (!isResponse(body)) return DohResult.Failed("response does not carry the QR bit")
        if (body[0] != query[0] || body[1] != query[1]) {
            return DohResult.Failed("response transaction id does not match the query")
        }
        if (!echoesQuestion(query, body)) {
            return DohResult.Failed("response question section does not match the query")
        }
        return DohResult.Resolved(body)
    }

    /** Returns a rejection reason, or null when the query is structurally usable. */
    private fun validateQuery(query: ByteArray): String? {
        if (query.size < 12) return "query shorter than a DNS header"
        if (query.size > maxQueryBytes) return "query of ${query.size} bytes exceeds $maxQueryBytes"
        // Opcode must be a standard query, and this must not already be a response.
        val flagsHigh = query[2].toInt() and 0xFF
        if ((flagsHigh and 0x80) != 0) return "query carries the QR bit"
        val opcode = (flagsHigh shr 3) and 0x0F
        if (opcode != 0) return "unsupported opcode $opcode"
        // Exactly one question, so the echoed section below is unambiguous.
        val questions = ((query[4].toInt() and 0xFF) shl 8) or (query[5].toInt() and 0xFF)
        if (questions != 1) return "expected exactly one question, found $questions"
        if (questionSection(query) == null) return "query question section is truncated"
        return null
    }

    /** True when the QR bit marks this as a response. */
    private fun isResponse(bytes: ByteArray): Boolean = (bytes[2].toInt() and 0x80) != 0

    /**
     * Confirms the response answers this query.
     *
     * A provider returning an answer for a different name would otherwise satisfy
     * the lookup and silently defeat blocking, so the question section must match
     * byte for byte.
     */
    private fun echoesQuestion(query: ByteArray, response: ByteArray): Boolean {
        val asked = questionSection(query) ?: return false
        val echoed = questionSection(response) ?: return false
        return asked.contentEquals(echoed)
    }

    /**
     * Returns the raw question section (QNAME labels plus QTYPE and QCLASS).
     *
     * QNAME is never compressed in a question, so a plain label walk is exact.
     */
    private fun questionSection(message: ByteArray): ByteArray? {
        var p = 12
        while (p < message.size) {
            val labelLength = message[p].toInt() and 0xFF
            p += 1
            if (labelLength == 0) break
            if (labelLength > 63) return null
            p += labelLength
            if (p >= message.size) return null
        }
        if (p + 4 > message.size) return null
        return message.copyOfRange(12, p + 4)
    }

    companion object {
        const val MAX_QUERY_BYTES = 65_535
    }
}

/**
 * Production transport over OkHttp.
 *
 * OkHttp rather than HttpURLConnection for two reasons that matter to this path:
 *
 * 1. It accepts a [javax.net.SocketFactory], so the client can be constructed with
 *    the physical network's socket factory. Shield's own DoH queries would
 *    otherwise be routed back through the tun this service establishes, which is
 *    a self-inflicted loop on Android's per-UID routing.
 * 2. Connection reuse across queries. A DNS client that redoes a TCP plus TLS
 *    handshake per lookup spends most of its latency budget on setup, and a burst
 *    of lookups multiplies that cost by every cached record a page pulls in.
 *
 * Trust is left entirely to the platform: the default trust manager and hostname
 * verifier are kept, so a TLS-intercepting proxy still cannot become the resolver
 * unless it already holds a CA the device trusts.
 */
class OkHttpDohTransport(
    socketFactory: javax.net.SocketFactory? = null,
    connectTimeoutMs: Int = 5_000,
    readTimeoutMs: Int = 8_000,
) : DohTransport {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .callTimeout((connectTimeoutMs + readTimeoutMs).toLong(), TimeUnit.MILLISECONDS)
        // No custom trust manager, so platform CA trust and hostname verification apply.
        .apply { socketFactory?.let { socketFactory(it) } }
        // A retry could re-send a query the provider already answered, and would
        // also silently mask a flaky path that users should see as a failure.
        .retryOnConnectionFailure(false)
        // A redirect would move the query to a host the user never chose.
        .followRedirects(false)
        .followSslRedirects(false)
        .cache(null)
        .build()

    override fun post(url: String, body: ByteArray): ByteArray {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(CONTENT_TYPE.toMediaType()))
            .header("Accept", CONTENT_TYPE)
            .header("Cache-Control", "no-store")
            .header("User-Agent", USER_AGENT)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} from provider")
            val payload = response.body?.bytes() ?: throw IOException("provider returned an empty body")
            if (payload.size > MAX_RESPONSE_BYTES) {
                throw IOException("provider response of ${payload.size} bytes exceeds $MAX_RESPONSE_BYTES")
            }
            return payload
        }
    }

    private companion object {
        const val CONTENT_TYPE = "application/dns-message"
        const val USER_AGENT = "Shield-Android/1.3.0 (DoH)"
        const val MAX_RESPONSE_BYTES = 65_535
    }
}