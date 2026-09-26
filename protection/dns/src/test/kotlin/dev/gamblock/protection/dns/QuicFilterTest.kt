package dev.gamblock.protection.dns

import com.google.common.truth.Truth.assertThat
import dev.gamblock.core.model.EncryptedDnsPolicy
import org.junit.Test

class QuicFilterTest {

    private fun udp(dstPort: Int) = UdpPacket(
        family = 4,
        srcAddress = ByteArray(4) { 10 },
        dstAddress = ByteArray(4) { 1 },
        srcPort = 51_234,
        dstPort = dstPort,
        payload = ByteArray(0),
    )

    @Test
    fun `udp 443 is dropped so browsers fall back to TCP`() {
        assertThat(QuicFilter.shouldDrop(udp(dstPort = 443))).isTrue()
        assertThat(QuicFilter.classifyPort(443)).isEqualTo(QuicFilter.PortVerdict.QUIC_DROP)
    }

    @Test
    fun `the filter can be turned off entirely`() {
        assertThat(QuicFilter.shouldDrop(udp(dstPort = 443), blockEncryptedBrowsers = false)).isFalse()
    }

    @Test
    fun `ordinary web ports are untouched`() {
        listOf(80, 8080, 8443, 3000, 1234).forEach { port ->
            assertThat(QuicFilter.shouldDrop(udp(dstPort = port))).isFalse()
            assertThat(QuicFilter.classifyPort(port)).isEqualTo(QuicFilter.PortVerdict.PASS)
        }
    }

    @Test
    fun `non gambling services on other udp ports are not dropped`() {
        // QUIC is also used by video calls and game traffic; only UDP 443 to a
        // filtered destination is in scope, which the port rule approximates.
        listOf(3478, 51820, 500, 1194).forEach { port ->
            assertThat(QuicFilter.shouldDrop(udp(dstPort = port))).isFalse()
        }
    }

    @Test
    fun `source port 443 is not mistaken for the destination port`() {
        val reply = UdpPacket(
            family = 4,
            srcAddress = ByteArray(4) { 1 },
            dstAddress = ByteArray(4) { 10 },
            srcPort = 443,
            dstPort = 51_234,
            payload = ByteArray(0),
        )

        assertThat(QuicFilter.shouldDrop(reply)).isFalse()
    }

    @Test
    fun `malformed packets are passed rather than dropped`() {
        val garbage = ByteArray(12) { 0 }

        assertThat(QuicFilter.shouldDropPacket(garbage)).isFalse()
        assertThat(QuicFilter.shouldDropPacket(ByteArray(0))).isFalse()
    }

    @Test
    fun `the port rule matches the shared encrypted dns policy`() {
        assertThat(EncryptedDnsPolicy.isQuicDestinationPort(443)).isTrue()
        assertThat(EncryptedDnsPolicy.isQuicDestinationPort(80)).isFalse()
    }
}
