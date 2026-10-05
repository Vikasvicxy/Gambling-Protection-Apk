package dev.gamblock.protection.vpn

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Sustained-load checks for the DoH resolver and its transport.
 *
 * ## Why this is not the same as DohUpstreamResolverTest
 *
 * The existing suite asserts protocol correctness against a fake transport. This
 * one runs the real [OkHttpDohTransport] against a loopback HTTP server for thousands
 * of requests, because the failure modes worth catching are the ones a fake cannot
 * reproduce: sockets that are never closed, threads that never exit, connection pools
 * that grow without limit, and a call that hangs instead of throwing.
 *
 * On a device any of those ends the same way -- Android kills the VPN process for
 * leaking, and the user sees protection silently drop.
 *
 * ## Offline by construction
 *
 * The server is a [ServerSocket] bound to the loopback interface inside this test.
 * No external host is contacted, no DNS is performed by the machine, and the resolver
 * is pointed at `127.0.0.1`. This suite is safe to run with networking disabled.
 */
class DohTransportStressTest {

    private lateinit var server: LoopbackDohServer
    private lateinit var transport: OkHttpDohTransport

    @Before
    fun setUp() {
        server = LoopbackDohServer()
        server.start()
        // connectTimeout is long enough that a correct run never trips it, and short
        // enough that the deadlock test finishes rather than hanging the build.
        transport = OkHttpDohTransport(
            connectTimeoutMs = 2_000,
            readTimeoutMs = 2_000,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun url() = server.baseUrl()

    // ---------- sustained load ----------

    @Test
    fun `ten thousand sequential posts all succeed`() {
        // One shared transport, exactly as the VPN service uses it. If the transport
        // were built per query instead, this would still pass, which is why the
        // resource tests below matter more than this one.
        repeat(iterations) { i ->
            val query = DnsMessageBuilder.query("load-$i.example.com", transactionId = i and 0xFFFF)
            val response = transport.post(url(), query)

            assertThat(response.size).isGreaterThan(12)
            assertThat(transactionId(response)).isEqualTo(i and 0xFFFF)
        }

        assertThat(server.requestsHandled.get()).isEqualTo(iterations)
    }

    @Test
    fun `open socket count does not grow with request count`() {
        // The core leak assertion. Reading the baseline after a warmup matters: OkHttp
        // allocates its dispatcher threads and the initial connection pool on first
        // use, and counting those as a leak would make the test useless.
        warmup()

        val baseline = openSockets()
        repeat(iterations) { i ->
            transport.post(url(), DnsMessageBuilder.query("leak-$i.example.com"))
        }
        // Give the pool a moment to release idle connections back to its max.
        Thread.sleep(SETTLE_MS)

        // Pool growth is allowed; unbounded growth is not. OkHttp holds up to five idle
        // connections per host, so a real leak of one socket per request would be
        // roughly `iterations` here rather than a couple of dozen.
        assertNoSocketGrowth("sequential fd growth", baseline, openSockets())

        // Platform-independent companion to the descriptor count: a correct keep-alive
        // run reuses a small pool, so the server should be holding only a handful of
        // live connections after 10,000 requests. This assertion runs everywhere,
        // including where descriptor counts are unavailable, so the leak check is
        // never fully vacuous.
        assertThat(server.openConnections.get()).isLessThan(maxSocketGrowth)
    }

    @Test
    fun `threads do not accumulate across many requests`() {
        // OkHttp's dispatcher uses a shared thread pool, so a correct run ends with a
        // stable thread count. A transport that spawned a thread per request would end
        // thousands higher, and on Android that is an ANR and a process kill.
        warmup()

        val baseline = Thread.activeCount()
        repeat(iterations) { i ->
            transport.post(url(), DnsMessageBuilder.query("threads-$i.example.com"))
        }
        Thread.sleep(SETTLE_MS)

        assertThat(Thread.activeCount() - baseline).isLessThan(maxThreadGrowth)
    }

    @Test
    fun `heap does not grow without bound across many requests`() {
        warmup()
        System.gc()
        Thread.sleep(GC_SETTLE_MS)
        val before = usedHeap()

        repeat(iterations) { i ->
            transport.post(url(), DnsMessageBuilder.query("heap-$i.example.com"))
        }

        System.gc()
        Thread.sleep(GC_SETTLE_MS)
        val growth = usedHeap() - before

        // Generous ceiling for a suite that legitimately allocates per request. The
        // assertion exists to catch retention, not to police the garbage collector.
        assertThat(growth).isLessThan(maxHeapGrowthBytes)
    }

    // ---------- concurrency ----------

    @Test
    fun `concurrent posts from many threads all complete`() {
        // The service resolves lookups from several threads at once. This proves the
        // transport is not single-threaded in a way that serialises or deadlocks.
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val pool = Executors.newFixedThreadPool(workerCount)
        val started = CountDownLatch(workerCount)

        try {
            repeat(workerCount) { worker ->
                pool.submit {
                    started.countDown()
                    try {
                        val perWorker = iterations / workerCount
                        repeat(perWorker) { i ->
                            val id = (worker * perWorker + i) and 0xFFFF
                            val query = DnsMessageBuilder.query("conc-$worker-$i.example.com", transactionId = id)
                            val response = transport.post(url(), query)

                            if (transactionId(response) != id) {
                                errors += "worker $worker iteration $i: wrong transaction id"
                            }
                        }
                    } catch (error: Exception) {
                        errors += "worker $worker: ${error.message}"
                    }
                }
            }

            assertThat(started.await(30, TimeUnit.SECONDS)).isTrue()
            pool.shutdown()
            // A generous but finite bound: a deadlock here must fail the test rather
            // than hang the build indefinitely.
            assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).isTrue()
        } finally {
            pool.shutdownNow()
        }

        assertThat(errors).isEmpty()
        assertThat(server.requestsHandled.get()).isEqualTo(iterations - (iterations % workerCount))
        // Concurrency is where a client-side connection leak would show up first.
        Thread.sleep(SETTLE_MS)
        assertThat(server.openConnections.get()).isLessThan(workerCount * 4)
    }

    // ---------- failure behaviour under load ----------

    @Test
    fun `a server that never answers fails with a timeout rather than hanging`() {
        // The failure that matters most on a VPN: no network. A blocking call that
        // never returns would stall the resolution thread forever and the user's DNS
        // would hang with it.
        val blackHole = LoopbackDohServer(respondWith = null)
        blackHole.start()
        try {
            val stalled = OkHttpDohTransport(connectTimeoutMs = 500, readTimeoutMs = 300)

            val started = System.nanoTime()
            val outcome = runCatching {
                stalled.post(blackHole.baseUrl(), DnsMessageBuilder.query("hang.example.com"))
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            assertThat(outcome.isFailure).isTrue()
            assertThat(outcome.exceptionOrNull()).isInstanceOf(IOException::class.java)
            // callTimeout is connect+read, so 500ms must bound this. The generous upper
            // bound leaves room for CI scheduling without permitting a real hang.
            assertThat(elapsedMs).isLessThan(15_000)
        } finally {
            blackHole.shutdown()
        }
    }

    @Test
    fun `repeated failures do not accumulate sockets`() {
        // Every request fails against a dead port. If the transport leaked the socket
        // on the error path -- the path that is least likely to be exercised in
        // production -- the count would climb by one per attempt.
        warmup()
        val baseline = openSockets()
        // Port 1 on loopback: reliably refused, never listening.
        val deadUrl = "http://127.0.0.1:1/dns-query"

        repeat(failureIterations) {
            runCatching { transport.post(deadUrl, DnsMessageBuilder.query("dead.example.com")) }
        }
        Thread.sleep(SETTLE_MS)

        assertNoSocketGrowth("failed-request fd growth", baseline, openSockets())
    }

    @Test
    fun `resolver reports failure rather than downgrading when the transport fails`() {
        // The privacy invariant, asserted under repetition: a broken DoH path must
        // never come back as a plaintext success.
        val exploding = object : DohTransport {
            override fun post(url: String, body: ByteArray): ByteArray =
                throw IOException("no network")
        }
        val resolver = DohUpstreamResolver(DohProvider.QUAD9, exploding)

        repeat(resolverIterations) {
            val result = resolver.resolve(DnsMessageBuilder.query("fail.example.com"))
            assertThat(result).isInstanceOf(DohUpstreamResolver.DohResult.Failed::class.java)
        }
    }

    // ---------- helpers ----------

    private fun warmup() {
        // Enough requests to have every lazily-created resource in existence.
        repeat(WARMUP) { i ->
            runCatching { transport.post(url(), DnsMessageBuilder.query("warmup-$i.example.com")) }
        }
        Thread.sleep(SETTLE_MS)
    }

    /**
     * Open file descriptors for this process, or -1 where the JVM cannot report it.
     *
     * The com.sun.management interface exposes this on Linux; elsewhere (notably
     * Windows) it is unsupported. Returning -1 rather than a fake zero keeps the
     * callers honest, which skip the assertion instead of passing it vacuously.
     */
    private fun openSockets(): Int? {
        return try {
            // Reached reflectively on purpose. android.jar does not expose
            // java.lang.management, so this symbol cannot be referenced at compile
            // time in an Android unit test even though it exists on the desktop JVM
            // that actually runs the test.
            val factory = Class.forName("java.lang.management.ManagementFactory")
            val bean = factory.getMethod("getOperatingSystemMXBean").invoke(null) ?: return null
            val method = bean.javaClass.methods.firstOrNull {
                it.name == "getOpenFileDescriptorCount" && it.parameterCount == 0
            } ?: return null
            method.isAccessible = true
            (method.invoke(bean) as? Number)?.toInt()
        } catch (_: Throwable) {
            // Unsupported platform (Windows), or the module system refusing
            // reflective access. Null means "cannot measure", never "measured zero".
            null
        }
    }

    /**
     * Asserts socket growth stays bounded, or records that the platform cannot
     * measure it.
     *
     * Returning without asserting is deliberate and visible in the output: a vacuous
     * pass would be worse than an honest skip, because it would look like coverage
     * that does not exist.
     */
    private fun assertNoSocketGrowth(label: String, baseline: Int?, after: Int?) {
        if (baseline == null || after == null) {
            println("$label: SKIPPED - this JVM cannot report open file descriptors")
            return
        }
        println("$label: open descriptors $baseline -> $after")
        assertThat(after - baseline).isLessThan(maxSocketGrowth)
    }

    private fun usedHeap(): Long =
        Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

    private companion object {
        const val iterations = 10_000
        const val failureIterations = 500
        const val resolverIterations = 1_000
        const val workerCount = 8
        const val WARMUP = 50
        const val SETTLE_MS = 500L
        const val GC_SETTLE_MS = 200L

        /**
         * Headroom for OkHttp's connection pool and the JVM's own bookkeeping.
         * A per-request socket leak would be ~10,000, so anything in this range
         * distinguishes the two.
         */
        const val maxSocketGrowth = 200
        const val maxThreadGrowth = 40
        const val maxHeapGrowthBytes = 96L * 1024 * 1024
    }
}

/**
 * Minimal HTTP/1.1 server speaking just enough to satisfy a DoH POST.
 *
 * Hand-rolled rather than pulled from a test library so the suite adds no
 * dependency, and so the failure mode is controllable: [respondWith] set to null makes
 * it accept connections and never reply, which is exactly the black-hole condition
 * the timeout test needs.
 */
private class LoopbackDohServer(private val respondWith: ((ByteArray) -> ByteArray)? = { it }) {

    private val serverSocket = ServerSocket(0, BACKLOG, InetAddress.getByName("127.0.0.1"))
    private val running = AtomicBoolean(true)
    private val pool = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "loopback-doh").apply { isDaemon = true }
    }

