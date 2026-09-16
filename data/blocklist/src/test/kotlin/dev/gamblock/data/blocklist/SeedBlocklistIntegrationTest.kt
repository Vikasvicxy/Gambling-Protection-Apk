package dev.gamblock.data.blocklist

import com.google.common.truth.Truth.assertThat
import dev.gamblock.core.model.DecisionKind
import dev.gamblock.core.testing.NoOpLogger
import dev.gamblock.core.testing.TestDispatchersProvider
import dev.gamblock.protection.dns.DnsConstants
import dev.gamblock.protection.dns.DnsParser
import dev.gamblock.protection.dns.DnsResponseFactory
import dev.gamblock.protection.dns.InternetChecksum
import dev.gamblock.protection.dns.IpPacketCodec
import dev.gamblock.protection.domainengine.CompiledIndex
import dev.gamblock.protection.domainengine.DecisionEngine
import dev.gamblock.protection.domainengine.DomainIndexCompiler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * End-to-end test of the production path: real seed asset -> DomainIndexCompiler
 * -> DecisionEngine -> DNS wire response (mirrors ShieldVpnService.handlePacket).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SeedBlocklistIntegrationTest {

    private val dispatchers = TestDispatchersProvider(UnconfinedTestDispatcher())

    private suspend fun compileSeed(): CompiledIndex {
        val loader = SeedBlocklistLoader(RuntimeEnvironment.getApplication(), dispatchers, NoOpLogger)
        return DomainIndexCompiler.compile(loader.loadAsset(), sourceVersion = 1)
    }

    private fun dnsQuery(host: String, id: Int = 0x1234): ByteArray =
        DnsResponseFactory.header(id, DnsConstants.FLAG_RD, qd = 1, an = 0, ns = 0, ar = 0) +
            DnsResponseFactory.encodeName(host) +
            byteArrayOf(0, 0, 1, 0, 1)

    private val ipv4Server = byteArrayOf(10, 0, 2, 1)
    private val ipv6Server = ByteArray(16).also { it[15] = 2 }

    private fun buildInbound(family: Int, clientIp: ByteArray, clientPort: Int, serverIp: ByteArray, query: ByteArray): ByteArray {
        val udpLen = 8 + query.size
        return if (family == IpPacketCodec.IPV4) {
            val totalLen = 20 + udpLen
            val buf = ByteArray(totalLen)
            buf[0] = 0x45.toByte()
            buf[2] = ((totalLen shr 8) and 0xFF).toByte()
            buf[3] = (totalLen and 0xFF).toByte()
            buf[8] = 64.toByte(); buf[9] = IpPacketCodec.PROTOCOL_UDP.toByte()
            System.arraycopy(clientIp, 0, buf, 12, 4)
            System.arraycopy(serverIp, 0, buf, 16, 4)
            buf[20] = ((clientPort shr 8) and 0xFF).toByte()
            buf[21] = (clientPort and 0xFF).toByte()
            buf[22] = 0; buf[23] = 53
            buf[24] = ((udpLen shr 8) and 0xFF).toByte()
            buf[25] = (udpLen and 0xFF).toByte()
            System.arraycopy(query, 0, buf, 28, query.size)
            val check = InternetChecksum.compute(buf, 0, 20)
            buf[10] = ((check shr 8) and 0xFF).toByte()
            buf[11] = (check and 0xFF).toByte()
            buf
        } else {
            val buf = ByteArray(40 + udpLen)
            buf[0] = 0x60.toByte()
            buf[4] = ((udpLen shr 8) and 0xFF).toByte()
            buf[5] = (udpLen and 0xFF).toByte()
            buf[6] = IpPacketCodec.PROTOCOL_UDP.toByte(); buf[7] = 64.toByte()
            System.arraycopy(clientIp, 0, buf, 8, 16)
            System.arraycopy(serverIp, 0, buf, 24, 16)
            val off = 40
            buf[off] = ((clientPort shr 8) and 0xFF).toByte()
            buf[off + 1] = (clientPort and 0xFF).toByte()
            buf[off + 2] = 0; buf[off + 3] = 53
            buf[off + 4] = ((udpLen shr 8) and 0xFF).toByte()
            buf[off + 5] = (udpLen and 0xFF).toByte()
            buf[off + 6] = 0; buf[off + 7] = 0
            System.arraycopy(query, 0, buf, off + 8, query.size)
            buf
        }
    }

    /** Mirrors ShieldVpnService.handlePacket: parse → decide → blocked reply (null when ALLOW). */
    private fun runThroughVpnPath(
        engine: DecisionEngine,
        clientIp: ByteArray,
        clientPort: Int,
        family: Int,
        query: ByteArray,
    ): ByteArray? {
        val serverIp = if (family == IpPacketCodec.IPV4) ipv4Server else ipv6Server
        val inbound = buildInbound(family, clientIp, clientPort, serverIp, query)
        val udp = IpPacketCodec.parseUdp(inbound)!!
        val question = DnsParser.parseQuestion(udp.payload)
        val decision = engine.decide(question.name, scheduleActive = true)
        if (decision.decision != DecisionKind.BLOCK) return null
        return IpPacketCodec.craftUdpResponse(
            family = udp.family,
            sourceIp = udp.dstAddress,
            destinationIp = udp.srcAddress,
            destinationPort = udp.srcPort,
            dnsPayload = DnsResponseFactory.blocked(udp.payload),
        )
    }

    private fun rcode(headers: ByteArray): Int {
        val flags = ((headers[2].toInt() and 0xFF) shl 8) or (headers[3].toInt() and 0xFF)
        return flags and DnsConstants.MASK_RCODE
    }

    @Test
    fun `seed compiles to the expected rule counts`() = runTest {
        val compiled = compileSeed()
        assertThat(compiled.enabledCount).isEqualTo(12)
        assertThat(compiled.allowlistCount).isEqualTo(4)
        assertThat(compiled.sourceVersion).isEqualTo(1)
        assertThat(compiled.digest).hasLength(64)
    }

    @Test
    fun `seed blocked domain and its subdomains are blocked`() = runTest {
        val engine = DecisionEngine(compileSeed().index)
        assertThat(engine.decide("bet-example.test", true).decision).isEqualTo(DecisionKind.BLOCK)
        assertThat(engine.decide("www.bet-example.test", true).decision).isEqualTo(DecisionKind.BLOCK)
        assertThat(engine.decide("casino-example.test", true).decision).isEqualTo(DecisionKind.BLOCK)
        assertThat(engine.decide("ringfence.test", true).decision).isEqualTo(DecisionKind.BLOCK)
    }

    @Test
    fun `seed allowlisted domain and its subdomains are allowed`() = runTest {
        val engine = DecisionEngine(compileSeed().index)
        assertThat(engine.decide("safe-example.test", true).decision).isEqualTo(DecisionKind.ALLOW)
        assertThat(engine.decide("www.safe-example.test", true).decision).isEqualTo(DecisionKind.ALLOW)
        assertThat(engine.decide("maps-example.test", true).decision).isEqualTo(DecisionKind.ALLOW)
    }

    @Test
    fun `unrelated domain is allowed`() = runTest {
        val engine = DecisionEngine(compileSeed().index)
        assertThat(engine.decide("example.test", true).decision).isEqualTo(DecisionKind.ALLOW)
        assertThat(engine.decide("google.com", true).decision).isEqualTo(DecisionKind.ALLOW)
    }

    @Test
    fun `IPv4 DNS query for a blocked seed domain gets an NXDOMAIN reply echoing the question`() = runTest {
        val engine = DecisionEngine(compileSeed().index)
        val client = byteArrayOf(10, 0, 2, 15)
        val reply = runThroughVpnPath(engine, client, 0xA1B1, IpPacketCodec.IPV4, dnsQuery("bet-example.test", id = 0xBEEF))!!
        val udp = IpPacketCodec.parseUdp(reply)!!
        assertThat(udp.srcPort).isEqualTo(53)
        assertThat(udp.dstPort).isEqualTo(0xA1B1)
        assertThat(DnsParser.headerId(udp.payload)).isEqualTo(0xBEEF)
        assertThat(rcode(udp.payload)).isEqualTo(DnsConstants.RCODE_NAME_ERROR)
        assertThat(DnsParser.questionCount(udp.payload)).isEqualTo(1)
        assertThat(DnsParser.parseQuestion(udp.payload).name).isEqualTo("bet-example.test")
    }

    @Test
    fun `IPv6 DNS query for a blocked seed domain gets an NXDOMAIN reply`() = runTest {
        val engine = DecisionEngine(compileSeed().index)
        val client = ByteArray(16).also { it[15] = 1 }
        val reply = runThroughVpnPath(engine, client, 0x35, IpPacketCodec.IPV6, dnsQuery("www.bet-example.test"))!!
        val udp = IpPacketCodec.parseUdp(reply)!!
        assertThat(udp.family).isEqualTo(IpPacketCodec.IPV6)
        assertThat(rcode(udp.payload)).isEqualTo(DnsConstants.RCODE_NAME_ERROR)
        assertThat(DnsParser.parseQuestion(udp.payload).name).isEqualTo("www.bet-example.test")
    }

    @Test
    fun `IPv6 DNS query for an allowlisted seed domain is not answered`() = runTest {
        val engine = DecisionEngine(compileSeed().index)
        val client = ByteArray(16).also { it[15] = 1 }
        val reply = runThroughVpnPath(engine, client, 0x35, IpPacketCodec.IPV6, dnsQuery("safe-example.test"))
        assertThat(reply).isNull()
    }
}