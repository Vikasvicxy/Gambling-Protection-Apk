package dev.gamblock.protection.domainengine

import com.google.common.truth.Truth.assertThat
import dev.gamblock.core.model.BlockStatus
import dev.gamblock.core.model.Category
import dev.gamblock.core.model.DomainRecord
import org.junit.Test

class DomainIndexCompilerTest {

    private fun record(domain: String, status: BlockStatus = BlockStatus.ACTIVE) = DomainRecord(
        domain = domain,
        normalizedDomain = domain,
        category = Category.GAMBLING,
        status = status,
    )

    @Test
    fun `compiles active and allowlisted rules only`() {
        val records = listOf(
            record("bet-example.test", BlockStatus.ACTIVE),
            record("safe-example.test", BlockStatus.ALLOWLISTED),
            record("disabled.test", BlockStatus.DISABLED),
            record("candidate.test", BlockStatus.CANDIDATE),
            record("false-positive.test", BlockStatus.FALSE_POSITIVE),
        )
        val compiled = DomainIndexCompiler.compile(records, sourceVersion = 1)
        assertThat(compiled.enabledCount).isEqualTo(1)
        assertThat(compiled.allowlistCount).isEqualTo(1)
        assertThat(compiled.index.ruleCount).isEqualTo(2)
        assertThat(compiled.index.lookup("bet-example.test")).isInstanceOf(LookupResult.Blocked::class.java)
        assertThat(compiled.index.lookup("safe-example.test")).isInstanceOf(LookupResult.Allowed::class.java)
        assertThat(compiled.index.lookup("disabled.test")).isInstanceOf(LookupResult.Allowed::class.java)
    }

    @Test
    fun `blank input produces an empty index`() {
        val compiled = DomainIndexCompiler.compile(emptyList(), sourceVersion = 2)
        assertThat(compiled.enabledCount).isEqualTo(0)
        assertThat(compiled.allowlistCount).isEqualTo(0)
        assertThat(compiled.index.lookup("anything.test")).isInstanceOf(LookupResult.Allowed::class.java)
        assertThat(compiled.sourceVersion).isEqualTo(2)
        assertThat(compiled.index.ruleCount).isEqualTo(0)
    }

    @Test
    fun `integrity digest is deterministic and order independent`() {
        val a = listOf(
            record("a.test"),
            record("b.test"),
        )
        val b = listOf(
            record("b.test"),
            record("a.test"),
        )
        assertThat(DomainIndexCompiler.integrityDigest(a))
            .isEqualTo(DomainIndexCompiler.integrityDigest(b))
        assertThat(DomainIndexCompiler.integrityDigest(a)).isEqualTo(DomainIndexCompiler.integrityDigest(a))
    }

    @Test
    fun `integrity digest changes when a rule changes`() {
        val a = listOf(record("a.test"), record("b.test"))
        val c = listOf(record("a.test"), record("c.test"))
        assertThat(DomainIndexCompiler.integrityDigest(a))
            .isNotEqualTo(DomainIndexCompiler.integrityDigest(c))
    }

    @Test
    fun `compiled digest matches integrity digest of the records`() {
        val records = listOf(record("a.test"), record("b.test", BlockStatus.ALLOWLISTED))
        val compiled = DomainIndexCompiler.compile(records, sourceVersion = 1)
        assertThat(compiled.digest).isEqualTo(DomainIndexCompiler.integrityDigest(records))
    }
}