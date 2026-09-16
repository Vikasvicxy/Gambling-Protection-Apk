package dev.gamblock.protection.dns

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DnsResponseFactoryTest {

    private fun query(name: String, id: Int = 0xABCD): ByteArray {
        val labels = DnsResponseFactory.encodeName(name)
        return byteArrayOf(
            ((id shr 8) and 0xFF).toByte(), (id and 0xFF).toByte(),
            1, 0,
            0, 1,
            0, 0, 0, 0, 0, 0,
        ) + labels + byteArrayOf(0) +
            byteArrayOf(0, 1) + byteArrayOf(0, 1)
    }

    private fun flags(response: ByteArray): Int =
        (response[2].toInt() and 0xFF shl 8) or (response[3].toInt() and 0xFF)

    @Test
    fun `refused echoes the identifier and sets QR and refused rcode`() {
        val response = DnsResponseFactory.refused(query("bet-example.test", id = 0x5555))
        assertThat(response.size).isEqualTo(12)
        assertThat(DnsParser.headerId(response)).isEqualTo(0x5555)
        val f = flags(response)
        assertThat(f and DnsConstants.FLAG_QR).isNotEqualTo(0)
        assertThat(f and DnsConstants.MASK_RCODE).isEqualTo(DnsConstants.RCODE_REFUSED)
    }

    @Test
    fun `blocked produces NXDOMAIN for one question`() {
        val response = DnsResponseFactory.blocked(query("bet-example.test"))
        val f = flags(response)
        assertThat(f and DnsConstants.FLAG_QR).isNotEqualTo(0)
        assertThat(f and DnsConstants.MASK_RCODE).isEqualTo(DnsConstants.RCODE_NAME_ERROR)
        assertThat(DnsParser.questionCount(response)).isEqualTo(1)
    }

    @Test
    fun `blocked echoes the original question`() {
        for (name in listOf("bet-example.test", "www.bet-example.test", "deep.sub.casino-example.test")) {
            val response = DnsResponseFactory.blocked(query(name))
            val question = DnsParser.parseQuestion(response)
            assertThat(question.name).isEqualTo(name)
            assertThat(question.type).isEqualTo(DnsConstants.TYPE_A)
        }
    }

    @Test
    fun `blocked survives a zero-question query`() {
        val bare = ByteArray(12) // all counts zero
        val response = DnsResponseFactory.blocked(bare)
        assertThat(DnsParser.headerId(response)).isEqualTo(0)
        assertThat(response.size).isEqualTo(12)
    }

    @Test
    fun `blocked handles a single label name`() {
        val response = DnsResponseFactory.blocked(query("localhost"))
        assertThat(DnsParser.parseQuestion(response).name).isEqualTo("localhost")
    }

    @Test
    fun `encodeName produces wire labels without trailing zero`() {
        val wire = DnsResponseFactory.encodeName("a.b.example")
        assertThat(wire.map { it.toInt() and 0xFF }).containsExactly(
            1, 'a'.code, 1, 'b'.code, 7, 'e'.code, 'x'.code, 'a'.code, 'm'.code, 'p'.code, 'l'.code, 'e'.code,
        ).inOrder()
    }

    @Test
    fun `encodeName round trips through the parser`() {
        val name = "www.bet-example.test"
        val message = ByteArray(12) + DnsResponseFactory.encodeName(name) + byteArrayOf(0) + ByteArray(4)
        val (parsed, _) = DnsParser.readName(message, 12)
        assertThat(parsed).isEqualTo(name)
    }

    @Test
    fun `header lays out id and flags for ncounts`() {
        val h = DnsResponseFactory.header(0x1234, 0x8000, 1, 2, 3, 4)
        assertThat(DnsParser.headerId(h)).isEqualTo(0x1234)
        assertThat(flags(h)).isEqualTo(0x8000)
        assertThat(DnsParser.questionCount(h)).isEqualTo(1)
    }
}