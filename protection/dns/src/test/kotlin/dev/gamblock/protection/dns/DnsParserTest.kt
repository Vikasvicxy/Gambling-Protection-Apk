package dev.gamblock.protection.dns

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class DnsParserTest {

    private fun query(
        name: String,
        type: Int = DnsConstants.TYPE_A,
        clazz: Int = DnsConstants.CLASS_IN,
        id: Int = 0x1234,
    ): ByteArray {
        val labels = DnsResponseFactory.encodeName(name)
        return byteArrayOf(
            ((id shr 8) and 0xFF).toByte(), (id and 0xFF).toByte(),
            1, 0, // RD
            0, 1,
            0, 0, 0, 0, 0, 0,
        ) + labels + byteArrayOf(0) +
            byteArrayOf(((type shr 8) and 0xFF).toByte(), (type and 0xFF).toByte()) +
            byteArrayOf(((clazz shr 8) and 0xFF).toByte(), (clazz and 0xFF).toByte())
    }

    @Test
    fun `parses a simple question`() {
        val q = DnsParser.parseQuestion(query("www.bet-example.test"))
        assertThat(q.name).isEqualTo("www.bet-example.test")
        assertThat(q.type).isEqualTo(DnsConstants.TYPE_A)
        assertThat(q.recordClass).isEqualTo(DnsConstants.CLASS_IN)
    }

    @Test
    fun `parses AAAA and non-IN classes`() {
        val aaaa = DnsParser.parseQuestion(query("v6.example", type = DnsConstants.TYPE_AAAA))
        assertThat(aaaa.type).isEqualTo(DnsConstants.TYPE_AAAA)
        val ch = DnsParser.parseQuestion(query("chaos.example", clazz = DnsConstants.CLASS_CH))
        assertThat(ch.recordClass).isEqualTo(DnsConstants.CLASS_CH)
    }

    @Test
    fun `readName handles compression pointers`() {
        // Two names: full at offset 12, second is a pointer back to 12.
        val full = DnsResponseFactory.encodeName("example.com")
        val message = ByteArray(12) + full + byteArrayOf(0) + byteArrayOf(0xC0.toByte(), 12)
        val (name, next) = DnsParser.readName(message, message.size - 2)
        assertThat(name).isEqualTo("example.com")
        assertThat(next).isEqualTo(message.size)
    }

    @Test
    fun `readName resolves an ordinary name and continuation offset`() {
        val full = DnsResponseFactory.encodeName("a.b.example")
        val message = ByteArray(12) + full + byteArrayOf(0) + ByteArray(4)
        val (name, next) = DnsParser.readName(message, 12)
        assertThat(name).isEqualTo("a.b.example")
        assertThat(next).isEqualTo(12 + full.size + 1)
    }

    @Test
    fun `readName rejects truncated label`() {
        val message = byteArrayOf(0x05, 'a'.code.toByte())
        assertThrows(IllegalArgumentException::class.java) {
            DnsParser.readName(message, 0)
        }
    }

    @Test
    fun `readName rejects truncated compression pointer`() {
        val message = byteArrayOf(0xC0.toByte())
        assertThrows(IllegalArgumentException::class.java) {
            DnsParser.readName(message, 0)
        }
    }

    @Test
    fun `readName rejects unsupported label types`() {
        val message = byteArrayOf(0x40, 0x00, 0x00) // 01xx label type, illegal
        assertThrows(IllegalArgumentException::class.java) {
            DnsParser.readName(message, 0)
        }
    }

    @Test
    fun `readName detects pointer loops`() {
        val message = byteArrayOf(0xC0.toByte(), 0x00) // always points to itself
        assertThrows(IllegalArgumentException::class.java) {
            DnsParser.readName(message, 0)
        }
    }

    @Test
    fun `parseQuestion rejects packets too short for a question`() {
        assertThrows(IllegalArgumentException::class.java) {
            DnsParser.parseQuestion(ByteArray(8))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DnsParser.parseQuestion(ByteArray(12) + byteArrayOf(1, 'a'.code.toByte()))
        }
    }

    @Test
    fun `headerId and questionCount read the raw bytes`() {
        val q = query("example.test", id = 0xBEEF)
        assertThat(DnsParser.headerId(q)).isEqualTo(0xBEEF)
        assertThat(DnsParser.questionCount(q)).isEqualTo(1)
    }

    @Test
    fun `headerId handles zero id`() {
        assertThat(DnsParser.headerId(ByteArray(12))).isEqualTo(0)
    }
}