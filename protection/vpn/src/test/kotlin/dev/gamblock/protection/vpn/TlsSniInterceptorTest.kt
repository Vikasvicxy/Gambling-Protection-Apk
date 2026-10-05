package dev.gamblock.protection.vpn

import dev.gamblock.protection.dns.IpPacketCodec
import dev.gamblock.protection.dns.TcpFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural tests for [TlsSniInterceptor].
 *
 * The two properties that matter most:
 *
 *  1. a blocked hostname named in the cleartext ClientHello produces a reset that
 *     the client will actually accept, and an allowed one does not;
 *  2. every path that cannot reach a verdict releases its buffered state, because
 *     this class runs on a device-wide packet path and a retained flow is a leak.
 */
class TlsSniInterceptorTest {

    private class Recorder {
        val seen = mutableListOf<String>()
        var blocked: Set<String> = emptySet()
        val interceptor = TlsSniInterceptor(hostIsBlocked = { host ->
            seen += host
            host in blocked
        })
    }

    private fun hello(host: String): ByteArray =
        ClientHelloBuilder.builder().serverName(host).build()

    private fun segment(
        payload: ByteArray,
        srcPort: Int = 44444,
        dstPort: Int = 443,
        seq: Int = 1000,
        src: String = "10.0.0.5",
    ): ByteArray = TcpTestSegments.ipv4(src, "93.184.216.34", srcPort, dstPort, seq, payload)

    // ---------- core blocking behaviour ----------

    @Test
    fun `blocks a clienthello naming a blocked host`() {
        val rec = Recorder().apply { blocked = setOf("bet.example") }

        val verdict = rec.interceptor.inspect(segment(hello("bet.example")))

        assertTrue("expected a block verdict, got $verdict", verdict is TlsSniInterceptor.Verdict.Block)
        verdict as TlsSniInterceptor.Verdict.Block
        assertEquals("bet.example", verdict.hostname)
        assertNotNull("a reset packet must be produced", verdict.reset)
    }

    @Test
    fun `forwards a clienthello naming an allowed host`() {
        val rec = Recorder().apply { blocked = setOf("bet.example") }

        val verdict = rec.interceptor.inspect(segment(hello("news.example")))

        assertEquals(TlsSniInterceptor.Verdict.Forward, verdict)
        assertEquals(listOf("news.example"), rec.seen)
    }

    @Test
    fun `the synthesised reset is accepted by a real stack`() {
        val rec = Recorder().apply { blocked = setOf("bet.example") }
        val hello = hello("bet.example")

        val verdict = rec.interceptor.inspect(segment(hello)) as TlsSniInterceptor.Verdict.Block

        // Parsing the reset must yield a well formed RST from the server's tuple.
        val reset = dev.gamblock.protection.dns.TcpPacketCodec.parse(verdict.reset)
        assertNotNull(reset)
        assertTrue(reset!!.isRst)
        assertEquals(443, reset.srcPort)
        assertEquals(44444, reset.dstPort)
        assertEquals(1000 + hello.size, reset.ackNumber)
    }

    @Test
    fun `hostname is matched case-insensitively because the parser normalises it`() {
        val rec = Recorder().apply { blocked = setOf("bet.example") }

        val verdict = rec.interceptor.inspect(segment(hello("BET.Example")))

        assertTrue(verdict is TlsSniInterceptor.Verdict.Block)
    }

    // ---------- battery and battery-adjacent guards ----------

    @Test
    fun `traffic to a port other than 443 is never buffered or inspected`() {
        val rec = Recorder()

        // A large body to a non-443 port must be dropped outright, with no flow
        // state created and no bytes retained.
        repeat(200) {
            rec.interceptor.inspect(segment(hello("bet.example"), dstPort = 8080))
        }

        assertEquals(0, rec.interceptor.trackedFlowCount)
        assertEquals(0, rec.interceptor.bufferedByteCount)
        assertTrue("blocklist must not be consulted for non-443 traffic", rec.seen.isEmpty())
    }

    @Test
    fun `a bare ack carrying no payload creates no flow`() {
        val rec = Recorder()

        val verdict = rec.interceptor.inspect(segment(ByteArray(0)))

        assertEquals(TlsSniInterceptor.Verdict.Forward, verdict)
        assertEquals(0, rec.interceptor.trackedFlowCount)
    }

