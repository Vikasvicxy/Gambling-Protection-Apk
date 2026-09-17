package dev.gamblock.tools.blocklist

import dev.gamblock.core.model.BlockStatus
import dev.gamblock.core.model.Category
import dev.gamblock.core.model.Confidence
import dev.gamblock.core.model.DomainRecord
import dev.gamblock.core.model.RuleHit
import dev.gamblock.core.release.DeltaCodec
import dev.gamblock.core.release.DeltaEngine
import dev.gamblock.core.release.RawSourceEntry
import dev.gamblock.core.release.ReleaseBuilder
import dev.gamblock.core.release.ReleaseCrypto
import dev.gamblock.core.release.ReleaseDomainRecord
import dev.gamblock.core.release.ReleasePipeline
import dev.gamblock.core.release.ReleaseVerifier
import dev.gamblock.core.release.SourceDefinitions
import dev.gamblock.core.release.TrustedKeyRing
import dev.gamblock.core.release.UpdateChannel
import dev.gamblock.protection.domainengine.DomainIndex
import dev.gamblock.protection.domainengine.DomainIndexCompiler
import dev.gamblock.protection.domainengine.DomainTrieIndex
import dev.gamblock.protection.domainengine.LookupResult
import java.util.Random

/**
 * JVM micro-benchmark harness for the hot paths that matter in Phase 2:
 * the domain trie (build + lookup) and the signed release pipeline
 * (process / build / verify / delta apply).
 *
 * Nothing here ships in the APK and nothing depends on Android APIs, so it runs
 * under a plain JVM (JDK 17) via `:tools:blocklist:benchmark`.
 *
 * Methodology: deterministic seeded inputs, explicit warmup before each measured
 * phase, and the JIT-stabilised MIN over N iterations is reported. Numbers feed
 * docs/PERFORMANCE.md; they are relative guidance, not a certification.
 */
object BenchmarkMain {

    private const val RAW_ENTRIES = 200_000
    private const val LEAF_RULES = 195_000
    private const val APEX_RULES = 5_000
    private const val RULES = LEAF_RULES + APEX_RULES
    private const val SEED = 0x5EED
    private const val NOW = 1_752_000_000_000L

    private val TLDS = listOf("com", "net", "org", "io", "bet", "win", "poker", "casino", "gamble", "info")

