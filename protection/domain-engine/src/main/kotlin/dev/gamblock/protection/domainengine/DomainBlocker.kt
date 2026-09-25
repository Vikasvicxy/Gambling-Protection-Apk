package dev.gamblock.protection.domainengine

import dev.gamblock.core.model.BlockDecision

/**
 * Thread-safe facade the transport layer (DNS proxy) uses to evaluate a hostname.
 * Implementations must never block on I/O or SQLite on the hot path.
 */
interface DomainBlocker {
    /** Number of active (non-allowlist) rules in the currently compiled index. */
    val ruleCount: Int

    val isReady: Boolean
        get() = true

    /** Decides a host. [scheduleActive] gates enforcement (schedule windows). */
    fun decide(host: String, scheduleActive: Boolean): BlockDecision
}