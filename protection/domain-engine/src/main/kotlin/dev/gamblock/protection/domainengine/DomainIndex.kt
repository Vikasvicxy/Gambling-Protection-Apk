package dev.gamblock.protection.domainengine

import dev.gamblock.core.model.RuleHit

/** Outcome of a lookup against the compiled in-memory domain index. */
sealed interface LookupResult {
    data class Blocked(val rule: RuleHit) : LookupResult
    data class Allowed(val rule: RuleHit? = null) : LookupResult

    val matchedRule: RuleHit?
        get() = when (this) {
            is Blocked -> rule
            is Allowed -> rule
        }
}

/**
 * High-performance in-memory domain index.
 *
 * Design (see PERFORMANCE.md for benchmark reasoning):
 *  - exact-match hash maps for the hot path (O(1));
 *  - a reversed-label trie for suffix (subdomain) matching, so blocking `example.com`
 *    also blocks `www.example.com` / `m.example.com` / `login.example.com` without
 *    materializing every subdomain;
 *  - a compiled allowlist overlay that wins every tie (length-tie and contradiction).
 *
 * Queries never hit SQLite: the index is (re)built from Room at boot/load time.
 * This scales to millions of rules with suffix behaviour.
 */
interface DomainIndex {
    val ruleCount: Int
    fun lookup(normalizedDomain: String): LookupResult
    fun containsExactBlocked(normalizedDomain: String): Boolean
}

class DomainTrieIndex private constructor(
    private val blockedRoot: TrieNode,
    private val allowRoot: TrieNode,
    private val exactBlocked: Map<String, RuleHit>,
    private val exactAllowed: Map<String, RuleHit>,
    override val ruleCount: Int,
) : DomainIndex {

    private class TrieNode {
        val children = LinkedHashMap<String, TrieNode>(4)
        var rule: RuleHit? = null
        fun findOrCreate(label: String): TrieNode = children.getOrPut(label) { TrieNode() }
    }

    override fun lookup(normalizedDomain: String): LookupResult {
        val allowExact = exactAllowed[normalizedDomain]
        if (allowExact != null) return LookupResult.Allowed(allowExact)

        val blockExact = exactBlocked[normalizedDomain]
        if (blockExact != null) return LookupResult.Blocked(blockExact)

        val labels = normalizedDomain.split('.')
        var bestAllow: RuleHit? = null
        var bestBlock: RuleHit? = null

        // Longest candidate first (= starting label index ascending).
        for (startFrom in 0..labels.size - 2) {
            if (bestAllow == null) bestAllow = trieRuleFor(allowRoot, labels, startFrom)
            if (bestBlock == null) bestBlock = trieRuleFor(blockedRoot, labels, startFrom)
            if (bestAllow != null && bestBlock != null) break
        }

        return when {
            bestAllow != null -> LookupResult.Allowed(bestAllow.copy(matchedAs = RuleHit.MatchKind.SUBDOMAIN))
            bestBlock != null -> LookupResult.Blocked(bestBlock.copy(matchedAs = RuleHit.MatchKind.SUBDOMAIN))
            else -> LookupResult.Allowed()
        }
    }

    override fun containsExactBlocked(normalizedDomain: String): Boolean =
        exactBlocked.containsKey(normalizedDomain)

    /** Returns the deepest rule along the trie path labels[last..startFrom]. */
    private fun trieRuleFor(root: TrieNode, labels: List<String>, startFrom: Int): RuleHit? {
        var node = root
        for (i in labels.lastIndex downTo startFrom) {
            node = node.children[labels[i]] ?: return null
        }
        return node.rule
    }

    companion object {
        fun build(entries: List<Pair<String, RuleHit>>, allowEntries: List<Pair<String, RuleHit>>): DomainTrieIndex {
            val blockedRoot = TrieNode()
            val allowRoot = TrieNode()
            val exactBlocked = HashMap<String, RuleHit>()
            val exactAllowed = HashMap<String, RuleHit>()

            fun insert(root: TrieNode, exact: HashMap<String, RuleHit>, domain: String, rule: RuleHit) {
                var node = root
                val labels = domain.split('.')
                for (i in labels.lastIndex downTo 0) {
                    node = node.findOrCreate(labels[i])
                }
                node.rule = rule
                exact[domain] = rule
            }

            entries.forEach { (domain, rule) -> insert(blockedRoot, exactBlocked, domain, rule) }
            allowEntries.forEach { (domain, rule) -> insert(allowRoot, exactAllowed, domain, rule) }

            return DomainTrieIndex(
                blockedRoot = blockedRoot,
                allowRoot = allowRoot,
                exactBlocked = exactBlocked,
                exactAllowed = exactAllowed,
                ruleCount = entries.size + allowEntries.size,
            )
        }
    }
}