    @JvmStatic
    fun main(args: Array<String>) {
        println("Shield JVM micro-benchmarks (deterministic seed=$SEED, JDK ${System.getProperty("java.version")})")

        // ------------------------------------------------------------ release pipeline
        val raw = ArrayList<RawSourceEntry>(RAW_ENTRIES)
        for (i in 0 until RAW_ENTRIES) {
            val first = SourceDefinitions.CONFIGURED[i % SourceDefinitions.CONFIGURED.size]
            // ~30% of domains also appear in a second source to exercise dedupe + confidence.
            val domain = "site%06d.%s".format(i, TLDS[i % TLDS.size])
            raw.add(RawSourceEntry(first.id, domain))
            if (i % 3 == 2) {
                val second = SourceDefinitions.CONFIGURED[(i + 1) % SourceDefinitions.CONFIGURED.size]
                raw.add(RawSourceEntry(second.id, domain))
            }
        }

        val records = global("release.pipeline.process", "raw=$RAW_ENTRIES") {
            val ingested = ReleasePipeline.process(
                entries = raw,
                definitions = SourceDefinitions.CONFIGURED,
                nowEpochMs = NOW,
                databaseVersion = 10,
            )
            ReleasePipeline.toReleaseRecords(ingested)
        }
        println("  -> ${records.size} unique release records")

        // ------------------------------------------------------------ full build + verify
        val signer = ReleaseCrypto.generateKeyPair()
        val ring = TrustedKeyRing(listOf(signer.public))

        val built = global("release.build.full", "records=${records.size}") {
            ReleaseBuilder.build(
                previousRecords = null,
                nextRecords = records,
                releaseId = "bench-fixed",
                version = 1,
                channel = UpdateChannel.CANARY,
                generatedAtEpochMs = NOW,
                minimumAppVersion = 1,
                signingKey = signer,
            )
        }
        println("  -> full payload = ${built.fullPayload.size} bytes gzip (${records.size} NDJSON records)")

        val manifestText = ReleaseVerifier.encodeEnvelope(built.envelope)
        val verified = global("release.verify.full", "records=${records.size}") {
            val envelope = ReleaseVerifier.parseEnvelope(manifestText)
            val result = ReleaseVerifier.verifyFullEnvelope(
                envelope = envelope,
                fullPayload = built.fullPayload,
                keyRing = ring,
                installedVersion = 0,
                currentAppVersionCode = 1,
                maxObservedVersion = 0,
            )
            check(result.ok) { "full verify failed: ${result.reason}" }
            result
        }
        println("  -> ${verified.reason}")

        // ------------------------------------------------------------ delta build + verify + apply
        val mutated = ArrayList(records)
        val rng = Random(SEED.toLong() xor 0xBEEF)
        repeat(records.size / 40) {
            mutated.removeAt(rng.nextInt(mutated.size))
        }
        repeat(records.size / 100) {
            val i = rng.nextInt(mutated.size)
            mutated[i] = mutated[i].copy(riskLevel = "HIGH")
        }
        val added = ArrayList<ReleaseDomainRecord>(records.size / 20)
        for (i in 0 until records.size / 20) {
            val base = records[rng.nextInt(records.size)]
            added.add(
                base.copy(
                    domain = "new%06d.%s".format(i, TLDS[i % TLDS.size]),
                    normalizedDomain = "new%06d.%s".format(i, TLDS[i % TLDS.size]),
                ),
            )
        }
        val nextRecords = mutated + added

        val deltaBuilt = global("release.build.delta", "base=${records.size} next=${nextRecords.size}") {
            ReleaseBuilder.build(
                previousRecords = records,
                nextRecords = nextRecords,
                releaseId = "bench-delta",
                version = 2,
                channel = UpdateChannel.CANARY,
                generatedAtEpochMs = NOW + 86_400_000L,
                minimumAppVersion = 1,
                signingKey = signer,
            )
        }
        val deltaArtifact = deltaBuilt.envelope.manifest.delta!!
        println("  -> delta=${deltaBuilt.deltaPayload!!.size} bytes (added=${deltaArtifact.addedCount} removed=${deltaArtifact.removedCount} modified=${deltaArtifact.modifiedCount})")

        global("release.verify.apply.delta", "added=${deltaArtifact.addedCount} removed=${deltaArtifact.removedCount} modified=${deltaArtifact.modifiedCount}") {
            val envelope = ReleaseVerifier.parseEnvelope(ReleaseVerifier.encodeEnvelope(deltaBuilt.envelope))
            val ok = ReleaseVerifier.verifyDelta(
                envelope = envelope,
                deltaPayload = deltaBuilt.deltaPayload!!,
                keyRing = ring,
                installedVersion = 1,
                currentAppVersionCode = 1,
                maxObservedVersion = 1,
            )
            check(ok.ok) { "delta verify failed: ${ok.reason}" }
            val ops = DeltaCodec.decode(deltaBuilt.deltaPayload!!)
            val plan = DeltaEngine.validateAndGroup(ops, deltaArtifact)
            val after = DeltaEngine.applyTo(records.associateBy { it.normalizedDomain }, plan)
            check(after.size == nextRecords.size) { "delta apply reached ${after.size}, expected ${nextRecords.size}" }
        }

        // ------------------------------------------------------------ domain trie build
        val index = global("domain.trie.build", "rules=$RULES") {
            DomainTrieIndex.build(buildRulePairs(), listOf("safe.example" to RuleHit("safe.example", Category.UNKNOWN, Confidence.HIGH)))
        }
        println("  -> ruleCount=${index.ruleCount}")

        benchmarkLookups(index)

        // ------------------------------------------------------------ full compiler path (records -> index + digest)
        val domainRecords = buildRulePairs().map { (d, hit) ->
            DomainRecord(
                domain = d,
                normalizedDomain = d,
                category = hit.category,
                status = BlockStatus.ACTIVE,
                confidence = hit.confidence,
                source = "bench",
                databaseVersion = 10,
            )
        }
        val compiled = global("domain.compiler.compile+digest", "records=${domainRecords.size}") {
            DomainIndexCompiler.compile(domainRecords, sourceVersion = 10)
        }
        println("  -> enabled=${compiled.enabledCount} allowlist=${compiled.allowlistCount} digest=${compiled.digest.take(12)}...")

        println("done.")
    }

