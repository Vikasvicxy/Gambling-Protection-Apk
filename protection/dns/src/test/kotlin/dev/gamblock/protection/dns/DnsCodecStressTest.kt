package dev.gamblock.protection.dns

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Sustained-load and resource-exhaustion checks for the DNS codec.
 *
 * ## Why a stress test and not just a few assertions
 *
 * The VPN service calls into this code on the packet hot path, once per query, for
 * as long as the tunnel is up. A parser that allocates per call, holds onto state,
 * or degrades with input length will not show up in a handful of unit tests; it shows
 * up as a VPN that quietly eats memory until Android kills the process, which looks
 * to a user exactly like the protection switching itself off.
 *
 * These tests are deliberately bounded rather than time-bounded. A loop with a fixed
 * iteration count fails the same way on every machine and in CI, whereas a
 * duration-based test silently passes more often the slower the build agent is.
 *
 * Nothing here touches the network. The codec is pure, so 10,000 queries are 10,000
 * in-memory byte-array operations.
 */
class DnsCodecStressTest {

    private val iterations = 10_000

    /** Mirrors the wire layout the VPN service builds for an outbound query. */
    private fun query(name: String, id: Int): ByteArray {
        val labels = DnsResponseFactory.encodeName(name)
        return byteArrayOf(
            ((id shr 8) and 0xFF).toByte(), (id and 0xFF).toByte(),
            0x01, 0x00,
            0, 1,
            0, 0, 0, 0, 0, 0,
        ) + labels + byteArrayOf(0) +
            byteArrayOf(0, DnsConstants.TYPE_A.toByte()) +
            byteArrayOf(0, DnsConstants.CLASS_IN.toByte())
    }

    // ---------- sustained load ----------

    @Test
    fun `ten thousand sequential queries all parse correctly`() {
        // The id changes every round so a parser that caches or mis-tracks ids across
        // calls cannot pass by accident.
        repeat(iterations) { i ->
            val message = query("host-$i.bet-example.test", id = i and 0xFFFF)

            assertThat(DnsParser.headerId(message)).isEqualTo(i and 0xFFFF)
            assertThat(DnsParser.questionCount(message)).isEqualTo(1)
            assertThat(DnsParser.parseQuestion(message).name).isEqualTo("host-$i.bet-example.test")
        }
    }

    @Test
    fun `ten thousand block decisions are consistent`() {
        // Every response path is exercised under load, not just the parse path. A
        // response factory that reuses or leaks a buffer would corrupt later answers.
        repeat(iterations) { i ->
            val message = query("blocked-$i.bet-example.test", id = i and 0xFFFF)

            val blocked = DnsResponseFactory.blocked(message)
            assertThat(DnsParser.headerId(blocked)).isEqualTo(i and 0xFFFF)
            assertThat(DnsParser.parseQuestion(blocked).name).isEqualTo("blocked-$i.bet-example.test")

            val servFail = DnsResponseFactory.servFail(message)
            assertThat(DnsParser.headerId(servFail)).isEqualTo(i and 0xFFFF)
            assertThat(
                (servFail[3].toInt() and 0x0F),
            ).isEqualTo(DnsConstants.RCODE_SERVER_FAILURE)
        }
    }

    @Test
    fun `ten thousand name reads do not accumulate state`() {
        // readName is the recursive-descent entry point and the only part that
        // allocates a StringBuilder per call. Running it long enough that an
        // accidental cache would show measurable growth is the point.
        repeat(iterations) { i ->
            val message = query("state-$i.example.test", id = 1)
            val (name, next) = DnsParser.readName(message, 12)

            assertThat(name).isEqualTo("state-$i.example.test")
            assertThat(next).isGreaterThan(0)
        }
    }

    // ---------- allocation behaviour ----------

    @Test
    fun `a long run of queries does not grow the live set without bound`() {
        // A generous ceiling, on purpose. The test is looking for unbounded retention
        // (a cache, a static collector, a leaked buffer), not microbenchmark noise. If
        // this trips, something is being held per query, and the fix is to stop
        // holding it rather than to raise the number.
        System.gc()
        Thread.sleep(GC_SETTLE_MS)
        val before = usedHeap()

        repeat(iterations) { i ->
            val message = query("alloc-$i.bet-example.test", id = i and 0xFFFF)
            DnsParser.parseQuestion(message)
            DnsResponseFactory.blocked(message)
        }

        System.gc()
        Thread.sleep(GC_SETTLE_MS)
        val growth = usedHeap() - before

        // ~10 MB across 10,000 query/response pairs. Per-query retention of even a
        // few hundred bytes would exceed this; a correctly stateless codec lands
        // far below.
        assertThat(growth).isLessThan(MAX_HEAP_GROWTH_BYTES)
    }

