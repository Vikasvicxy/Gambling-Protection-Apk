package dev.gamblock.protection.domainengine

import com.google.common.truth.Truth.assertThat
import dev.gamblock.core.model.Category
import dev.gamblock.core.model.DecisionKind
import dev.gamblock.core.model.RuleHit
import org.junit.Test

class DecisionEngineTest {

    private fun engineWith(vararg blocks: String, allows: List<String> = emptyList()): DecisionEngine {
        val blocked = blocks.map { it to RuleHit(it, Category.GAMBLING) }
        val allowed = allows.map { it to RuleHit(it, Category.GAMBLING) }
        return DecisionEngine(DomainTrieIndex.build(blocked, allowed))
    }

    @Test
    fun `blocks a matching domain when schedule is active`() {
        val engine = engineWith("bet-example.test")
        val decision = engine.decide("bet-example.test", scheduleActive = true)
        assertThat(decision.decision).isEqualTo(DecisionKind.BLOCK)
        assertThat(decision.ruleHit!!.normalizedDomain).isEqualTo("bet-example.test")
        assertThat(decision.reason).contains("blocklist rule")
    }

    @Test
    fun `blocks subdomains when schedule is active`() {
        val engine = engineWith("bet-example.test")
        val decision = engine.decide("www.bet-example.test", scheduleActive = true)
        assertThat(decision.decision).isEqualTo(DecisionKind.BLOCK)
        assertThat(decision.ruleHit!!.matchedAs).isEqualTo(RuleHit.MatchKind.SUBDOMAIN)
    }

    @Test
    fun `allows when no rule matches`() {
        val engine = engineWith("bet-example.test")
        val decision = engine.decide("example.test", scheduleActive = true)
        assertThat(decision.decision).isEqualTo(DecisionKind.ALLOW)
        assertThat(decision.ruleHit).isNull()
        assertThat(decision.reason).contains("no matching rule")
    }

    @Test
    fun `allowlist rule wins over blocklist rule`() {
        val engine = engineWith("bet-example.test", allows = listOf("www.bet-example.test"))
        val decision = engine.decide("www.bet-example.test", scheduleActive = true)
        assertThat(decision.decision).isEqualTo(DecisionKind.ALLOW)
        assertThat(decision.ruleHit!!.normalizedDomain).isEqualTo("www.bet-example.test")
        assertThat(decision.reason).contains("allowlist rule")
    }

    @Test
    fun `schedule gate forces allow with explanatory reason`() {
        val engine = engineWith("bet-example.test")
        val decision = engine.decide("bet-example.test", scheduleActive = false)
        assertThat(decision.decision).isEqualTo(DecisionKind.ALLOW)
        assertThat(decision.reason).contains("schedule")
        assertThat(engine.decide("bet-example.test", scheduleActive = true).decision)
            .isEqualTo(DecisionKind.BLOCK)
    }

    @Test
    fun `malformed hosts are never blocked`() {
        val engine = engineWith("bet-example.test")
        for (host in listOf("not a domain", "bet_example.test", "..", "1.2.3.4")) {
            val decision = engine.decide(host, scheduleActive = true)
            assertThat(decision.decision).isEqualTo(DecisionKind.ALLOW)
            assertThat(decision.reason).contains("malformed")
        }
    }

    @Test
    fun `hostname with numeric port is normalized and still matched`() {
        // Port stripping is part of normalisation (user input path), not a DNS qname concern.
        val engine = engineWith("bet-example.test")
        assertThat(engine.decide("bet-example.test:999", true).decision).isEqualTo(DecisionKind.BLOCK)
    }

    @Test
    fun `schedule gate applies before malformed rejection`() {
        val engine = engineWith("bet-example.test")
        assertThat(engine.decide("not a domain", scheduleActive = false).decision)
            .isEqualTo(DecisionKind.ALLOW)
    }

    @Test
    fun `signatures are stable for the same decision`() {
        val engine = engineWith("bet-example.test")
        val a = engine.decide("bet-example.test", true)
        val b = engine.decide("bet-example.test", true)
        assertThat(a.signature).isEqualTo(b.signature)
    }

    @Test
    fun `signatures differ across outcomes`() {
        val engine = engineWith("bet-example.test")
        val blocked = engine.decide("bet-example.test", true)
        val allowed = engine.decide("safe-example.test", true)
        assertThat(blocked.signature).isNotEqualTo(allowed.signature)
    }

    @Test
    fun `hostname passed through normalizer before matching`() {
        val engine = engineWith("bet-example.test")
        assertThat(engine.decide("  BET-EXAMPLE.TEST. ", true).decision).isEqualTo(DecisionKind.BLOCK)
    }
}