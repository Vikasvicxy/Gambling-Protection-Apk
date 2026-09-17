package dev.gamblock.core.admin

import dev.gamblock.core.model.AuditAction
import dev.gamblock.core.model.AuditLogEntry

/**
 * In-memory audit logger. Server deployments should back this with durable storage
 * (D1/KV) - the interface is kept tiny so swapping implementations is trivial.
 */
interface AuditLogger {
    fun log(entry: AuditLogEntry)
    fun query(limit: Int = 100): List<AuditLogEntry>
    fun count(): Int
}

class InMemoryAuditLogger(
    private val clock: () -> Long = System::currentTimeMillis,
) : AuditLogger {
    private val entries = mutableListOf<AuditLogEntry>()

    override fun log(entry: AuditLogEntry) {
        synchronized(entries) {
            entries.add(entry)
        }
    }

    override fun query(limit: Int): List<AuditLogEntry> = synchronized(entries) {
        entries.takeLast(limit).reversed()
    }

    override fun count(): Int = synchronized(entries) { entries.size }
}

/** Source-of-truth ordering helper for audit queries. */
object AuditLogFilters {
    fun byAction(entries: List<AuditLogEntry>, action: AuditAction): List<AuditLogEntry> =
        entries.filter { it.action == action }
}