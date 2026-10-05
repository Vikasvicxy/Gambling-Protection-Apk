package dev.gamblock.protection.dns

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * SERVFAIL is what the encrypted upstream returns when the DoH provider cannot be
 * reached. It has to be distinguishable from the REFUSED used for blocked and
 * malformed queries, and it has to echo the question so the client's transaction
 * completes rather than timing out.
 */
class DnsResponseFactoryServFailTest {

    private fun query(name: String = "example.com", id: Int = 0x1234): ByteArray {
        val labels = name.split('.')
        val out = ArrayList<Byte>()
        out.add(((id shr 8) and 0xFF).toByte())
        out.add((id and 0xFF).toByte())
        out.add(0x01); out.add(0x00) // RD
        out.add(0x00); out.add(0x01) // QDCOUNT
        out.addAll(listOf(0, 0, 0, 0, 0, 0))
        labels.forEach {
            out.add(it.length.toByte())
            it.toByteArray(Charsets.US_ASCII).forEach { b -> out.add(b) }
        }
        out.add(0x00)
        out.add(0x00); out.add(0x01) // QTYPE A
        out.add(0x00); out.add(0x01) // QCLASS IN
        return out.toByteArray()
    }

    private fun rcodeOf(response: ByteArray): Int = response[3].toInt() and 0x0F

    private fun flagsOf(response: ByteArray): Int =
        ((response[2].toInt() and 0xFF) shl 8) or (response[3].toInt() and 0xFF)

    @Test
    fun `sets the qr bit`() {
        val response = DnsResponseFactory.servFail(query())

        assertThat(flagsOf(response) and 0x8000).isEqualTo(0x8000)
    }

    @Test
    fun `returns server failure rather than refused`() {
        val response = DnsResponseFactory.servFail(query())

        assertThat(rcodeOf(response)).isEqualTo(2)
    }

    @Test
    fun `is distinguishable from refused`() {
        val servFail = DnsResponseFactory.servFail(query())

        assertThat(rcodeOf(servFail)).isNotEqualTo(rcodeOf(DnsResponseFactory.refused(query())))
    }

    @Test
    fun `is distinguishable from blocked`() {
        val servFail = DnsResponseFactory.servFail(query())

        assertThat(rcodeOf(servFail)).isNotEqualTo(rcodeOf(DnsResponseFactory.blocked(query())))
    }

    @Test
    fun `is distinguishable from an empty noerror`() {
        val servFail = DnsResponseFactory.servFail(query())

        assertThat(rcodeOf(servFail)).isNotEqualTo(rcodeOf(DnsResponseFactory.emptyNoError(query())))
    }

    @Test
    fun `echoes the transaction id`() {
        val response = DnsResponseFactory.servFail(query(id = 0xABCD))

        assertThat(response[0].toInt() and 0xFF).isEqualTo(0xAB)
        assertThat(response[1].toInt() and 0xFF).isEqualTo(0xCD)
    }

    @Test
    fun `echoes the question so the client transaction completes`() {
        val q = query("echoed.example")
        val response = DnsResponseFactory.servFail(q)

        assertThat(((response[4].toInt() and 0xFF) shl 8) or (response[5].toInt() and 0xFF)).isEqualTo(1)
        assertThat(response.copyOfRange(12, response.size).toList()).isEqualTo(q.copyOfRange(12, q.size).toList())
    }

    @Test
    fun `reports no answers`() {
        val response = DnsResponseFactory.servFail(query())

        assertThat(((response[6].toInt() and 0xFF) shl 8) or (response[7].toInt() and 0xFF)).isEqualTo(0)
    }

    @Test
    fun `returns only a header when the query has no question`() {
        val bare = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x00, 0, 0, 0, 0, 0, 0)
        val response = DnsResponseFactory.servFail(bare)

        assertThat(response.size).isEqualTo(12)
        assertThat(rcodeOf(response)).isEqualTo(2)
    }

    @Test
    fun `handles a truncated query without throwing`() {
        val q = query("truncated.example").copyOfRange(0, 16)

        assertThat(DnsResponseFactory.servFail(q).size).isAtLeast(12)
    }

    @Test
    fun `handles a bare twelve byte header without throwing`() {
        val bareHeader = ByteArray(12).also {
            it[2] = 0x01
        }

        assertThat(DnsResponseFactory.servFail(bareHeader).size).isEqualTo(12)
    }
}