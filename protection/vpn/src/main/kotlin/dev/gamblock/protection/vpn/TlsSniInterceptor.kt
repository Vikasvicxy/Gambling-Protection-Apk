package dev.gamblock.protection.vpn

import dev.gamblock.protection.dns.TcpPacket
import dev.gamblock.protection.dns.TcpPacketCodec

/**
 * Decides whether an outbound TLS ClientHello names a blocked host, and keeps
 * just enough per-flow state to read that ClientHello when TCP has split it
 * across several segments.
 *
 * Why this is a blocker and not a proxy
 * ------------------------------------
 * Android's `VpnService` has no port-selective routing: `addRoute` takes an
 * address and a prefix length and nothing else. Pulling TCP/443 into the tunnel
 * therefore means claiming `0.0.0.0/0` and forwarding *every* packet in
 * userspace, which needs a real TCP/IP stack (handshake, sequence rewriting,
 * windowing, retransmission). That stack is deliberately absent.
 *
 * So this class implements the half that can be correct on its own: reading the
 * ClientHello, consulting the blocklist, and emitting a reset for a blocked host.
 * Returning [Verdict.Forward] means "not blocked, and the packet is not this
 * class's to handle" -- it is handed back to the transport layer, which today
 * drops it. See docs/ARCHITECTURE.md for the forwarding work this depends on.
 *
 * Battery behaviour
 * -----------------
 * A full-tunnel interceptor sits on every packet on the device, so buffering is
 * budgeted aggressively:
 *
 *  - only destination port 443 is inspected; everything else returns immediately
 *    and is never buffered;
 *  - a flow is marked [FlowState.TERMINAL] the moment its verdict is known, so a
 *    long-lived keepalive stream is examined once and never again;
 *  - a first byte that is not a TLS handshake record (content type 0x16) ends
 *    inspection for that flow permanently, which is what keeps QUIC-over-TCP and
 *    long-lived streams off the hot path;
 *  - per-flow and global buffer caps, plus idle expiry, bound resident memory
 *    regardless of how hostile or numerous the flows are.
 *
 * Every method is safe to call from the packet worker pool; state is guarded by
 * a single monitor and no method blocks on I/O.
 */
