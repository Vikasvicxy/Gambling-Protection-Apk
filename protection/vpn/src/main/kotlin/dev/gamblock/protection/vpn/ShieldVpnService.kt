package dev.gamblock.protection.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.core.app.NotificationCompat
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
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var running = false

    @Volatile
    private var tunThread: Thread? = null

    private var pfd: ParcelFileDescriptor? = null
    private val upstreamSemaphore = Semaphore(32)
    private var roundRobin = 0

    override fun onCreate() {
        super.onCreate()
        startVpnForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn(VpnRuntimeState.VpnFailure.REVOKED, userStopped = true)
                return START_NOT_STICKY
            }
            else -> { /* START or null (system restart) — (re)establish below */ }
        }
        if (!running) {
            scope.launch {
                establish()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    override fun onRevoke() {
        super.onRevoke()
        shutdown()
        stateStore.markStopped(VpnRuntimeState.VpnFailure.REVOKED, userStopped = false)
    }

    private suspend fun establish() = withContext(dispatchers.io) {
        if (running) return@withContext
        stateStore.markConnecting()
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
            stateStore.markStopped(VpnRuntimeState.VpnFailure.ESTABLISH_FAILED, userStopped = false)
            stopSelf()
            return@withContext
        }

        pfd = established
        stateStore.markConnected(wallClock.nowEpochMillis())
        running = true
        resetCounters()

        val input = FileInputStream(established.fileDescriptor)
        val output = FileOutputStream(established.fileDescriptor)
        tunThread = Thread(
            { runTunLoop(input, output) },
            "shield-tun",
        ).also { it.start() }

        launchScheduleMonitor()
        logger.i(Logs.VPN, "VPN established: $VpnConfig.TUN_ADDR/32, MTU ${VpnConfig.TUN_MTU}")
    }

    private fun launchScheduleMonitor() {
        scope.launch {
            while (running && isActive) {
                delay(15_000)
                if (!running) break
                val settings = settingsRepository.settings.value
                if (!settings.vpnEnabled) {
                    logger.i(Logs.VPN, "protection disabled by user; stopping")
                    stopVpn(VpnRuntimeState.VpnFailure.ESTABLISH_FAILED, userStopped = true)
                    break
                }
                val scheduleActive = settings.schedule.isSystemCurrentlyActive(wallClock.nowEpochMillis())
                if (!scheduleActive) {
                    logger.i(Logs.VPN, "protection window ended; pausing")
                    stateStore.markStopped(VpnRuntimeState.VpnFailure.IN_SCHEDULE_PAUSE, userStopped = false)
                    stopVpn(VpnRuntimeState.VpnFailure.IN_SCHEDULE_PAUSE, userStopped = false)
                    break
                }
            }
        }
    }

    private fun runTunLoop(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(READ_SIZE)
        // VpnService hands out a non-blocking fd on some builds; poll() first so the loop
        // blocks with an idle tun instead of busy-spinning, but still wakes up instantly
        // when a packet is queued for us.
        val pollFd = StructPollfd().apply {
            fd = pfd?.fileDescriptor
            events = OsConstants.POLLIN.toShort()
        }
        while (running) {
            val fd = pollFd.fd
            if (fd != null) {
                val ready = try {
                    Os.poll(arrayOf(pollFd), TUN_POLL_TIMEOUT_MS)
                        .let { it > 0 && (pollFd.revents.toInt() and OsConstants.POLLIN) != 0 }
                } catch (t: Throwable) {
                    if (!running) break
                    logger.w(Logs.VPN, "tun poll failed: ${t.message}")
                    Thread.sleep(25)
                    true
                }
                if (!ready) continue
            }
            val n = try {
                input.read(buffer)
            } catch (t: Throwable) {
                if (running) logger.e(Logs.VPN, "tun read failed: ${t.message}", t)
                break
            }
            if (n <= 0) {
                if (!running) break
                Thread.sleep(25)
                continue
            }
            if (!running) break
            try {
                handlePacket(buffer.copyOfRange(0, n), output)
            } catch (t: Throwable) {
                // A single malformed packet must never kill the loop.
                if (running) logger.w(Logs.DNS, "packet ignored: ${t.message}")
            }
        }
    }

    private fun handlePacket(packet: ByteArray, output: OutputStream) {
        val udp = IpPacketCodec.parseUdp(packet)
        if (udp == null) {
            // Non-DNS traffic on the tun (e.g. IPv6 router-solicitation, DoT probes) is ignored by design.
            logger.d(Logs.DNS, "tun ${packet.size}B dropped: not IPv4/IPv6 UDP")
            return
        }
        if (udp.dstPort != VpnConfig.DNS_PORT) {
            logger.d(Logs.DNS, "tun ${packet.size}B dropped: dst port ${udp.dstPort} != ${VpnConfig.DNS_PORT}")
            return
        }
        if (!udp.dstAddress.contentEquals(VpnConfig.TUN_ADDR_BYTES)) {
            logger.d(Logs.DNS, "tun ${packet.size}B dropped: dst not ${VpnConfig.TUN_ADDR}")
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
            writeResponse(udp, DnsResponseFactory.refused(query), output)
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
            writeResponse(udp, DnsResponseFactory.blocked(query), output)
            logger.d(Logs.DNS, "BLOCK ${question.name} (${decision.reason})")
            return
        }

        stateStore.recordQuery(allowed = true)
        val answer = forwardQuery(udp, query)
        val response = answer ?: DnsResponseFactory.refused(query) // fail-open on upstream errors
        writeResponse(udp, response, output)
        logger.d(Logs.DNS, "ALLOW ${question.name} (upstream ${if (answer != null) "ok" else "fail-open"})")
    }

    private fun forwardQuery(udp: UdpPacket, query: ByteArray): ByteArray? {
        val servers = upstreamProvider.currentServers()
        if (servers.isEmpty()) return null
        if (!upstreamSemaphore.tryAcquire(2, TimeUnit.SECONDS)) return null
        return try {
            val socket = DatagramSocket()
            try {
                socket.soTimeout = UPSTREAM_TIMEOUT_MS
                protect(socket)
                val server = servers[(roundRobin++) % servers.size]
                socket.send(DatagramPacket(query, query.size, server, VpnConfig.DNS_PORT))
                val reply = ByteArray(MAX_DNS_REPLY)
                val datagram = DatagramPacket(reply, reply.size)
                socket.receive(datagram)
                reply.copyOf(datagram.length)
            } finally {
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

    private fun shutdown() {
        val wasRunning = running
        running = false
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
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "VPN protection",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) },
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Shield is protecting this phone")
            .setContentText("All internet traffic is filtered locally")
            .setSmallIcon(R.drawable.ic_stat_shield_vpn)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(buildConfigureIntent())
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
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
        private const val CHANNEL_ID = "shield_vpn"
        private const val NOTIFICATION_ID = 1
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