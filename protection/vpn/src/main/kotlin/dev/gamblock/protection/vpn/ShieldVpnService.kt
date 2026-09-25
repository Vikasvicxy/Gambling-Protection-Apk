package dev.gamblock.protection.vpn

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.gamblock.core.common.clock.WallClock
import dev.gamblock.core.common.dispatcher.DispatchersProvider
import dev.gamblock.core.common.logging.Logs
import dev.gamblock.core.common.logging.ShieldLogger
import dev.gamblock.core.model.DecisionKind
import dev.gamblock.core.model.VpnRuntimeState
import dev.gamblock.data.preferences.SettingsRepository
import dev.gamblock.protection.domainengine.DomainBlocker
import dev.gamblock.protection.dns.DnsParser
import dev.gamblock.protection.dns.DnsResponseFactory
import dev.gamblock.protection.dns.IpPacketCodec
import dev.gamblock.protection.dns.UdpPacket
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Local DNS-only VPN. All inspection happens on-device; we neither terminate HTTPS
 * nor tunnel user traffic. Blocked queries receive NXDOMAIN; allowed queries are
 * forwarded to the carrier resolver, fail-open on upstream errors.
 *
 * Security notes (see SECURITY.md): the fixed non-routable tun address is topologically
 * contained; hardcoded resolvers / DoH pinned clients are out of scope and documented.
 */
@AndroidEntryPoint
class ShieldVpnService : VpnService() {

    @Inject lateinit var blocker: DomainBlocker
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var wallClock: WallClock
    @Inject lateinit var dispatchers: DispatchersProvider
    @Inject lateinit var logger: ShieldLogger
    @Inject lateinit var stateStore: VpnStateStore
    @Inject lateinit var upstreamProvider: DnsUpstreamProvider
    @Inject lateinit var recorder: BlockEventRecorder
    @Inject lateinit var notificationManager: VpnNotificationManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lifecycleLock = Any()
    private val packetExecutor = Executors.newFixedThreadPool(PACKET_WORKER_COUNT)
    private val activePacketTasks = AtomicInteger(0)

    @Volatile
    private var running = false

    @Volatile
    private var tunThread: Thread? = null

    private var starting = false
    private var lifecycleGeneration = 0L
    private var notificationJob: Job? = null

    private var pfd: ParcelFileDescriptor? = null
    private val upstreamSemaphore = Semaphore(32)
    private val activeUpstreamSockets = ConcurrentHashMap.newKeySet<DatagramSocket>()
    private val networkGeneration = AtomicLong(0)
    private var networkCallbackRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = handleNetworkChange()