    val requestsHandled = AtomicInteger()

    /** Connections still open. Used to assert the client side actually disconnects. */
    val openConnections = AtomicInteger()

    fun baseUrl(): String = "http://127.0.0.1:${serverSocket.localPort}/dns-query"

    fun start() {
        pool.submit {
            while (running.get()) {
                val socket = try {
                    serverSocket.accept()
                } catch (_: IOException) {
                    return@submit // closed during shutdown
                }
                pool.submit { handle(socket) }
            }
        }
    }

    private fun handle(socket: Socket) {
        openConnections.incrementAndGet()
        try {
            socket.use { client ->
                // Keep-alive, as production DoH over TLS is. Serving one request per
                // connection would force a fresh socket per request, exhaust the
                // ephemeral port range after a few thousand, and test a connection
                // pattern OkHttp never actually uses.
                val input = client.getInputStream()
                val output = client.getOutputStream()
                while (handleRequest(input, output)) {
                    // Serve the next request on the same connection.
                }
            }
        } catch (_: IOException) {
            // Client hung up early, which some tests deliberately do.
        } finally {
            openConnections.decrementAndGet()
        }
    }

    /** @return true when the connection should stay open for another request. */
    private fun handleRequest(input: InputStream, output: OutputStream): Boolean {
        val contentLength = readRequest(input) ?: return false
        val body = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(body, read, contentLength - read)
            if (n < 0) return false
            read += n
        }
        requestsHandled.incrementAndGet()