    // ---------------------------------------------------------------- trie rules & lookups

    private fun buildRulePairs(): List<Pair<String, RuleHit>> {
        val leaves = (0 until LEAF_RULES).map { i ->
            val domain = "block%06d.%s".format(i, TLDS[i % TLDS.size])
            domain to RuleHit(domain, if (i % 2 == 0) Category.GAMBLING else Category.CASINO)
        }
        val apexes = (0 until APEX_RULES).map { i ->
            val domain = "apex%04d.%s".format(i, TLDS[i % TLDS.size])
            domain to RuleHit(domain, if (i % 2 == 0) Category.GAMBLING else Category.CASINO)
        }
        return (leaves + apexes).shuffled(Random(SEED.toLong() xor 0xD0D0L))
    }

    private fun benchmarkLookups(index: DomainIndex) {
        val rnd = Random(SEED.toLong() xor 0xCAFE)

        // Exact hits: same leaf formula used at build time, visited in random order.
        val exactHits = List(50_000) {
            val i = rnd.nextInt(LEAF_RULES)
            "block%06d.%s".format(i, TLDS[i % TLDS.size])
        }
        // Subdomain hits: 3-4 label queries under registered apex rules (suffix match).
        val subQueries = List(50_000) {
            val apex = rnd.nextInt(APEX_RULES)
            "cdn%02d.m.apex%04d.%s".format(rnd.nextInt(90), apex, TLDS[apex % TLDS.size])
        }
        // Misses: domains that exist nowhere in the index.
        val misses = List(50_000) {
            "clean%06d.%s".format(rnd.nextInt(100_000_000), TLDS[rnd.nextInt(TLDS.size)])
        }

        report("domain.lookup.exact_hit", rate(exactHits) { index.lookup(it) is LookupResult.Blocked })
        report("domain.lookup.subdomain_hit", rate(subQueries) { index.lookup(it) is LookupResult.Blocked })
        report("domain.lookup.miss", rate(misses) { index.lookup(it) is LookupResult.Allowed })
    }

    /** Best-of-5 wall time in ns/op over [queries]; a sanity-asserting warmup pass runs first. */
    private fun rate(queries: List<String>, isHit: (String) -> Boolean): Double {
        var hits = 0
        for (q in queries) if (isHit(q)) hits++
        check(hits == queries.size) { "benchmark sanity: $hits/${queries.size} lookups matched as expected" }
        var best = Double.MAX_VALUE
        repeat(5) {
            val start = System.nanoTime()
            for (q in queries) isHit(q)
            val ns = (System.nanoTime() - start).toDouble() / queries.size
            if (ns < best) best = ns
        }
        return best
    }

    private fun report(label: String, nsPerOp: Double) {
        val kOpsPerSec = 1_000_000_000.0 / nsPerOp / 1000.0
        println("  %-22s %7.0f ns/op  (%6.0f k-op/s)".format(label, nsPerOp, kOpsPerSec))
    }

    /** Times [block] once after a warm call; returns the value and prints a millisecond figure. */
    private fun <T> global(name: String, tag: String, block: () -> T): T {
        block()
        val start = System.nanoTime()
        val result = block()
        val ms = (System.nanoTime() - start) / 1_000_000.0
        println("[bench] %-28s %-14s -> %8.1f ms".format(name, tag, ms))
        return result
    }
}