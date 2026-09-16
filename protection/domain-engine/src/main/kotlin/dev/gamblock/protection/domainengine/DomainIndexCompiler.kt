package dev.gamblock.protection.domainengine

import dev.gamblock.core.model.BlockStatus
import dev.gamblock.core.model.DomainRecord
import dev.gamblock.core.model.RuleHit
import java.security.MessageDigest

/** A compiled, immutable lookup index plus its integrity digest. */
data class CompiledIndex(
    val index: DomainIndex,
    val digest: String,
    val enabledCount: Int,
    val allowlistCount: Int,
    val sourceVersion: Int,
)

/**
 * Builds the in-memory [DomainIndex] from persisted [DomainRecord]s.
 * ACTIVE rules block; ALLOWLISTED rules override; all other states are ignored.
 */
object DomainIndexCompiler {

    fun compile(records: List<DomainRecord>, sourceVersion: Int): CompiledIndex {
        val blocked = ArrayList<Pair<String, RuleHit>>(records.size)
        val allowed = ArrayList<Pair<String, RuleHit>>(16)

        for (record in records) {
            if (record.status != BlockStatus.ACTIVE && record.status != BlockStatus.ALLOWLISTED) continue
            val hit = RuleHit(
                normalizedDomain = record.normalizedDomain,
                category = record.category,
                confidence = record.confidence,
            )
            if (record.status == BlockStatus.ALLOWLISTED) {
                allowed.add(record.normalizedDomain to hit.copy(category = record.category))
            } else {
                blocked.add(record.normalizedDomain to hit)
            }
        }

        val index = DomainTrieIndex.build(blocked, allowed)
        val digest = integrityDigest(records)
        return CompiledIndex(
            index = index,
            digest = digest,
            enabledCount = blocked.size,
            allowlistCount = allowed.size,
            sourceVersion = sourceVersion,
        )
    }

    /** Digest of the canonical record list - used to detect silent corruption. */
    fun integrityDigest(records: List<DomainRecord>): String {
        val md = MessageDigest.getInstance("SHA-256")
        val sorted = records.map { it.normalizedDomain }.sorted()
        for (domain in sorted) {
            md.update(domain.toByteArray(Charsets.UTF_8))
            md.update(0)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}