package dev.gamblock.protection.dns

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Covers the empty-NOERROR response used to suppress IPv6.
 *
 * The wire format matters more than usual here, because a malformed answer does
 * not fail loudly: the client either ignores it and times out, or caches
 * something wrong. The assertions below are the ones that distinguish "client
 * silently retries over IPv4" from "user sees a spinning page".
 */
class DnsEmptyNoErrorTest {

    /** Minimal query for `example.com` A, built by hand so the test owns its bytes. */
    private fun query(type: Int = DnsConstants.TYPE_AAAA, id: Int = 0x1234): ByteArray {
        val name = byteArrayOf(7) + "example".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(3) + "com".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0)
        val out = ArrayList<Byte>()
        out.add(((id shr 8) and 0xFF).toByte())
        out.add((id and 0xFF).toByte())
        out.add(0x01); out.add(0x00) // flags: RD
        out.add(0x00); out.add(0x01) // QDCOUNT = 1
        repeat(6) { out.add(0x00) } // ANCOUNT NSCOUNT ARCOUNT
        out.addAll(name.toList())
        out.add(((type shr 8) and 0xFF).toByte())
        out.add((type and 0xFF).toByte())
        out.add(0x00); out.add(0x01) // CLASS IN
        return out.toByteArray()
    }

    @Test
    fun `rcode is NOERROR, not NXDOMAIN`() {
        val response = DnsResponseFactory.emptyNoError(query())
        val flags = ((response[2].toInt() and 0xFF) shl 8) or (response[3].toInt() and 0xFF)
        assertThat(flags and DnsConstants.MASK_RCODE).isEqualTo(DnsConstants.RCODE_OK)
    }

    @Test
    fun `response is a reply, not a query`() {
        val response = DnsResponseFactory.emptyNoError(query())
        val flags = ((response[2].toInt() and 0xFF) shl 8) or (response[3].toInt() and 0xFF)
        assertThat(flags and DnsConstants.FLAG_QR).isEqualTo(DnsConstants.FLAG_QR)
    }

    @Test
    fun `transaction id is echoed`() {
        val response = DnsResponseFactory.emptyNoError(query(id = 0xBEEF))
        assertThat(DnsParser.headerId(response)).isEqualTo(0xBEEF)
    }

    @Test
    fun `answer section is empty`() {
        val response = DnsResponseFactory.emptyNoError(query())
        val an = ((response[6].toInt() and 0xFF) shl 8) or (response[7].toInt() and 0xFF)
        val ns = ((response[8].toInt() and 0xFF) shl 8) or (response[9].toInt() and 0xFF)
        val ar = ((response[10].toInt() and 0xFF) shl 8) or (response[11].toInt() and 0xFF)
        assertThat(an).isEqualTo(0)
        assertThat(ns).isEqualTo(0)
        assertThat(ar).isEqualTo(0)
    }

    @Test
    fun `question count is preserved and the question is echoed`() {
        val q = query()
        val response = DnsResponseFactory.emptyNoError(q)
        val qd = ((response[4].toInt() and 0xFF) shl 8) or (response[5].toInt() and 0xFF)
        assertThat(qd).isEqualTo(1)
        // The question must come back byte-identical or the client discards it.
        assertThat(response.copyOfRange(12, response.size)).isEqualTo(q.copyOfRange(12, q.size))
    }

    @Test
    fun `question type survives the round trip`() {
        val response = DnsResponseFactory.emptyNoError(query(type = DnsConstants.TYPE_AAAA))
        val question = DnsParser.parseQuestion(response)
        assertThat(question.name).isEqualTo("example.com")
        assertThat(question.type).isEqualTo(DnsConstants.TYPE_AAAA)
    }

    @Test
    fun `A queries can also be answered empty without breaking`() {
        val response = DnsResponseFactory.emptyNoError(query(type = DnsConstants.TYPE_A))
        assertThat(DnsParser.parseQuestion(response).type).isEqualTo(DnsConstants.TYPE_A)
    }

    @Test
    fun `a query with no question still yields a well-formed header`() {
        val headerOnly = ByteArray(12).also {
            it[0] = 0x12; it[1] = 0x34
        }
        val response = DnsResponseFactory.emptyNoError(headerOnly)
        assertThat(response).hasLength(12)
        assertThat(DnsParser.headerId(response)).isEqualTo(0x1234)
    }

    @Test
    fun `suppression is distinguishable from a block`() {
        // Empty NOERROR and NXDOMAIN must never be confused, because the first
        // means "no IPv6" and the second means "no such domain".
        val suppressed = DnsResponseFactory.emptyNoError(query())
        val blocked = DnsResponseFactory.blocked(query())
        assertThat(suppressed).isNotEqualTo(blocked)
        assertThat(suppressed[3].toInt() and DnsConstants.MASK_RCODE)
            .isNotEqualTo(blocked[3].toInt() and DnsConstants.MASK_RCODE)
    }
}