        override fun onLost(network: Network) = handleNetworkChange()

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) = handleNetworkChange()

        override fun onLinkPropertiesChanged(
            network: Network,
            linkProperties: LinkProperties,
        ) = handleNetworkChange()
    }

    override fun onCreate() {
        super.onCreate()
        registerNetworkCallback()
        startVpnForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn(failure = null, userStopped = true)
                return START_NOT_STICKY
            }
            else -> {}
        }
        startEstablish()
        return START_STICKY
    }

    override fun onDestroy() {
        synchronized(lifecycleLock) {
            lifecycleGeneration++
            starting = false
        }
        unregisterNetworkCallback()
        shutdown()
        scope.cancel()
        packetExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onRevoke() {
        unregisterNetworkCallback()
        super.onRevoke()
        stopVpn(VpnRuntimeState.VpnFailure.REVOKED, userStopped = false)
    }

    private fun startEstablish() {
        val generation = synchronized(lifecycleLock) {
            if (running || starting) return
            starting = true
            lifecycleGeneration++
            lifecycleGeneration
        }
        stateStore.markConnecting()
        scope.launch {
            try {
                establish(generation)
            } finally {
                synchronized(lifecycleLock) {
                    if (lifecycleGeneration == generation) starting = false
                }
            }
        }
    }

    private fun isCurrentStart(generation: Long): Boolean = synchronized(lifecycleLock) {
        starting && lifecycleGeneration == generation
    }

    private suspend fun establish(generation: Long) = withContext(dispatchers.io) {
        if (!isCurrentStart(generation)) return@withContext
        if (!awaitBlocklistReady(generation)) {
            if (isCurrentStart(generation)) {
                stateStore.markStopped(VpnRuntimeState.VpnFailure.STARTUP_ERROR, userStopped = false)
                stopSelf()
            }
            return@withContext
        }

        val builder = Builder()
            .setSession("Shield - DNS protection")
            .setConfigureIntent(buildConfigureIntent())
            .setMtu(VpnConfig.TUN_MTU)
            .addAddress(VpnConfig.TUN_ADDR, VpnConfig.TUN_ADDR_PREFIX)
            .addRoute(VpnConfig.TUN_ADDR, VpnConfig.TUN_ADDR_PREFIX)
            .addDnsServer(VpnConfig.TUN_ADDR)

        val established = try {
            builder.establish()
        } catch (t: Throwable) {
            logger.e(Logs.VPN, "tun establish threw: ${t.message}", t)
            null
        }

        if (established == null) {
            if (isCurrentStart(generation)) {
                stateStore.markStopped(VpnRuntimeState.VpnFailure.ESTABLISH_FAILED, userStopped = false)
                stopSelf()
            }
            return@withContext
        }

        val input = try {
            FileInputStream(established.fileDescriptor)
        } catch (t: Throwable) {
            runCatching { established.close() }
            if (isCurrentStart(generation)) {
                stateStore.markStopped(VpnRuntimeState.VpnFailure.STARTUP_ERROR, userStopped = false)
                stopSelf()
            }
            logger.e(Logs.VPN, "tun input setup failed: ${t.message}", t)
            return@withContext
        }
        val output = try {
            FileOutputStream(established.fileDescriptor)
        } catch (t: Throwable) {
            runCatching { input.close() }
            runCatching { established.close() }
            if (isCurrentStart(generation)) {
                stateStore.markStopped(VpnRuntimeState.VpnFailure.STARTUP_ERROR, userStopped = false)
                stopSelf()
            }
            logger.e(Logs.VPN, "tun output setup failed: ${t.message}", t)
            return@withContext
        }

        synchronized(lifecycleLock) {
            if (!starting || lifecycleGeneration != generation) {
                runCatching { output.close() }
                runCatching { input.close() }
                runCatching { established.close() }
                return@withContext
            }
            pfd = established
            stateStore.markConnected(wallClock.nowEpochMillis())
            running = true
            tunThread = Thread(
                { runTunLoop(input, output, generation) },
                "shield-tun",
            ).also { it.start() }
        }
        if (!isCurrentRun(generation)) return@withContext
        resetCounters()
        notificationManager.ensureChannel()
        startNotificationTicker(generation)
        launchScheduleMonitor(generation)
        logger.i(Logs.VPN, "VPN established: $VpnConfig.TUN_ADDR/32, MTU ${VpnConfig.TUN_MTU}")
    }

    private fun isCurrentRun(generation: Long): Boolean = synchronized(lifecycleLock) {
        running && lifecycleGeneration == generation
    }

    private suspend fun awaitBlocklistReady(generation: Long): Boolean {
        val ready = withTimeoutOrNull(BLOCKLIST_READY_TIMEOUT_MS) {
            while (!blocker.isReady) {
                if (!isCurrentStart(generation)) return@withTimeoutOrNull false
                delay(BLOCKLIST_POLL_INTERVAL_MS)
            }
            true
        }
        return ready == true
    }

    /** Rebuilds the live FGS notification with throttled query/block counts. */
    private fun startNotificationTicker(generation: Long) {
        synchronized(lifecycleLock) {
            if (!running || lifecycleGeneration != generation) return
            notificationJob?.cancel()
            notificationJob = scope.launch {
                while (isCurrentRun(generation) && isActive) {
                    notificationManager.update(stateStore.state.value)
                    delay(VpnNotificationManager.UPDATE_THROTTLE_MS)
                }
            }
        }
    }

    private fun launchScheduleMonitor(generation: Long) {
        scope.launch {
            while (isCurrentRun(generation) && isActive) {
                delay(15_000)
                if (!isCurrentRun(generation)) break
                val settings = settingsRepository.settings.value
                if (!settings.vpnEnabled) {
                    logger.i(Logs.VPN, "protection disabled by user; stopping")
                    stopVpn(failure = null, userStopped = true)
                    break
                }
                val scheduleActive = settings.schedule.isSystemCurrentlyActive(wallClock.nowEpochMillis())
                if (!scheduleActive) {
                    logger.i(Logs.VPN, "protection window ended; pausing")
                    stopVpn(VpnRuntimeState.VpnFailure.IN_SCHEDULE_PAUSE, userStopped = false)
                    break
                }
            }
        }
    }

    private fun runTunLoop(input: InputStream, output: OutputStream, generation: Long) {
        val buffer = ByteArray(READ_SIZE)
        val pollFd = StructPollfd().apply {
            fd = pfd?.fileDescriptor
            events = OsConstants.POLLIN.toShort()
        }
        while (isCurrentRun(generation)) {
            val fd = pollFd.fd
            if (fd != null) {
                val ready = try {
                    Os.poll(arrayOf(pollFd), TUN_POLL_TIMEOUT_MS)
                        .let { it > 0 && (pollFd.revents.toInt() and OsConstants.POLLIN) != 0 }
                } catch (t: Throwable) {
                    if (!isCurrentRun(generation)) break
                    logger.w(Logs.VPN, "tun poll failed: ${t.message}")
                    Thread.sleep(25)
                    true
                }
                if (!ready) continue
            }
            val n = try {
                input.read(buffer)
            } catch (t: Throwable) {
                if (isCurrentRun(generation)) logger.e(Logs.VPN, "tun read failed: ${t.message}", t)
                break
            }
            if (n <= 0) {
                if (!isCurrentRun(generation)) break
                Thread.sleep(25)
                continue
            }
            if (!isCurrentRun(generation)) break
            dispatchPacket(buffer.copyOf(n), n, output, generation)
        }
    }

    private fun dispatchPacket(packet: ByteArray, length: Int, output: OutputStream, generation: Long) {
        val taskCount = activePacketTasks.incrementAndGet()
        if (taskCount > MAX_ACTIVE_PACKET_TASKS) {
            activePacketTasks.decrementAndGet()
            logger.w(Logs.DNS, "packet dropped: forwarding queue full")
            return
        }
        try {
            packetExecutor.execute {
                try {
                    handlePacket(packet, length, output, generation)
                } catch (t: Throwable) {
                    if (isCurrentRun(generation)) logger.w(Logs.DNS, "packet ignored: ${t.message}")
                } finally {
                    activePacketTasks.decrementAndGet()
                }
            }
        } catch (t: Throwable) {
            activePacketTasks.decrementAndGet()
            if (isCurrentRun(generation)) logger.w(Logs.DNS, "packet dispatch failed: ${t.message}")
        }
    }

    private fun handlePacket(
        packet: ByteArray,
        length: Int,
        output: OutputStream,
        generation: Long,
    ) {
        if (!isCurrentRun(generation)) return
        val udp = IpPacketCodec.parseUdp(packet, length)
        if (udp == null) {
            // Non-DNS traffic on the tun (e.g. IPv6 router-solicitation, DoT probes) is ignored by design.
            logger.d(Logs.DNS, "tun ${length}B dropped: not IPv4/IPv6 UDP")
            return
        }
        if (udp.dstPort != VpnConfig.DNS_PORT) {
            logger.d(Logs.DNS, "tun ${length}B dropped: dst port ${udp.dstPort} != ${VpnConfig.DNS_PORT}")
            return
        }
        if (!udp.dstAddress.contentEquals(VpnConfig.TUN_ADDR_BYTES)) {
            logger.d(Logs.DNS, "tun ${length}B dropped: dst not ${VpnConfig.TUN_ADDR}")
            return
        }

        val query = udp.payload
        val question = try {
            DnsParser.parseQuestion(query)
        } catch (_: Exception) {
            null
        }

        if (question == null) {
            logger.w(Logs.DNS, "unparseable DNS query ${query.size}B; refusing")
            if (isCurrentRun(generation)) {
                writeResponse(udp, DnsResponseFactory.refused(query), output)
            }
            return
        }

        val scheduleActive = settingsRepository
            .settings
            .value
            .schedule
            .isSystemCurrentlyActive(wallClock.nowEpochMillis())
        val decision = blocker.decide(question.name, scheduleActive)

        if (decision.decision == DecisionKind.BLOCK) {
            stateStore.recordQuery(allowed = false)
            recorder.recordBlocked(question.name, decision)
            if (isCurrentRun(generation)) {
                writeResponse(udp, DnsResponseFactory.blocked(query), output)
                logger.d(Logs.DNS, "BLOCK ${question.name} (${decision.reason})")
            }
            return
        }

        stateStore.recordQuery(allowed = true)
        if (decision.bypassViaException) {
            stateStore.recordExceptionApplied()
            logger.d(Logs.VPN, "EXCEPTION ${question.name} (custom allowlist)")
        }
        val answer = forwardQuery(query, generation)
        if (!isCurrentRun(generation)) return
        val response = answer ?: DnsResponseFactory.refused(query)
        writeResponse(udp, response, output)
        logger.d(Logs.DNS, "ALLOW ${question.name} (upstream ${if (answer != null) "ok" else "fail-open"})")
    }

    private fun forwardQuery(query: ByteArray, runGeneration: Long): ByteArray? {
        if (!isCurrentRun(runGeneration)) return null
        val upstream = upstreamProvider.currentUpstream()
        val generation = currentUpstreamGeneration()
        if (!upstreamSemaphore.tryAcquire(2, TimeUnit.SECONDS)) return null
        return try {
            val socket = DatagramSocket()
            activeUpstreamSockets.add(socket)
            try {
                socket.soTimeout = UPSTREAM_TIMEOUT_MS
                if (!protect(socket)) {
                    logger.w(Logs.DNS, "upstream socket could not be protected")
                    return null
                }
                if (generation != currentUpstreamGeneration()) return null
                upstream.network?.let { network ->
                    runCatching { network.bindSocket(socket) }
                        .onFailure { logger.d(Logs.DNS, "upstream network bind failed: ${it.message}") }
                }
                val candidates = UpstreamFallbackResolver.ordered(upstream.servers)
                var attempts = 0
                var lastError: Throwable? = null
                for (server in candidates) {
                    attempts++
                    try {
                        if (generation != currentUpstreamGeneration()) return null
                        if (!isCurrentRun(runGeneration)) return null
                        socket.send(DatagramPacket(query, query.size, server, VpnConfig.DNS_PORT))
                        val reply = ByteArray(MAX_DNS_REPLY)
                        val datagram = DatagramPacket(reply, reply.size)
                        socket.receive(datagram)
                        if (datagram.length > 0 &&
                            datagram.port == VpnConfig.DNS_PORT &&
                            datagram.address == server
                        ) {
                            if (attempts > 1) {
                                logger.w(Logs.DNS, "upstream fallback: answered by ${server.hostAddress} on attempt $attempts")
                            }
                            return reply.copyOf(datagram.length)
                        }
                        logger.d(Logs.DNS, "ignored invalid upstream response from ${datagram.address}:${datagram.port}")
                    } catch (t: Throwable) {
                        lastError = t
                        if (!isCurrentRun(runGeneration) || generation != currentUpstreamGeneration()) return null
                        logger.d(Logs.DNS, "upstream ${server.hostAddress} failed: ${t.message}")
                    }
                }
                logger.w(Logs.DNS, "all ${candidates.size} upstream(s) failed: ${lastError?.message}")
                null
            } finally {
                activeUpstreamSockets.remove(socket)
                socket.close()
            }
        } catch (t: Throwable) {
            logger.w(Logs.DNS, "upstream failed: ${t.message}")
            null
        } finally {
            upstreamSemaphore.release()
        }
    }

    private fun writeResponse(udp: UdpPacket, dns: ByteArray, output: OutputStream) {
        try {
            val packet = IpPacketCodec.craftUdpResponse(
                family = udp.family,
                sourceIp = udp.dstAddress,
                destinationIp = udp.srcAddress,
                destinationPort = udp.srcPort,
                dnsPayload = dns,
                mtuLimit = VpnConfig.TUN_MTU,
            )
            synchronized(output) {
                output.write(packet)
                output.flush()
            }
        } catch (t: Throwable) {
            logger.w(Logs.DNS, "response write failed: ${t.message}")
        }
    }

    private fun registerNetworkCallback() {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (manager == null) {
            logger.w(Logs.VPN, "connectivity service unavailable; upstream refresh is timer-only")
            return
        }
        try {
            manager.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                networkCallback,
            )
            networkCallbackRegistered = true
        } catch (t: Throwable) {
            logger.w(Logs.VPN, "network callback registration failed: ${t.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        if (!networkCallbackRegistered) return
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (manager == null) {
            networkCallbackRegistered = false
            return
        }
        try {
            manager.unregisterNetworkCallback(networkCallback)
        } catch (t: Throwable) {
            logger.d(Logs.VPN, "network callback unregister failed: ${t.message}")
        } finally {
            networkCallbackRegistered = false
        }
    }

    private fun currentUpstreamGeneration(): Long = networkGeneration.get() + upstreamProvider.generation

    private fun handleNetworkChange() {
        networkGeneration.incrementAndGet()
        closeUpstreamSockets()
        upstreamProvider.refreshNow()
    }

    private fun closeUpstreamSockets() {
        activeUpstreamSockets.toList().forEach { socket ->
            runCatching { socket.close() }
        }
    }

    private fun shutdown() {
        val wasRunning = running
        synchronized(lifecycleLock) {
            lifecycleGeneration++
            starting = false
        }
        running = false
        closeUpstreamSockets()
        notificationJob?.cancel()
        notificationJob = null
        pfd?.close()
        pfd = null
        tunThread?.let { thread ->
            if (thread.isAlive) {
                thread.interrupt()
                try {
                    thread.join(1_500)
                } catch (_: InterruptedException) {
                    // ignore
                }
            }
        }
        tunThread = null
        if (wasRunning) stateStore.markStopped()
    }

    private fun stopVpn(failure: VpnRuntimeState.VpnFailure?, userStopped: Boolean) {
        shutdown()
        stateStore.markStopped(failure, userStopped = userStopped)
        stopForegroundService()
        stopSelf()
    }

    private fun resetCounters() {
        stateStore.resetCounters()
    }

    private fun buildConfigureIntent(): PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun startVpnForeground() {
        notificationManager.ensureChannel()
        val notification = notificationManager.buildLive(stateStore.state.value)
        ServiceCompat.startForeground(
            this,
            VpnNotificationManager.NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    private fun stopForegroundService() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    companion object {
        const val ACTION_START = "dev.gamblock.shield.action.START"
        const val ACTION_STOP = "dev.gamblock.shield.action.STOP"
        private const val PACKET_WORKER_COUNT = 8
        private const val MAX_ACTIVE_PACKET_TASKS = 64
        private const val BLOCKLIST_READY_TIMEOUT_MS = 30_000L
        private const val BLOCKLIST_POLL_INTERVAL_MS = 100L
        private const val UPSTREAM_TIMEOUT_MS = 4_000
        private const val MAX_DNS_REPLY = 2_048
        private const val READ_SIZE = 9_000
        private const val TUN_POLL_TIMEOUT_MS = 5_000

        fun launch(context: Context) {
            val intent = Intent(context, ShieldVpnService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ShieldVpnService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}