        val responder = respondWith ?: return true // black hole: hold the socket open
        val payload = responder(body)

        output.write(
            buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: application/dns-message\r\n")
                append("Content-Length: ${payload.size}\r\n")
                append("\r\n")
            }.toByteArray(Charsets.US_ASCII),
        )
        output.write(payload)
        output.flush()
        return true
    }

    /** Returns the declared body length, or null when the request is unusable. */
    private fun readRequest(input: InputStream): Int? {
        val head = readHeader(input) ?: return null

        return head.lineSequence()
            .firstOrNull { it.startsWith(CONTENT_LENGTH_HEADER, ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.toIntOrNull()
    }

    /** Reads up to and including the blank line that ends the HTTP header block. */
    private fun readHeader(input: InputStream): String? {
        val head = StringBuilder()
        while (head.length < MAX_HEADER_BYTES) {
            val b = input.read()
            if (b < 0) return null
            head.append(b.toChar())
            if (head.endsWith("\r\n\r\n")) return head.toString()
        }
        return null
    }

    fun shutdown() {
        running.set(false)
        try {
            serverSocket.close()
        } catch (_: IOException) {
            // Already closed.
        }
        pool.shutdownNow()
    }

    private companion object {
        const val BACKLOG = 128
        const val MAX_HEADER_BYTES = 8 * 1024
        const val CONTENT_LENGTH_HEADER = "Content-Length:"
    }
}

/** Reads the transaction id out of a DNS message. */
private fun transactionId(message: ByteArray): Int =
    ((message[0].toInt() and 0xFF) shl 8) or (message[1].toInt() and 0xFF)