package dev.gamblock.core.release

/**
 * Pure delta planning + verification.
 *
 * The on-device transactional application lives in `data:update`; this module holds the
 * parts that must be provably identical between the CI builder and the device verifier:
 *  - [plan]: diff two domain sets into add/remove/modify lists;
 *  - [ops]: turn a plan into ordered [DeltaOp] wire lines;
 *  - [applyTo]: replay a decoded delta against a domain map (used by tests to prove the
 *    target set is reached exactly).
 */
object DeltaEngine {

    data class DeltaPlan(
        val added: List<ReleaseDomainRecord>,
        val removed: List<String>,
        val modified: List<ReleaseDomainRecord>,
    ) {
        val addedCount: Int get() = added.size
        val removedCount: Int get() = removed.size
        val modifiedCount: Int get() = modified.size
    }

    /**
     * Builds the delta from [previous] to [next] (keyed by normalized domain).
     * Requires [nextVersion] == [previousVersion] + 1 (forward-only, one hop).
     */
    fun plan(
        previous: Map<String, ReleaseDomainRecord>,
        next: Map<String, ReleaseDomainRecord>,
        previousVersion: Int,
        nextVersion: Int,
    ): DeltaPlan {
        require(nextVersion == previousVersion + 1) {
            "delta is single-hop forward: $previousVersion -> $nextVersion"
        }
        val added = ArrayList<ReleaseDomainRecord>()
        val removed = ArrayList<String>()
        val modified = ArrayList<ReleaseDomainRecord>()
        for ((domain, record) in next) {
            val prev = previous[domain]
            when {
                prev == null -> added.add(record)
                prev != record -> modified.add(record)
            }
        }
        for (domain in previous.keys) {
            if (!next.containsKey(domain)) removed.add(domain)
        }
        // Deterministic ordering for stable artifacts.
        added.sortBy { it.normalizedDomain }
        modified.sortBy { it.normalizedDomain }
        removed.sort()
        return DeltaPlan(added, removed, modified)
    }

    fun ops(plan: DeltaPlan, baseVersion: Int, targetVersion: Int): List<DeltaOp> {
        val ops = ArrayList<DeltaOp>(plan.addedCount + plan.removedCount + plan.modifiedCount + 1)
        ops.add(
            DeltaOp.Header(
                base = baseVersion,
                target = targetVersion,
                added = plan.addedCount,
                removed = plan.removedCount,
                modified = plan.modifiedCount,
            ),
        )
        ops.addAll(plan.added.map { DeltaOp.Add(record = it) })
        ops.addAll(plan.modified.map { DeltaOp.Modify(record = it) })
        ops.addAll(plan.removed.map { DeltaOp.Remove(domain = it) })
        return ops
    }

    /**
     * Validates a decoded delta against the manifest's embedded counts and structure.
     * Returns the ops grouped by kind for application.
     */
    fun validateAndGroup(
        ops: List<DeltaOp>,
        declared: DeltaArtifact,
    ): DeltaPlan {
        val added = ArrayList<ReleaseDomainRecord>()
        val modified = ArrayList<ReleaseDomainRecord>()
        val removed = ArrayList<String>()
        var sawHeader = false
        for (op in ops) {
            when (op) {
                is DeltaOp.Header -> {
                    if (sawHeader) throw ReleaseValidationException("delta has multiple headers")
                    sawHeader = true
                    if (op.base != declared.baseVersion) {
                        throw ReleaseValidationException("delta base ${op.base} != artifact base ${declared.baseVersion}")
                    }
                    if (op.target != declared.targetVersion) {
                        throw ReleaseValidationException("delta target ${op.target} != artifact target ${declared.targetVersion}")
                    }
                }
                is DeltaOp.Add -> added.add(op.record)
                is DeltaOp.Modify -> modified.add(op.record)
                is DeltaOp.Remove -> removed.add(op.domain)
            }
        }
        if (!sawHeader) throw ReleaseValidationException("delta missing header")

        if (added.size != declared.addedCount || modified.size != declared.modifiedCount || removed.size != declared.removedCount) {
            throw ReleaseValidationException(
                "delta counts mismatch: manifest added=${declared.addedCount} got=${added.size}, " +
                    "modified=${declared.modifiedCount} got=${modified.size}, removed=${declared.removedCount} got=${removed.size}",
            )
        }
        val records = added + modified
        for (record in records) {
            val normalized = dev.gamblock.protection.domainengine.DomainNormalizer.normalize(record.normalizedDomain)
            if (normalized != record.normalizedDomain) {
                throw ReleaseValidationException("delta record invalid domain '${record.normalizedDomain}'")
            }
        }
        val dupRemoved = removed.toHashSet()
        if (dupRemoved.size != removed.size) throw ReleaseValidationException("delta contains duplicate remove entries")
        val dupAdded = records.associateBy { it.normalizedDomain }
        if (dupAdded.size != records.size) throw ReleaseValidationException("delta contains duplicate add/modify entries")
        return DeltaPlan(added = added, removed = removed, modified = modified)
    }

    /** Pure replay used by tests to prove a delta reaches exactly the target set. */
    fun applyTo(
        current: Map<String, ReleaseDomainRecord>,
        plan: DeltaPlan,
    ): Map<String, ReleaseDomainRecord> {
        val next = HashMap<String, ReleaseDomainRecord>(current)
        for (domain in plan.removed) next.remove(domain)
        for (record in plan.added) next[record.normalizedDomain] = record
        for (record in plan.modified) next[record.normalizedDomain] = record
        return next
    }
}