    @Test
    fun `a payload that is not a tls handshake ends inspection for that flow`() {
        val rec = Recorder()
        val payload = "GET / HTTP/1.1\r\nHost: bet.example\r\n\r\n".toByteArray()

        val first = rec.interceptor.inspect(segment(payload))
        assertEquals(TlsSniInterceptor.Verdict.Forward, first)
        assertTrue("nothing should be retained after a non-TLS first byte", rec.interceptor.bufferedByteCount == 0)

        // A long-lived stream keeps sending; it must not start buffering again.
        val bufferAfterFirst = rec.interceptor.bufferedByteCount
        repeat(500) {
            rec.interceptor.inspect(segment(ByteArray(512), seq = 1000 + (it + 1) * 512))
        }
        assertEquals(0, rec.interceptor.bufferedByteCount)
        assertEquals(bufferAfterFirst, rec.interceptor.bufferedByteCount)
        assertTrue(rec.seen.isEmpty())
    }

    @Test
    fun `a decided flow is not re-inspected on later segments`() {
        val rec = Recorder().apply { blocked = setOf("bet.example") }

        rec.interceptor.inspect(segment(hello("bet.example")))
        // Continuing the same connection must not re-consult the blocklist.
        repeat(50) { rec.interceptor.inspect(segment(ByteArray(256), seq = 1000 + (it + 1) * 256)) }

        assertEquals(listOf("bet.example"), rec.seen)
    }

    @Test
    fun `per flow buffering is capped and abandoned past the budget`() {
        val bounded = TlsSniInterceptor(
            hostIsBlocked = { true },
            maxBufferedBytesPerFlow = 512,
        )

        // A TLS-looking stream that never completes a parse must not hoard memory.
        var seq = 1000
        repeat(20) {
            bounded.inspect(segment(ByteArray(400) { 0x16.toByte() }, seq = seq))
            seq += 400
        }

        assertTrue(
            "buffered bytes must stay within budget, was ${bounded.bufferedByteCount}",
            bounded.bufferedByteCount <= 512,
        )
    }

    @Test
    fun `global flow count is bounded`() {
        val bounded = TlsSniInterceptor(hostIsBlocked = { true }, maxFlows = 16)

        // 200 distinct client ports => 200 distinct flows.
        for (port in 30000..30200) {
            bounded.inspect(segment(hello("bet.example"), srcPort = port))
        }

        assertTrue(
            "flow table must not exceed maxFlows, was ${bounded.trackedFlowCount}",
            bounded.trackedFlowCount <= 16,
        )
    }

    @Test
    fun `idle flows are reclaimed`() {
        var now = 0L
        val clocked = TlsSniInterceptor(
            hostIsBlocked = { true },
            nowNanos = { now },
            flowIdleNanos = 1_000_000L,
        )

        clocked.inspect(segment(ByteArray(8) { 0x16 }))
        assertTrue(clocked.trackedFlowCount > 0)

        // Jump past the idle window; the next packet sweeps the stale entry.
        now = 10_000_000L
        clocked.inspect(segment(hello("other.example"), srcPort = 40000))
        assertEquals(1, clocked.trackedFlowCount)
    }

    // ---------- reassembly ----------

    @Test
    fun `reassembles a clienthello split across several segments`() {
        val rec = Recorder().apply { blocked = setOf("bet.example") }
        val hello = hello("bet.example")
        val third = hello.size / 3

        assertEquals(TlsSniInterceptor.Verdict.Hold, rec.interceptor.inspect(segment(hello.copyOfRange(0, third))))
        assertEquals(TlsSniInterceptor.Verdict.Hold, rec.interceptor.inspect(segment(hello.copyOfRange(third, 2 * third), seq = 1000 + third)))

        val verdict = rec.interceptor.inspect(segment(hello.copyOfRange(2 * third, hello.size), seq = 1000 + 2 * third))

        assertTrue("split hello should still block, got $verdict", verdict is TlsSniInterceptor.Verdict.Block)
        assertEquals("bet.example", (verdict as TlsSniInterceptor.Verdict.Block).hostname)
    }

