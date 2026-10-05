package dev.gamblock.protection.vpn

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.net.InetAddress
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.gamblock.core.common.clock.WallClock
import dev.gamblock.core.common.dispatcher.DispatchersProvider
import dev.gamblock.core.common.logging.Logs
import dev.gamblock.core.common.logging.ShieldLogger
import dev.gamblock.core.model.BlockDecision
import dev.gamblock.core.model.Category
import dev.gamblock.core.model.DecisionKind
import dev.gamblock.core.model.Ipv6LeakPolicy
import dev.gamblock.core.model.RuleHit
import dev.gamblock.core.model.SafeSearchPolicy
import dev.gamblock.core.model.SearchEngineMatch
import dev.gamblock.core.model.VpnRuntimeState
import dev.gamblock.core.model.util.stableHash
import dev.gamblock.data.preferences.AppExclusionRepository
import dev.gamblock.data.preferences.RecoveryRepository
import dev.gamblock.data.preferences.SettingsRepository
import dev.gamblock.protection.domainengine.DomainBlocker
import dev.gamblock.protection.dns.DnsParser
import dev.gamblock.protection.dns.DnsResponseFactory
import dev.gamblock.protection.dns.IpPacketCodec
import dev.gamblock.protection.dns.QuicFilter
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Local DNS-only VPN. All inspection happens on-device; we neither terminate HTTPS
 * nor tunnel user traffic. Blocked queries receive NXDOMAIN; allowed queries are
 * forwarded to the carrier resolver, fail-open on upstream errors.
 *
 * When encryptedDnsEnabled is set, allowed queries instead go to the configured
 * DNS-over-HTTPS provider. That path fails *closed* with SERVFAIL, unlike the
 * plaintext path which fails open, because downgrading would hand the name to the
 * carrier while the UI still claimed the query was encrypted.
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
    @Inject lateinit var appExclusionApplier: AppExclusionApplier
    @Inject lateinit var appExclusionRepository: AppExclusionRepository
    @Inject lateinit var recoveryRepository: RecoveryRepository

    /**
     * DoH resolvers keyed by physical-network generation.
     *
     * Each entry's transport is bound to the socket factory of the underlying
     * network, so Shield's own HTTPS lookups leave via the real interface instead
     * of re-entering this tunnel. Keyed by generation so a network change
     * discards the stale client and its pooled sockets.
     */
    private val dohResolvers = HashMap<Long, DohUpstreamResolver>()

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

    /**
     * Parks DNS queries while the physical network is changing instead of dropping them.
     *
     * See [NetworkHandoffGate] for why a dropped query is a user-visible failure. The tunnel is
     * never rebuilt during a handoff: the reader keeps draining the tun file descriptor and the
     * packets stay queued in the kernel buffer until a replacement upstream is available.
     */
    private val handoffGate = NetworkHandoffGate()

    /** Exemptions baked into the currently established tunnel. */
    @Volatile
    private var lastAppliedExclusions: Set<String> = emptySet()

    private var exclusionWatcherJob: Job? = null

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
        startExclusionWatcher()
    }

    /**
     * Re-establishes the tunnel when the exemption list changes.
     *
     * The platform applies disallowed applications at setup time only, so
     * without this the user would toggle a bank off, see the list update, and
     * carry on with the old tunnel still routing its DNS. Re-establishing
     * drops in-flight connections for a moment, which is the honest cost of the
     * change and the reason the UI warns before saving.
     */
    private fun startExclusionWatcher() {
        exclusionWatcherJob?.cancel()
        exclusionWatcherJob = scope.launch {
            appExclusionRepository.state
                .map { it.excludedPackages }
                .distinctUntilChanged()
                .drop(1) // the first emission matches whatever establish() already applied
                .collect { packages ->
                    if (packages == lastAppliedExclusions) return@collect
                    if (!running) return@collect
                    logger.i(Logs.VPN, "exemption list changed; re-establishing tunnel")
                    shutdown()
                    startEstablish()
                }
        }
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
        exclusionWatcherJob?.cancel()
        exclusionWatcherJob = null
        synchronized(dohResolvers) { dohResolvers.clear() }
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

    /**
     * TCP 443 SNI inspection.
     *
     * Held per run and recreated with the tunnel, because buffered ClientHello
     * bytes describe a specific upstream generation: keeping them across a
     * network change could inspect a half-received handshake against a blocklist
     * that has since been recompiled.
     */
    @Volatile
    private var sniInterceptor: TlsSniInterceptor = newSniInterceptor()

    /**
     * Blocks a host iff the schedule permits it. Read through the same gate the DNS
     * path uses, so a paused schedule does not produce SNI resets either.
     */
    private fun newSniInterceptor(): TlsSniInterceptor = TlsSniInterceptor(
        hostIsBlocked = { host ->
            val scheduleActive = settingsRepository.settings.value.schedule
                .isSystemCurrentlyActive(wallClock.nowEpochMillis())
            blocker.decide(host, scheduleActive).decision == DecisionKind.BLOCK
        },
    )

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
            .addDnsServer(VpnConfig.TUN_ADDR)

        // Routes come from VpnConfig.ALL_ROUTES so the SNI interceptor's precondition
        // (this stays a single /32 until a forwarding stack exists) is asserted in
        // SniInterceptionRouteGuardTest rather than being implicit here.
        VpnConfig.ALL_ROUTES.forEach { route ->
            runCatching { builder.addRoute(route.address, route.prefixLength) }
                .onFailure {
                    logger.w(Logs.VPN, "route ${route.address}/${route.prefixLength} rejected: ${it.message}")
                }
        }

        // Keep local networks out of the tunnel so Chromecast, printers, mDNS and
        // tethered clients keep working while protection is on.
        //
        // Builder has no addDisallowedRoute; the subtraction primitive is
        // excludeRoute(IpPrefix), which is API 33+. Below that there is no
        // subtraction API at all, so the only option is to never route the private
        // space in the first place.
        //
        // Note this is a no-op for today's DNS-only /32 tunnel, which already routes
        // nothing but the DNS address. It is applied now so the routing table is
        // already correct if a full-tunnel route is ever introduced, rather than
        // discovering the collision then.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            VpnConfig.ALL_EXCLUSIONS.forEach { route ->
                runCatching {
                    // IpPrefix rejects an address with host bits set, so parse and mask.
                    val parsed = InetAddress.getByName(route.address)
                    builder.excludeRoute(IpPrefix(parsed, route.prefixLength))
                }.onFailure {
                    logger.w(Logs.VPN, "exclude ${route.address}/${route.prefixLength} rejected: ${it.message}")
                }
            }
        }

        // Split tunnelling must be declared before establish(): the platform
        // freezes the exemption list when the tunnel comes up, so anything added
        // afterwards is silently ignored until the next setup.
        val exclusionPlan = appExclusionApplier.currentPlan()
        lastAppliedExclusions = exclusionPlan.applied.toSet()
        appExclusionApplier.applyTo(builder, exclusionPlan)

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
            recordProtectedDay()
            tunThread = Thread(
                { runTunLoop(input, output, generation) },
                "shield-tun",
            ).also { it.start() }
        }
        if (!isCurrentRun(generation)) return@withContext
        resetCounters()
        // Fresh interceptor per tunnel: any half-reassembled ClientHello describes the
        // previous generation's connection and must not be judged against this one.
        sniInterceptor.reset()
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

    /**
     * Marks today as a protected day, which is what the money-saved figure is
     * derived from.
     *
     * Recorded only once the tun is actually established, not when the user taps
     * start, so a failure to bring the VPN up cannot be counted as protection.
     * The repository de-duplicates within a day, so a flapping connection cannot
     * inflate anything. Failures are swallowed deliberately: the VPN is already
     * running and must not be torn down because a progress metric could not be
     * written.
     */
    private fun recordProtectedDay() {
        scope.launch {
            runCatching { recoveryRepository.recordProtectionActive(wallClock.nowEpochMillis()) }
                .onFailure { error ->
                    logger.w(Logs.VPN, "Could not record protected day", error)
                }
        }
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

    /**
     * The [BlockDecision] recorded for a host refused via its TLS ClientHello.
     *
     * This path never consults the compiled rule index for the hostname directly:
     * [TlsSniInterceptor] already asked, and answering BLOCK means a rule matched.
     * The rule hit is therefore reported as [Category.UNKNOWN] with a signature
     * prefixed `sni:` so it cannot collide with, or be collapsed into, the DNS
     * event for the same host.
     */
    private fun sniBlockDecision(hostname: String): BlockDecision = BlockDecision(
        decision = DecisionKind.BLOCK,
        ruleHit = RuleHit(
            normalizedDomain = hostname,
            category = Category.UNKNOWN,
        ),
        signature = stableHash("sni:$hostname").toString(),
        reason = "blocked by TLS SNI inspection",
    )

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

        // TCP 443 interception is evaluated before the UDP path so the SNI engine
        // gets first refusal on any TCP segment. It is a no-op unless the user has
        // enabled the setting, because the DNS-only /32 route never delivers TCP
        // traffic here today (see docs/ARCHITECTURE.md).
        if (settingsRepository.settings.value.sniInterceptionEnabled) {
            when (val verdict = sniInterceptor.inspect(packet, length)) {
                is TlsSniInterceptor.Verdict.Block -> {
                    stateStore.recordSniBlock()
                    // Record through the normal block pipeline so SNI blocks appear
                    // in the same history and reports as DNS blocks. The signature
                    // prefix keeps them distinct, which is what lets the UI say
                    // "blocked by TLS name" instead of implying a DNS lookup failed.
                    recorder.recordBlocked(verdict.hostname, sniBlockDecision(verdict.hostname))
                    if (isCurrentRun(generation)) {
                        runCatching { output.write(verdict.reset); output.flush() }
                            .onFailure {
                                if (isCurrentRun(generation)) {
                                    logger.w(Logs.VPN, "sni reset write failed: ${it.message}")
                                }
                            }
                    }
                    return
                }
                // Either allowed, or nothing to inspect. The packet is not ours to
                // forward: this build has no userspace TCP stack, so it is dropped
                // exactly as it was before the interceptor existed.
                TlsSniInterceptor.Verdict.Forward,
                TlsSniInterceptor.Verdict.Hold,
                -> return
            }
        }

        val udp = IpPacketCodec.parseUdp(packet, length)
        if (udp == null) {
            // Non-DNS traffic on the tun (e.g. IPv6 router-solicitation, DoT probes) is ignored by design.
            logger.d(Logs.DNS, "tun ${length}B dropped: not IPv4/IPv6 UDP")
            return
        }
        if (QuicFilter.shouldDrop(udp, settingsRepository.settings.value.blockEncryptedBrowsers)) {
            stateStore.recordQuicDrop()
            logger.d(Logs.DNS, "tun ${length}B dropped: QUIC/UDP ${udp.dstPort} forces TCP fallback")
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

        val settings = settingsRepository.settings.value

        // IPv6 leak suppression, after the block decision so a blocked name
        // still gets the stronger NXDOMAIN. Applied to every allowed domain
        // rather than only blocked ones: we forward no packets, so handing a
        // client an AAAA it could have used would just black-hole the tunnel.
        if (Ipv6LeakPolicy.shouldSuppress(question.type, settings.ipv6LeakProtectionEnabled)) {
            stateStore.recordIpv6Suppressed()
            if (isCurrentRun(generation)) {
                writeResponse(udp, DnsResponseFactory.emptyNoError(query), output)
            }
            logger.d(Logs.DNS, "AAAA suppressed for ${question.name}; forcing IPv4")
            return
        }

        // Search-engine recognition. This deliberately does not rewrite the
        // answer: substituting the SafeSearch address cannot change what a TLS
        // client sees, because the client still sends the original hostname in
        // SNI. See SafeSearchPolicy before "fixing" this into a rewrite.
        if (settings.safeSearchAssistEnabled) {
            when (val match = SafeSearchPolicy.classify(question.name)) {
                is SearchEngineMatch.Engine -> {
                    stateStore.recordSearchEngineQuery()
                    logger.i(Logs.DNS, "search engine detected: ${match.engine.displayName}")
                }
                is SearchEngineMatch.AlreadySafe, SearchEngineMatch.NotSearch -> Unit
            }
        }

        // Encrypted upstream. When DoH is on, allowed queries go over HTTPS to the
        // configured provider instead of plaintext UDP 53, so the network cannot see
        // the names Shield resolves. Failures are NOT downgraded to plaintext:
        // a silent fallback would hand the name to the carrier anyway while the
        // UI still claimed protection. The client sees SERVFAIL, which is the
        // honest outcome.
        val answer = if (settings.encryptedDnsEnabled) {
            val response = forwardQueryDoh(query, generation)
            if (!isCurrentRun(generation)) return
            response ?: DnsResponseFactory.servFail(query)
        } else {
            forwardQuery(query, generation)
        }
        if (!isCurrentRun(generation)) return
        val finalResponse = answer ?: DnsResponseFactory.refused(query)
        writeResponse(udp, finalResponse, output)
        logger.d(
            Logs.DNS,
            "ALLOW ${question.name} (${if (settings.encryptedDnsEnabled) "doh" else "udp"} " +
                "${if (answer != null) "ok" else "failed"})",
        )
    }

    /**
     * Returns the resolver bound to the physical network for [networkGeneration].
     *
     * The transport gets that network's socket factory so its sockets are pinned to
     * the real interface. Without this, Shield's DoH queries would be routed back
     * into the tun this service owns and fail to resolve themselves.
     */
    private fun dohResolverFor(networkGeneration: Long): DohUpstreamResolver =
        synchronized(dohResolvers) {
            dohResolvers[networkGeneration]?.let { return it }
            // Drop clients for generations we have left so pooled sockets and their
            // bound file descriptors are released rather than leaked.
            dohResolvers.keys.filter { it != networkGeneration }.forEach(dohResolvers::remove)
            val socketFactory = upstreamProvider.currentUpstream().network?.let { network ->
                runCatching { network.socketFactory }.getOrNull()
            }
            DohUpstreamResolver(
                transport = OkHttpDohTransport(socketFactory = socketFactory),
            ).also { dohResolvers[networkGeneration] = it }
        }

    /** Resolves [query] over DNS-over-HTTPS, honouring the same handoff parking as the UDP path. */
    private fun forwardQueryDoh(query: ByteArray, runGeneration: Long): ByteArray? {
        if (!isCurrentRun(runGeneration)) return null
        handoffGate.onUpstreamGeneration(currentUpstreamGeneration())
        return try {
            val stable = runBlocking {
                handoffGate.awaitStable(
                    generation = { currentUpstreamGeneration() },
                    sleep = { ms -> delay(ms) },
                )
            }
            if (!stable) {
                logger.w(Logs.DNS, "upstream did not settle during handoff; failing DoH query")
                return null
            }
            if (!isCurrentRun(runGeneration)) return null
            // Bound DoH concurrency the same way as the UDP path: each call holds a
            // thread for the length of an HTTPS round trip.
            if (!upstreamSemaphore.tryAcquire(DOH_SLOT_WAIT_MS, TimeUnit.MILLISECONDS)) {
                logger.w(Logs.DNS, "doh query dropped: no upstream slot within ${DOH_SLOT_WAIT_MS}ms")
                return null
            }
            try {
                if (!isCurrentRun(runGeneration)) return null
                val generation = currentUpstreamGeneration()
                if (generation != currentUpstreamGeneration()) return null
                when (val result = dohResolverFor(generation).resolve(query)) {
                    is DohUpstreamResolver.DohResult.Resolved -> {
                        stateStore.recordEncryptedUpstreamQuery()
                        result.response
                    }
                    is DohUpstreamResolver.DohResult.Failed -> {
                        stateStore.recordEncryptedUpstreamFailure()
                        logger.w(Logs.DNS, "doh upstream failed: ${result.reason}")
                        null
                    }
                }
            } finally {
                upstreamSemaphore.release()
            }
        } catch (t: Throwable) {
            if (isCurrentRun(runGeneration)) {
                stateStore.recordEncryptedUpstreamFailure()
                logger.w(Logs.DNS, "doh upstream error: ${t.message}")
            }
            null
        }
    }

    private fun forwardQuery(query: ByteArray, runGeneration: Long): ByteArray? {
        if (!isCurrentRun(runGeneration)) return null
        // Park through a network handoff rather than failing. A query that arrives while the old
        // network is down and the new one is still being resolved waits here for the replacement
        // upstream, then goes out over it. Nothing is sent to the network that just disappeared.
        val stable = runBlocking {
            handoffGate.awaitStable(
                generation = { currentUpstreamGeneration() },
                sleep = { ms -> delay(ms) },
            )
        }
        if (!stable) {
            logger.w(Logs.DNS, "upstream did not settle within the handoff window; failing query")
            return null
        }
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
        // Open the handoff window *before* touching sockets, so a query that arrives in this
        // window waits for the replacement upstream instead of racing a generation bump and being
        // discarded.
        handoffGate.onHandoffStart(currentUpstreamGeneration())
        networkGeneration.incrementAndGet()
        closeUpstreamSockets()
        upstreamProvider.refreshNow()
        // refreshNow() may already have produced a new upstream, which closes the window.
        handoffGate.onUpstreamGeneration(currentUpstreamGeneration())
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

    /** How long a DoH query waits for a free upstream slot before being failed. */
    private const val DOH_SLOT_WAIT_MS = 2_000L

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