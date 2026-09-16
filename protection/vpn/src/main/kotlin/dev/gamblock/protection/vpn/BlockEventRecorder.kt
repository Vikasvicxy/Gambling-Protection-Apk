package dev.gamblock.protection.vpn

import dev.gamblock.core.model.BlockDecision

/**
 * Transport records a blocked DNS attempt. Implemented by the persistence layer
 * and wired at app level to avoid a repo -> vpn dependency cycle. Called on the
 * tun reader thread, so implementations must not block (fire-and-forget).
 */
interface BlockEventRecorder {
    fun recordBlocked(domain: String, decision: BlockDecision)
}