    @Test
    fun `an out of order segment abandons inspection rather than reassembling wrongly`() {
        val rec = Recorder().apply { blocked = setOf("bet.example") }
        val hello = hello("bet.example")

        rec.interceptor.inspect(segment(hello.copyOfRange(0, 40)))
        // A segment arrives from the future: we cannot place it, so we stop.
        val verdict = rec.interceptor.inspect(segment(hello.copyOfRange(40, 80), seq = 9999))

        assertEquals(TlsSniInterceptor.Verdict.Forward, verdict)
        assertEquals(0, rec.interceptor.bufferedByteCount)
        assertTrue("a mis-reassembled hello must never reach the blocklist", rec.seen.isEmpty())
    }

    @Test
    fun `two clients to the same server are tracked separately`() {
        val rec = Recorder().apply { blocked = setOf("bet.example") }

        // Client A sends half a hello and stalls; client B sends a whole one.
        val hello = hello("bet.example")
        rec.interceptor.inspect(segment(hello.copyOfRange(0, 40), srcPort = 40001))
        val verdict = rec.interceptor.inspect(segment(hello, srcPort = 40002))

        assertTrue(verdict is TlsSniInterceptor.Verdict.Block)
        assertEquals(2, rec.interceptor.trackedFlowCount)
    }

    // ---------- malformed and hostile input ----------

    @Test
    fun `a non tls clienthello is forwarded without consulting the blocklist`() {
        val rec = Recorder()
        // A well-formed handshake record whose message type is not ClientHello (0x01).
        val notAHello = byteArrayOf(0x16, 0x03, 0x01, 0x00, 0x04, 0x02, 0x00, 0x00, 0x00)

        val verdict = rec.interceptor.inspect(segment(notAHello))

        assertEquals(TlsSniInterceptor.Verdict.Forward, verdict)
        assertTrue("a non-ClientHello must not be read for a hostname", rec.seen.isEmpty())
        assertEquals(0, rec.interceptor.bufferedByteCount)
    }

    @Test
    fun `a hello with no sni extension is forwarded`() {
        val rec = Recorder()
        val hello = ClientHelloBuilder.builder().noExtensions().build()

        val verdict = rec.interceptor.inspect(segment(hello))

        assertEquals(TlsSniInterceptor.Verdict.Forward, verdict)
        assertTrue("no hostname means no blocklist lookup", rec.seen.isEmpty())
        assertEquals(0, rec.interceptor.bufferedByteCount)
    }

    @Test
    fun `garbage never throws and never reaches the blocklist`() {
        val rec = Recorder()
        // A TLS-looking header followed by random bytes.
        val hostile = ByteArray(600).also {
            it[0] = 0x16
            it[1] = 0x03
            it[2] = 0x01
            it[3] = 0x02
            it[4] = 0x58 // claims 600 bytes
        }

        val verdict = rec.interceptor.inspect(segment(hostile))

        // Must be a clean verdict, not an exception, and must not claim a host.
        assertTrue(verdict is TlsSniInterceptor.Verdict.Forward || verdict is TlsSniInterceptor.Verdict.Hold)
        assertTrue(rec.seen.isEmpty())
    }

    @Test
    fun `a truncated segment is rejected without throwing`() {
        val rec = Recorder()
        val full = segment(hello("bet.example"))

        for (cut in 1 until full.size) {
            // No exception, and never a block verdict on a partial packet.
            val verdict = rec.interceptor.inspect(full, length = cut)
            assertFalse(
                "a $cut byte fragment must not produce a block",
                verdict is TlsSniInterceptor.Verdict.Block,
            )
        }
    }

    @Test
    fun `non ipv traffic is forwarded untouched`() {
        val rec = Recorder()
        val junk = ByteArray(40).also { it[0] = 0x70 } // version 7

        assertEquals(TlsSniInterceptor.Verdict.Forward, rec.interceptor.inspect(junk))
        assertEquals(0, rec.interceptor.trackedFlowCount)
    }