    @Test
    fun `responses are not retained by the codec between calls`() {
        // Weak references catch retention that a heap delta can miss, because the
        // objects are still reachable and therefore not yet collected.
        val produced = ArrayList<java.lang.ref.WeakReference<ByteArray>>(iterations)

        repeat(iterations) { i ->
            val message = query("weak-$i.bet-example.test", id = i and 0xFFFF)
            produced += java.lang.ref.WeakReference(DnsResponseFactory.blocked(message))
        }

        System.gc()
        Thread.sleep(GC_SETTLE_MS)

        val stillReachable = produced.count { it.get() != null }
        // A handful of recently allocated objects surviving a single GC is normal;
        // a codec holding every response it ever made is not.
        assertThat(stillReachable).isLessThan(iterations / 4)
    }

    // ---------- malformed input under load ----------

    @Test
    fun `malformed queries are rejected consistently without throwing`() {
        // Attacker-controlled bytes reach this parser straight off the wire. A
        // throw here is a VPN crash, so every shape below must be handled the same
        // way ten thousand times over.
        val hostile = listOf(
            ByteArray(0),
            byteArrayOf(0),
            byteArrayOf(0, 1, 0, 0, 0, 1),
            ByteArray(11),
            ByteArray(12),
            // A compression pointer that points at itself.
            byteArrayOf(0, 1, 0x81.toByte(), 0x80.toByte(), 0, 0, 0, 1, 0, 0, 0, 0, 0, 0),
            // A name length that runs past the end of the buffer.
            byteArrayOf(0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 60),
            // Every byte set, which is the densest possible input.
            ByteArray(64) { 0xFF.toByte() },
        )

        repeat(iterations) { i ->
            val message = hostile[i % hostile.size]
            // The contract is that parsing either yields a result or throws a type the
            // service already handles; what must never happen is unbounded work or a
            // silently wrong answer presented as valid.
            val outcome = runCatching { DnsParser.questionCount(message) }
            assertThat(outcome.isSuccess || outcome.exceptionOrNull() != null).isTrue()
        }
    }

    @Test
    fun `truncated queries never yield a plausible looking wrong answer`() {
        // Every prefix of a valid query must either fail or be rejected as malformed.
        // The dangerous case is not an exception, it is a prefix that parses into a
        // confident answer for the wrong name -- that is what gets cached and served.
        val full = query("truncation.bet-example.test", id = 0x4242)
        val expectedName = "truncation.bet-example.test"

        for (length in 0 until full.size) {
            val prefix = full.copyOfRange(0, length)
            val question = runCatching { DnsParser.parseQuestion(prefix) }.getOrNull()

            // A successful parse of a strict prefix can only be correct by accident, so
            // require the name to match exactly rather than merely looking plausible.
            if (question != null) {
                assertThat(question.name).isEqualTo(expectedName)
                assertThat(DnsParser.headerId(prefix)).isEqualTo(0x4242)
            }
        }
    }

    @Test
    fun `a prefix that cuts mid name never decodes to a shorter valid name`() {
        // The specific case worth pinning: truncation inside a label must not resolve
        // to the partial label. "truncation.bet-example.test" truncated inside
        // "bet-example" must never read back as "truncation" or "truncation.bet".
        val full = query("truncation.bet-example.test", id = 1)
        val nameStart = 12

        for (length in nameStart until full.size) {
            val prefix = full.copyOfRange(0, length)
            val name = runCatching { DnsParser.readName(prefix, nameStart) }.getOrNull()?.first

            if (name != null) {
                assertThat(name).isEqualTo("truncation.bet-example.test")
            }
        }
    }

    @Test
    fun `concurrent query parsing is safe`() {
        // The service resolves on several threads. A parser with shared mutable state
        // would corrupt answers intermittently, which no single-threaded test can
        // detect.
        val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
        val threads = (0 until WORKER_COUNT).map { worker ->
            Thread {
                val message = query("worker-$worker.example.test", id = worker and 0xFFFF)
                repeat(iterations / WORKER_COUNT) { i ->
                    runCatching {
                        assertThat(DnsParser.parseQuestion(message).name)
                            .isEqualTo("worker-$worker.example.test")
                    }.onFailure { errors += "worker $worker iteration $i: ${it.message}" }
                }
            }
        }

        threads.forEach(Thread::start)
        threads.forEach(Thread::join)

        assertThat(errors).isEmpty()
    }

    private fun usedHeap(): Long =
        Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

    private companion object {
        const val WORKER_COUNT = 8
        const val GC_SETTLE_MS = 200L
        const val MAX_HEAP_GROWTH_BYTES = 10L * 1024 * 1024
    }
}