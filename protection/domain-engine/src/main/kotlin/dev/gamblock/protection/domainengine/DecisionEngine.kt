package dev.gamblock.protection.domainengine

import dev.gamblock.core.model.util.stableHash
import dev.gamblock.core.model.BlockDecision
import dev.gamblock.core.model.DecisionKind
import dev.gamblock.core.model.EncryptedDnsPolicy

/**
 * Turns a [DomainIndex] lookup into a [BlockDecision]. The schedule gate is applied
 * by the caller (DNS server) because it is transport-aware; here we only need the
 * rule overlay semantics.
 */
class DecisionEngine(
    private val index: DomainIndex,
    private val blockDohBootstrap: Boolean = true,
) {
    fun decide(host: String, scheduleActive: Boolean): BlockDecision {
        val normalized = DomainNormalizer.normalize(host)
        if (normalized == null) {
            return BlockDecision(
                decision = DecisionKind.ALLOW,
                ruleHit = null,
                signature = stableHash("invalid:$host").toString(),
                reason = "malformed host: not blocked",
            )
        }
        if (!scheduleActive) {
            return BlockDecision(
                decision = DecisionKind.ALLOW,
                ruleHit = null,
                signature = stableHash("schedule:$normalized").toString(),
                reason = "outside active protection schedule",
            )
        }

        if (blockDohBootstrap && EncryptedDnsPolicy.isDohBootstrapHost(normalized)) {
            return BlockDecision(
                decision = DecisionKind.BLOCK,
                ruleHit = null,
                signature = stableHash("doh:$normalized").toString(),
                reason = "DNS-over-HTTPS bootstrap host: forces resolver back to local port 53",
            )
        }

        return when (val result = index.lookup(normalized)) {
            is LookupResult.Blocked -> BlockDecision(
                decision = DecisionKind.BLOCK,
                ruleHit = result.rule,
                signature = stableHash(result.rule.normalizedDomain).toString(),
                reason = "domain matched blocklist rule",
            )
            is LookupResult.Allowed -> BlockDecision(
                decision = DecisionKind.ALLOW,
                ruleHit = result.rule,
                signature = stableHash("allow:$normalized").toString(),
                reason = if (result.rule != null) "domain matched allowlist rule" else "no matching rule",
            )
        }
    }
}