package dev.gamblock.core.release

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReleasePipelineTest {

    private val defs = listOf(
        SourceDefinition(
            id = "stevenblack",
            name = "StevenBlack gambling-only",
            url = "https://example.invalid/hosts",
            license = "MIT",
            licenseUrl = "https://example.invalid/license.txt",
            format = SourceFormat.HOSTS,
            defaultCategory = "GAMBLING",
            reputation = 0.9,
            maxConfidence = "HIGH",
        ),
        SourceDefinition(
            id = "bigdargon",
            name = "HostsVN gambling",
            url = "https://example.invalid/hosts-VN",
            license = "MIT",
            licenseUrl = "https://example.invalid/LICENSE",
            format = SourceFormat.NDJSON,
            defaultCategory = "GAMBLING",
            reputation = 0.7,
            maxConfidence = "HIGH",
        ),
    )

    private fun raw(sourceId: String, domain: String) = RawSourceEntry(sourceId, domain)

    @Test
    fun `pipeline drops the unlicensed source before processing`() {
        val gpl = defs[0].copy(id = "hagezi", license = "GPL-3.0")
        val e = assertThrows<PipelineException> { ReleasePipeline.process(emptyList(), listOf(gpl), 1L, 1) }
        assertThat(e.message).contains("not in allowed set")
    }

    @Test
    fun `pipeline normalises and dedupes across sources`() {
        val entries = listOf(
            raw("stevenblack", "Casino-Bet.COM"),
            raw("bigdargon", "casino-bet.com/extra"),
            raw("bigdargon", " other-domain.example "),
        )
        val out = ReleasePipeline.process(entries, defs, nowEpochMs = 5L, databaseVersion = 7)
        // "Casino-Bet.COM" and "casino-bet.com/extra" both normalize to casino-bet.com.
        assertThat(out).hasSize(2)
        val casino = out.first { it.normalizedDomain == "casino-bet.com" }
        assertThat(casino.sourceIds).containsExactly("bigdargon", "stevenblack")
        assertThat(casino.category).isEqualTo("CASINO")
        assertThat(casino.status).isEqualTo("ACTIVE")
        assertThat(casino.databaseVersion).isEqualTo(7)
        assertThat(casino.firstSeenEpochMs).isEqualTo(5L)
    }

    @Test
    fun `critical infrastructure can never be ACTIVE`() {
        val entries = listOf(raw("stevenblack", "google.com"), raw("bigdargon", "android.com"))
        val out = ReleasePipeline.process(entries, defs, nowEpochMs = 1L, databaseVersion = 1)
        assertThat(out).hasSize(2)
        // Everything critical is demoted to CANDIDATE/LOW regardless of source trust.
        for (record in out) {
            assertThat(record.confidence).isEqualTo("LOW")
            assertThat(record.status).isEqualTo("CANDIDATE")
        }
    }

    @Test
    fun `user allowlist removes entries entirely`() {
        val entries = listOf(raw("stevenblack", "casino-bet.com"), raw("stevenblack", "poker.example"))
        val out = ReleasePipeline.process(entries, defs, nowEpochMs = 1L, databaseVersion = 1, userAllowlist = setOf("casino-bet.com"))
        assertThat(out.map { it.normalizedDomain }).containsExactly("poker.example")
    }

    @Test
    fun `single low-reputation source stays CANDIDATE not ACTIVE`() {
        val def = SourceDefinition(
            id = "lowTrust",
            name = "Low trust",
            url = "x",
            license = "MIT",
            licenseUrl = "y",
            format = SourceFormat.HOSTS,
            defaultCategory = "GAMBLING",
            reputation = 0.3,
            maxConfidence = "LOW",
        )
        val out = ReleasePipeline.process(listOf(raw("lowTrust", "random-casino.example")), listOf(def), 1L, 1)
        assertThat(out.single().status).isEqualTo("CANDIDATE")
    }

    @Test
    fun `classification picks most specific category`() {
        val (cat1, _) = ReleasePipeline.classifyForcedBlocklist("bet-cryptocasino.test")
        assertThat(cat1).isEqualTo("CRYPTO_GAMBLING")

        val (cat2, _) = ReleasePipeline.classifyForcedBlocklist("play-vegas-slots.test")
        assertThat(cat2).isEqualTo("CASINO")

        val (cat3, _) = ReleasePipeline.classifyForcedBlocklist("go-fast-csgobet.example")
        assertThat(cat3).isEqualTo("SKIN_GAMBLING")

        val (cat4, kw) = ReleasePipeline.classifyForcedBlocklist("plain-shop.example.test")
        assertThat(cat4).isNull()
        assertThat(kw).isNull()

        // Most-specific-first ordering: crypto keywords beat the generic CASINO term.
        val (cat5, keyword5) = ReleasePipeline.classifyForcedBlocklist("fiesta-csgocasino.test")
        assertThat(cat5).isEqualTo("CRYPTO_GAMBLING")
        assertThat(keyword5).isEqualTo("csgocasino")
    }

    @Test
    fun `pipeline results are sorted deterministically`() {
        val entries = listOf(raw("stevenblack", "zz.casino.test"), raw("bigdargon", "aa-casino.test"))
        val out = ReleasePipeline.process(entries, defs, nowEpochMs = 1L, databaseVersion = 1)
        val names = out.map { it.normalizedDomain }
        assertThat(names).isEqualTo(names.sorted())
    }

    inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            check(e is T) { "expected ${T::class.java.simpleName} but got ${e::class.java.simpleName}: ${e.message}" }
            return e
        }
        error("expected ${T::class.java.simpleName} but nothing was thrown")
    }
}