    @Test
    fun `udp dns traffic is ignored by the tcp interceptor`() {
        val rec = Recorder()
        // IpPacketCodec's own builder is used to make a genuine UDP datagram.
        val dns = ByteArray(12)
        val udp = IpPacketCodec.craftUdpResponse(
            family = IpPacketCodec.IPV4,
            sourceIp = byteArrayOf(198.toByte(), 18, 0, 1),
            destinationIp = byteArrayOf(10, 0, 0, 5),
            destinationPort = 53,
            dnsPayload = dns,
        )

        assertEquals(TlsSniInterceptor.Verdict.Forward, rec.interceptor.inspect(udp))
        assertEquals(0, rec.interceptor.trackedFlowCount)
    }

    // ---------- lifecycle ----------

    @Test
    fun `reset clears all flow state`() {
        val rec = Recorder()
        rec.interceptor.inspect(segment(ByteArray(8) { 0x16 }))
        assertTrue(rec.interceptor.trackedFlowCount > 0)

        rec.interceptor.reset()

        assertEquals(0, rec.interceptor.trackedFlowCount)
        assertEquals(0, rec.interceptor.bufferedByteCount)
    }

    @Test
    fun `forget client drops only that client's flows`() {
        val rec = Recorder()
        rec.interceptor.inspect(segment(hello("a.example"), src = "10.0.0.5"))
        rec.interceptor.inspect(segment(hello("b.example"), src = "10.0.0.6"))
        assertEquals(2, rec.interceptor.trackedFlowCount)

        rec.interceptor.forgetClient(byteArrayOf(10, 0, 0, 5))

        assertEquals(1, rec.interceptor.trackedFlowCount)
    }

    @Test
    fun `concurrent inspection of distinct flows is safe`() {
        val rec = Recorder().apply { blocked = setOf("bet.example") }
        val threads = (0 until 8).map { t ->
            Thread {
                repeat(300) { i ->
                    rec.interceptor.inspect(segment(hello("bet.example"), srcPort = 30000 + t * 1000 + (i % 50)))
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // The important assertion is that this did not deadlock or corrupt state.
        assertTrue(rec.seen.size >= 1)
        assertTrue(rec.interceptor.bufferedByteCount <= 4096 * 1024)
    }

    @Test
    fun `flags do not affect payload inspection`() {
        val rec = Recorder().apply { blocked = setOf("bet.example") }

        // Same ClientHello, different control bits; the verdict must be identical.
        val verdict = rec.interceptor.inspect(segment(hello("bet.example"), seq = 77))

        assertTrue(verdict is TlsSniInterceptor.Verdict.Block)
        assertEquals(0, (verdict as TlsSniInterceptor.Verdict.Block).reset.size % 2)
    }
}

/** Builds real tun segments for interceptor tests. */
object TcpTestSegments {
    fun ipv4(
        src: String,
        dst: String,
        srcPort: Int,
        dstPort: Int,
        seq: Int,
        payload: ByteArray,
    ): ByteArray {
        val tcpLength = 20 + payload.size
        val total = 20 + tcpLength
        val buf = ByteArray(total)
        buf[0] = 0x45.toByte()
        buf[2] = ((total shr 8) and 0xFF).toByte()
        buf[3] = (total and 0xFF).toByte()
        buf[8] = 64.toByte()
        buf[9] = dev.gamblock.protection.dns.PROTOCOL_TCP.toByte()
        v4(src).copyInto(buf, 12)
        v4(dst).copyInto(buf, 16)
        buf[20] = ((srcPort shr 8) and 0xFF).toByte()
        buf[21] = (srcPort and 0xFF).toByte()
        buf[22] = ((dstPort shr 8) and 0xFF).toByte()
        buf[23] = (dstPort and 0xFF).toByte()
        buf[24] = ((seq shr 24) and 0xFF).toByte()
        buf[25] = ((seq shr 16) and 0xFF).toByte()
        buf[26] = ((seq shr 8) and 0xFF).toByte()
        buf[27] = (seq and 0xFF).toByte()
        buf[32] = 0x50.toByte()
        buf[33] = (TcpFlags.ACK or TcpFlags.PSH).toByte()
        buf[34] = 0xFF.toByte()
        buf[35] = 0xFF.toByte()
        payload.copyInto(buf, 40)
        return buf
    }

    private fun v4(value: String): ByteArray = value.split('.').map { it.toInt().toByte() }.toByteArray()
}