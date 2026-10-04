package dev.gamblock.protection.vpn

/**
 * Decides how the DNS pipeline should behave while the device is changing networks.
 *
 * ## The problem this solves
 *
 * On a Wi-Fi to cellular handoff there is a short window where the old physical network is gone
 * but the new one has not been resolved yet. Anything that treats that window as "carry on
 * anyway" breaks, and anything that treats it as "give up" loses protection. The previous
 * behaviour was the latter: [ShieldVpnService.forwardQuery] compared its captured upstream
 * generation against the current one and returned `null` on any mismatch, so every query that
 * landed mid-handoff was silently dropped. On a real network change that is a burst of failed
 * lookups in whatever app was in the foreground, and from the user's side it looks like the
 * protection app is broken.
 *
 * ## The approach
 *
 * Keep reading from the tunnel but hold the results. A DNS query in flight is not discarded and
 * not answered wrongly; it waits, briefly, for the replacement upstream to appear and is then
 * forwarded over it. Queries are not retried against the dead network, so nothing is sent to a
 * network that no longer exists.
 *
 * Two bounds keep this from becoming a hang:
 *
 *  - [maxWaitMs] caps how long a query will wait. Past that the caller is told the upstream is
 *    not coming and fails the query, which is the same outcome as before this class existed.
 *  - A handoff that never completes (airplane mode, all networks down) expires on its own, so
 *    the gate always returns to a usable state without external intervention.
 *
 * This is deliberately free of Android types. The service owns the callbacks and the socket
 * plumbing; this owns only the timing decisions, which is the part worth testing exhaustively.
 *
 * Not thread-safe as a policy object: it is designed to be guarded by the service's existing
 * lifecycle lock, or to be confined to the TUN reader coroutine.
 */
class NetworkHandoffGate(
    /** Longest a caller will be held while waiting for a replacement upstream. */
    val maxWaitMs: Long = DEFAULT_MAX_WAIT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** What the caller should do with a query it is about to forward. */
    enum class Verdict {
        /** Upstream is settled; forward now. */
        FORWARD,

        /** A handoff is in progress; wait for [NetworkHandoffGate.awaitStable] to return true. */
        WAIT,

        /** The handoff outlived [maxWaitMs]; stop waiting and fail the query. */
        GIVE_UP,
    }

    /** Set when a physical network change is observed. */
    @Volatile
    private var handoffStartedAtMs: Long? = null

    /** Upstream generation captured when the current handoff began. */
    @Volatile
    private var generationAtHandoff: Long = 0L

    /** Queries currently parked in the tunnel buffer awaiting a replacement upstream. */
    @Volatile
    var parkedCount: Int = 0
        private set

    /** Number of handoffs observed. Diagnostics and tests. */
    var handoffCount: Int = 0
        private set

    /**
     * Records that the physical network is changing.
     *
     * Called from the connectivity callback. [generation] is the upstream generation as it was
     * when the change was noticed; when [generation] later differs, the handoff is complete.
     */
    fun onHandoffStart(generation: Long) {
        val now = clock()
        synchronized(this) {
            if (handoffStartedAtMs == null) handoffCount++
            handoffStartedAtMs = now
            generationAtHandoff = generation
            parkedCount = 0
        }
    }

    /**
     * Records that the upstream changed to [generation].
     *
     * Ends the handoff when this is genuinely a different upstream than the one captured at the
     * start, which is what distinguishes "the new network is live" from "the callback fired again
     * for the network we already had".
     */
    fun onUpstreamGeneration(generation: Long) {
        synchronized(this) {
            if (handoffStartedAtMs == null) return
            if (generation == generationAtHandoff) return
            handoffStartedAtMs = null
            generationAtHandoff = generation
            parkedCount = 0
        }
    }

    /** Clears any handoff state. Used when the tunnel stops, where waiting has no meaning. */
    fun reset() {
        synchronized(this) {
            handoffStartedAtMs = null
            generationAtHandoff = 0L
            parkedCount = 0
        }
    }

    /** True while a handoff is open and has not yet expired. */
    fun isHandoffActive(): Boolean = when (val started = handoffStartedAtMs) {
        null -> false
        else -> clock() - started < maxWaitMs
    }

    /** Milliseconds a caller should sleep before re-checking, or 0 when there is nothing to wait for. */
    fun pollIntervalMs(): Long = if (isHandoffActive()) POLL_INTERVAL_MS else 0L

    /**
     * What to do with a query right now.
     *
     * [generation] is the caller's currently captured upstream generation; when it has moved since
     * the handoff began, the handoff is over regardless of any callback having arrived yet.
     */
    fun verdict(generation: Long): Verdict {
        val started = handoffStartedAtMs ?: return Verdict.FORWARD
        if (generation != generationAtHandoff) return Verdict.FORWARD
        val waited = clock() - started
        return if (waited >= maxWaitMs) Verdict.GIVE_UP else Verdict.WAIT
    }

    /**
     * Suspends until the upstream is settled, or until [maxWaitMs] elapses.
     *
     * [sleep] is injected so tests can advance a fake clock instead of waiting in real time, and
     * so the service can pass a cancellable delay.
     *
     * @return true when forwarding may proceed, false if the caller must fail the query.
     */
    suspend fun awaitStable(
        generation: () -> Long,
        sleep: suspend (Long) -> Unit,
    ): Boolean {
        val started = handoffStartedAtMs ?: return true
        while (true) {
            when (verdict(generation())) {
                Verdict.FORWARD -> {
                    // The upstream has moved, so the handoff is genuinely over. Clear it here
                    // rather than waiting for a callback: otherwise isHandoffActive() keeps
                    // reporting a handoff that has already finished, and pollIntervalMs() would
                    // hand callers a sleep interval for a settled network.
                    synchronized(this) {
                        handoffStartedAtMs = null
                        parkedCount = 0
                    }
                    return true
                }
                Verdict.GIVE_UP -> {
                    synchronized(this) {
                        // The window is spent. Leaving it set would strand every later query in
                        // WAIT until a callback eventually arrives, which on a device with no
                        // network may be never.
                        handoffStartedAtMs = null
                        parkedCount = 0
                    }
                    return false
                }
                Verdict.WAIT -> {
                    synchronized(this) { parkedCount++ }
                    sleep(POLL_INTERVAL_MS)
                    // Guard against the deadline passing mid-sleep so we never spin.
                    if (clock() - started >= maxWaitMs) {
                        synchronized(this) { handoffStartedAtMs = null; parkedCount = 0 }
                        return false
                    }
                }
            }
        }
    }

    companion object {
        /**
         * Long enough to cover a typical Wi-Fi to cellular handover, short enough that an app in
         * the foreground is not left with a resolver that never answers.
         */
        const val DEFAULT_MAX_WAIT_MS: Long = 3_000L

        /** How often a parked query re-checks for a settled upstream. */
        const val POLL_INTERVAL_MS: Long = 50L
    }
}