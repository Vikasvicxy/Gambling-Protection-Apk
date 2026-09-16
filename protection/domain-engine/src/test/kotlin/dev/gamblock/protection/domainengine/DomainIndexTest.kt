package dev.gamblock.protection.domainengine

import com.google.common.truth.Truth.assertThat
import dev.gamblock.core.model.Category
import dev.gamblock.core.model.Confidence
import dev.gamblock.core.model.RuleHit
import org.junit.Test

class DomainIndexTest {

    private fun block(domain: String) = domain to RuleHit(domain, Category.GAMBLING)
    private fun allow(domain: String) = domain to RuleHit(domain, Category.GAMBLING)

    // ---- exact matching ----

    @Test
    fun `exact match is blocked with EXACT kind`() {
        val index = DomainTrieIndex.build(listOf(block("bet-example.test")), emptyList())
        val result = index.lookup("bet-example.test")
        assertThat(result).isInstanceOf(LookupResult.Blocked::class.java)
        val hit = (result as LookupResult.Blocked).rule
        assertThat(hit.normalizedDomain).isEqualTo("bet-example.test")
        assertThat(hit.matchedAs).isEqualTo(RuleHit.MatchKind.EXACT)
    }

    // ---- subdomain matching ----

    @Test
    fun `subdomain of a blocked domain is blocked`() {
        val index = DomainTrieIndex.build(listOf(block("bet-example.test")), emptyList())
        assertThat((index.lookup("www.bet-example.test") as LookupResult.Blocked).rule.matchedAs)
            .isEqualTo(RuleHit.MatchKind.SUBDOMAIN)
        assertThat((index.lookup("deep.sub.bet-example.test") as LookupResult.Blocked).rule.matchedAs)
            .isEqualTo(RuleHit.MatchKind.SUBDOMAIN)
    }

    @Test
    fun `unrelated domain with common TLD is not blocked`() {
        val index = DomainTrieIndex.build(listOf(block("bet-example.test")), emptyList())
        assertThat(index.lookup("safe-example.test")).isEqualTo(LookupResult.Allowed())
        assertThat(index.lookup("notbet-example.test")).isEqualTo(LookupResult.Allowed())
        assertThat(index.lookup("example.test")).isEqualTo(LookupResult.Allowed())
    }

    @Test
    fun `tld-only rule never matches subdomains`() {
        val index = DomainTrieIndex.build(listOf(block("test")), emptyList())
        assertThat(index.lookup("example.test")).isEqualTo(LookupResult.Allowed())
        // A bare-label rule only ever blocks that exact label.
        assertThat(index.lookup("test")).isInstanceOf(LookupResult.Blocked::class.java)
    }

    @Test
    fun `most specific rule wins for deep hosts`() {
        val parent = block("example.com")
        val child = block("www.example.com")
        val index = DomainTrieIndex.build(listOf(parent, child), emptyList())
        val deep = (index.lookup("a.b.www.example.com") as LookupResult.Blocked).rule
        assertThat(deep.normalizedDomain).isEqualTo("www.example.com")
        assertThat(deep.matchedAs).isEqualTo(RuleHit.MatchKind.SUBDOMAIN)
    }

    // ---- allowlist overlay ----

    @Test
    fun `allowlist exact beats blocklist exact tie`() {
        val index = DomainTrieIndex.build(listOf(block("bet-example.test")), listOf(allow("bet-example.test")))
        assertThat(index.lookup("bet-example.test")).isEqualTo(LookupResult.Allowed(allow("bet-example.test").second))
    }

    @Test
    fun `allowlist subdomain beats blocklist parent`() {
        val index = DomainTrieIndex.build(listOf(block("bet-example.test")), listOf(allow("www.bet-example.test")))
        val result = index.lookup("www.bet-example.test")
        assertThat(result).isInstanceOf(LookupResult.Allowed::class.java)
        val hit = (result as LookupResult.Allowed).rule
        assertThat(hit!!.normalizedDomain).isEqualTo("www.bet-example.test")
        assertThat(hit.matchedAs).isEqualTo(RuleHit.MatchKind.EXACT)
    }

    @Test
    fun `allowlist child allows deeper subdomains when more specific than a block`() {
        val index = DomainTrieIndex.build(listOf(block("bet-example.test")), listOf(allow("www.bet-example.test")))
        assertThat(index.lookup("x.www.bet-example.test")).isInstanceOf(LookupResult.Allowed::class.java)
    }

    @Test
    fun `exact block on an explicit host beats allowlist parent`() {
        // Documented tie-break: exact matches are resolved before suffix rules.
        val index = DomainTrieIndex.build(listOf(block("sub.bet-example.test")), listOf(allow("bet-example.test")))
        assertThat(index.lookup("sub.bet-example.test")).isInstanceOf(LookupResult.Blocked::class.java)
        // ...but a deeper, non-exact host under the allowlisted parent is allowed.
        assertThat(index.lookup("deep.sub.bet-example.test")).isInstanceOf(LookupResult.Allowed::class.java)
    }

    @Test
    fun `exact block on a host beats allowlist parent`() {
        // Documented tie-break: exact matches are resolved before suffix rules.
        val index = DomainTrieIndex.build(listOf(block("www.bet-example.test")), listOf(allow("bet-example.test")))
        assertThat(index.lookup("www.bet-example.test")).isInstanceOf(LookupResult.Blocked::class.java)
    }

    @Test
    fun `allowlist only index returns allowed for unrelated and matches subdomains`() {
        val index = DomainTrieIndex.build(emptyList(), listOf(allow("safe-example.test")))
        assertThat(index.lookup("safe-example.test")).isInstanceOf(LookupResult.Allowed::class.java)
        assertThat(index.lookup("www.safe-example.test")).isInstanceOf(LookupResult.Allowed::class.java)
        assertThat(index.lookup("bet-example.test")).isEqualTo(LookupResult.Allowed())
    }

    // ---- counts & metadata ----

    @Test
    fun `ruleCount covers both maps`() {
        val index = DomainTrieIndex.build(listOf(block("a.com"), block("b.com")), listOf(allow("safe.test")))
        assertThat(index.ruleCount).isEqualTo(3)
    }

    @Test
    fun `containsExactBlocked reflects only exact block rules`() {
        val index = DomainTrieIndex.build(listOf(block("bet-example.test")), emptyList())
        assertThat(index.containsExactBlocked("bet-example.test")).isTrue()
        assertThat(index.containsExactBlocked("www.bet-example.test")).isFalse()
    }
}