class TlsSniInterceptor(
    /**
     * Consults the compiled blocklist. Returns true when [hostname] must be
     * refused. Injected as a function so this class stays a pure, host-testable
     * unit with no dependency on the domain engine or SQLite.
     */
    private val hostIsBlocked: (String) -> Boolean,
    /** Monotonic nanosecond clock, injected so idle expiry is testable. */
    private val nowNanos: () -> Long = { System.nanoTime() },
    /** Hard ceiling on concurrently tracked flows; oldest are evicted first. */
    private val maxFlows: Int = 1024,
    /** Per-flow ceiling on buffered ClientHello bytes before inspection is abandoned. */
    private val maxBufferedBytesPerFlow: Int = 4096,
    /** A flow untouched for longer than this is dropped to reclaim memory. */
    private val flowIdleNanos: Long = 30L * 1_000_000_000L,
) {

    sealed interface Verdict {
        /** Host is blocked. [reset] is the packet to write back to the client. */
        data class Block(val hostname: String, val reset: ByteArray) : Verdict

        /**
         * Not blocked, or nothing here to inspect. The caller owns the packet.
         * Includes already-decided flows, non-443 traffic, non-TLS payloads and
         * flows abandoned for exceeding a budget.
         */
        data object Forward : Verdict

        /**
         * A partial ClientHello is buffered and the caller must wait for the next
         * segment of this flow before a decision is possible.
         */
        data object Hold : Verdict
    }

    private enum class FlowState { AWAITING_HELLO, TERMINAL }

    private class Flow {
        var state = FlowState.AWAITING_HELLO
        var buffer = ByteArray(0)
        var nextSequence = 0
        var lastSeenNanos = 0L
    }

    private val flows = LinkedHashMap<FlowKey, Flow>()

    /** Diagnostics for the UI and for leak assertions in tests. */
    val trackedFlowCount: Int get() = synchronized(lock) { flows.size }

    /** Bytes currently held across all flows. Never exceeds the configured caps. */
    val bufferedByteCount: Int get() = synchronized(lock) {
        var total = 0
        for (f in flows.values) total += f.buffer.size
        total
    }

    private val lock = Any()

    /**
     * Examines one outbound tun segment.
     *
     * @return [Verdict.Block] with a synthesised reset when the ClientHello names a
     *   blocked host, [Verdict.Hold] while a ClientHello is still being reassembled,
     *   otherwise [Verdict.Forward].
     */
    fun inspect(packet: ByteArray, length: Int = packet.size, mtuLimit: Int = 1500): Verdict {
        val tcp = TcpPacketCodec.parse(packet, length) ?: return Verdict.Forward

        // Only outbound HTTPS is in scope. RST, bare ACKs and FINs carry nothing
        // to read and must not allocate flow state.
        if (tcp.isRst || !tcp.hasPayload) return Verdict.Forward
        if (tcp.dstPort != HTTPS_PORT) return Verdict.Forward

        val key = FlowKey(tcp)

        return synchronized(lock) {
            sweepIdle()

            val existing = flows[key]
            val flow = existing ?: Flow().also {
                // Anchor the flow at this segment's sequence number. The next expected
                // sequence is derived from it below; seeding from 0 instead would make
                // every subsequent segment look like a gap.
                it.nextSequence = tcp.sequenceNumber
                flows[key] = it
                evictOverflow()
            }
            flow.lastSeenNanos = nowNanos()

            if (flow.state == FlowState.TERMINAL) {
                return@synchronized Verdict.Forward
            }

            // --- budget check before any copying ---
            if (flow.buffer.size + tcp.payload.size > maxBufferedBytesPerFlow) {
                // The client is not sending a ClientHello we can bound. Stop
                // buffering this flow entirely rather than hoard memory for it.
                flow.state = FlowState.TERMINAL
                flow.buffer = EMPTY
                return@synchronized Verdict.Forward
            }

            // --- in-order reassembly only ---
            // A sequence gap means segments are arriving out of order or were
            // retransmitted. Correct reassembly needs the full stack, so abandon
            // inspection rather than assemble something wrong and act on it.
            if (flow.buffer.isNotEmpty()) {
                val gap = TcpPacket.sequenceDistance(flow.nextSequence, tcp.sequenceNumber)
                if (gap != 0L) {
                    flow.state = FlowState.TERMINAL
                    flow.buffer = EMPTY
                    return@synchronized Verdict.Forward
                }
            }

            flow.buffer = if (flow.buffer.isEmpty()) {
                tcp.payload
            } else {
                flow.buffer + tcp.payload
            }
            flow.nextSequence = TcpPacket.advance(flow.nextSequence, tcp.payload.size)

            // A payload that does not begin with a TLS handshake record is not TLS.
            // Retire the flow now: this is the guard that keeps non-TLS long-lived
            // streams off the buffering path.
            if (flow.buffer[0].toInt() and 0xFF != TLS_HANDSHAKE) {
                flow.state = FlowState.TERMINAL
                flow.buffer = EMPTY
                return@synchronized Verdict.Forward
            }

            when (val sni = TlsSniParser.parse(flow.buffer)) {
                is TlsSniParser.SniResult.Present -> {
                    flow.state = FlowState.TERMINAL
                    flow.buffer = EMPTY
                    if (hostIsBlocked(sni.hostname)) {
                        Verdict.Block(sni.hostname, TcpPacketCodec.craftReset(tcp, mtuLimit))
                    } else {
                        Verdict.Forward
                    }
                }

                // Truncated so far. Keep buffering and wait for the next segment.
                //
                // The reason strings are matched rather than re-parsed because
                // TlsSniParser reports "not enough bytes yet" and "malformed" with
                // the same result type, and conflating them would either buffer
                // hostile input forever or abandon legitimate split handshakes.
                is TlsSniParser.SniResult.Unreadable -> {
                    if (TlsSniParser.isTruncatedReason(sni.reason)) {
                        Verdict.Hold
                    } else {
                        // Structurally wrong rather than truncated: stop buffering.
                        flow.state = FlowState.TERMINAL
                        flow.buffer = EMPTY
                        Verdict.Forward
                    }
                }

                // Parsed cleanly and there is genuinely no SNI: an IP-literal
                // connection or ECH. Nothing further to learn.
                TlsSniParser.SniResult.Absent -> {
                    flow.state = FlowState.TERMINAL
                    flow.buffer = EMPTY
                    Verdict.Forward
                }
            }
        }
    }

    /** Drops all per-flow state. Called when the tunnel is re-established or torn down. */
    fun reset() {
        synchronized(lock) { flows.clear() }
    }

    /** Drops flows for a given client address, e.g. when an app loses the network. */
    fun forgetClient(address: ByteArray) {
        synchronized(lock) {
            val doomed = flows.keys.filter { it.srcAddress.contentEquals(address) }
            doomed.forEach { flows.remove(it) }
        }
    }

    private fun sweepIdle() {
        if (flows.isEmpty()) return
        val now = nowNanos()
        val iterator = flows.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.lastSeenNanos > flowIdleNanos) iterator.remove()
        }
    }

    /** Bounds resident state by dropping the least recently touched flows. */
    private fun evictOverflow() {
        if (flows.size <= maxFlows) return
        // LinkedHashMap preserves insertion order; re-inserting on touch would give
        // true LRU, but flows are short-lived and the sweep handles the tail, so an
        // insertion-order trim is sufficient and cheaper on the packet path.
        val excess = flows.size - maxFlows
        val iterator = flows.keys.iterator()
        var removed = 0
        while (iterator.hasNext() && removed < excess) {
            iterator.next()
            iterator.remove()
            removed++
        }
    }

    /**
     * Identity of one direction of a TCP connection. Two flows are the same iff
     * client, server and both ports match, which is exactly the tuple that must be
     * kept separate when reassembling a ClientHello.
     */
    private class FlowKey(tcp: TcpPacket) {
        val srcAddress: ByteArray = tcp.srcAddress
        val dstAddress: ByteArray = tcp.dstAddress
        val srcPort: Int = tcp.srcPort
        val dstPort: Int = tcp.dstPort

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FlowKey) return false
            return srcPort == other.srcPort &&
                dstPort == other.dstPort &&
                srcAddress.contentEquals(other.srcAddress) &&
                dstAddress.contentEquals(other.dstAddress)
        }

        override fun hashCode(): Int {
            var h = srcPort
            h = 31 * h + dstPort
            h = 31 * h + srcAddress.contentHashCode()
            h = 31 * h + dstAddress.contentHashCode()
            return h
        }
    }

    private companion object {
        const val HTTPS_PORT = 443

        /** TLS handshake record content type. */
        const val TLS_HANDSHAKE = 0x16

        val EMPTY = ByteArray(0)
    }
}