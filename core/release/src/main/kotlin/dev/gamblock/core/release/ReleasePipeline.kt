package dev.gamblock.core.release

import dev.gamblock.protection.domainengine.DomainNormalizer

class PipelineException(message: String) : Exception(message)

enum class SourceFormat { HOSTS, DOMAINS, NDJSON }

/**
 * Configured upstream source. License verification is a hard gate: the pipeline refuses
 * to ingest a source whose [license] is not in [SourceDefinitions.ALLOWED_LICENSES].
 *
 * [reputation] (0..1) encodes how much we trust the source to be correct AND not
 * poisoned. The higher the reputation and the more independent sources agree, the
 * higher the confidence of the resulting rule.
 */
data class SourceDefinition(
    val id: String,
    val name: String,
    val url: String,
    val license: String,
    val licenseUrl: String,
    val format: SourceFormat,
    val defaultCategory: String,
    val reputation: Double,
    val maxConfidence: String,
)

/** One raw domain line as parsed from a source, before normalization. */
data class RawSourceEntry(
    val sourceId: String,
    val rawDomain: String,
)

/** Immutable processing result for one normalized domain. */
data class IngestedDomain(
    val domain: String,
    val normalizedDomain: String,
    val category: String,
    val confidence: String,
    val status: String,
    val riskLevel: String,
    val sourceIds: List<String>,
    val operatorId: String? = null,
    val brandId: String? = null,
    val mirrorOf: String? = null,
    val country: String? = null,
    val firstSeenEpochMs: Long,
    val lastVerifiedEpochMs: Long,
    val databaseVersion: Int,
    val appliesToSubdomains: Boolean,
)

object SourceDefinitions {

    /** Expressly verified permissive licenses accepted for data import (BLOCKLIST_LICENSES.md). */
    val ALLOWED_LICENSES: Set<String> = setOf(
        "MIT", "BSD-2-Clause", "BSD-3-Clause", "ISC", "Apache-2.0", "CC0-1.0", "Unlicense",
    )

    val CONFIGURED: List<SourceDefinition> = listOf(
        SourceDefinition(
            id = "stevenblack",
            name = "StevenBlack/hosts (gambling-only)",
            url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/gambling-only/hosts",
            license = "MIT",
            licenseUrl = "https://github.com/StevenBlack/hosts/blob/master/license.txt",
            format = SourceFormat.HOSTS,
            defaultCategory = "GAMBLING",
            reputation = 0.9,
            maxConfidence = "HIGH",
        ),
        SourceDefinition(
            id = "sinfonietta",
            name = "Sinfonietta/hostfiles gambling-hosts",
            url = "https://raw.githubusercontent.com/Sinfonietta/hostfiles/master/gambling-hosts",
            license = "MIT",
            licenseUrl = "https://github.com/Sinfonietta/hostfiles/blob/master/LICENSE",
            format = SourceFormat.HOSTS,
            defaultCategory = "GAMBLING",
            reputation = 0.75,
            maxConfidence = "MEDIUM",
        ),
        SourceDefinition(
            id = "bigdargon-gambling",
            name = "bigdargon/hostsVN gambling extension",
            url = "https://raw.githubusercontent.com/bigdargon/hostsVN/master/extensions/gambling/hosts-VN",
            license = "MIT",
            licenseUrl = "https://github.com/bigdargon/hostsVN/blob/master/LICENSE",
            format = SourceFormat.HOSTS,
            defaultCategory = "GAMBLING",
            reputation = 0.7,
            maxConfidence = "MEDIUM",
        ),
    )
}

private class MutableAccumulator(val domain: String, val wildcard: Boolean) {
    val sourceIds = LinkedHashSet<String>()
    var trustedMass = 0.0
}

/**
 * Pure pipeline: ingestion -> normalization -> deduplication -> classification ->
 * confidence scoring -> allowlist safety -> status selection.
 *
 * Deterministic and pure so the same logic is exercised by unit tests and by the CI
 * release job. Every status decision is independently testable.
 */
object ReleasePipeline {

    fun validateSourceLicenses(definitions: List<SourceDefinition>) {
        for (definition in definitions) {
            if (definition.license !in SourceDefinitions.ALLOWED_LICENSES) {
                throw PipelineException(
                    "source '${definition.id}' license '${definition.license}' not in allowed set; " +
                        "import refused until explicitly verified (BLOCKLIST_LICENSES.md)",
                )
            }
            if (definition.reputation !in 0.0..1.0) {
                throw PipelineException("source '${definition.id}' reputation out of range")
            }
        }
    }

    fun process(
        entries: List<RawSourceEntry>,
        definitions: List<SourceDefinition>,
        nowEpochMs: Long,
        databaseVersion: Int,
        userAllowlist: Set<String> = emptySet(),
        userBlocklist: Set<String> = emptySet(),
    ): List<IngestedDomain> {
        validateSourceLicenses(definitions)
        val defsById = definitions.associateBy { it.id }

        // 1+2. Normalize each raw line; unparseable entries are dropped defensively.
        val accumulated = LinkedHashMap<String, MutableAccumulator>()
        for (entry in entries) {
            val norm = DomainNormalizer.normalizeWithFlags(entry.rawDomain) ?: continue
            val acc = accumulated.getOrPut(norm.domain) { MutableAccumulator(norm.domain, norm.wildcard) }
            acc.sourceIds.add(entry.sourceId)
            acc.trustedMass += defsById[entry.sourceId]?.reputation ?: 0.0
        }

        // 3. Deduplicated map is now our working domain set.
        val result = ArrayList<IngestedDomain>(accumulated.size)
        for ((domain, acc) in accumulated) {
            if (domain in userAllowlist) continue
            val defs = acc.sourceIds.mapNotNull { defsById[it] }.distinctBy { it.id }

            val classification = classifyForcedBlocklist(domain)
            val category = classification.first ?: "GAMBLING"
            var confidence = computeConfidence(acc.sourceIds.size, defs)
            var status = if (domain in userBlocklist) "ACTIVE" else selectStatus(confidence, defs)

            if (CriticalAllowlist.isProtected(domain)) {
                confidence = "LOW"
                status = "CANDIDATE"
            }

            result.add(
                IngestedDomain(
                    domain = domain,
                    normalizedDomain = domain,
                    category = category,
                    confidence = confidence,
                    status = status,
                    riskLevel = riskFor(category),
                    sourceIds = acc.sourceIds.toList().sorted(),
                    firstSeenEpochMs = nowEpochMs,
                    lastVerifiedEpochMs = nowEpochMs,
                    databaseVersion = databaseVersion,
                    appliesToSubdomains = acc.wildcard,
                ),
            )
        }

        result.sortWith(compareBy({ it.status }, { it.normalizedDomain }))
        return result
    }

    fun toReleaseRecords(ingested: List<IngestedDomain>): List<ReleaseDomainRecord> =
        ingested.map {
            ReleaseDomainRecord(
                domain = it.domain,
                normalizedDomain = it.normalizedDomain,
                category = it.category,
                confidence = it.confidence,
                status = it.status,
                riskLevel = it.riskLevel,
                sourceIds = it.sourceIds,
                operatorId = it.operatorId,
                brandId = it.brandId,
                mirrorOf = it.mirrorOf,
                country = it.country,
                firstSeenEpochMs = it.firstSeenEpochMs,
                lastVerifiedEpochMs = it.lastVerifiedEpochMs,
                databaseVersion = it.databaseVersion,
                appliesToSubdomains = it.appliesToSubdomains,
            )
        }

    private fun computeConfidence(sourceCount: Int, defs: List<SourceDefinition>): String {
        val maxScore = defs.maxOfOrNull { it.reputation } ?: 0.0
        return when {
            sourceCount >= 2 && maxScore >= 0.6 -> "HIGH"
            sourceCount >= 2 -> "MEDIUM"
            maxScore >= 0.85 -> "MEDIUM"
            else -> "LOW"
        }
    }

    private fun selectStatus(confidence: String, defs: List<SourceDefinition>): String {
        val maxAllowed = defs.maxOfOrNull { it.maxConfidence } ?: "LOW"
        return when {
            confidence == "HIGH" && maxAllowed == "HIGH" && defs.any { it.reputation >= 0.8 } -> "ACTIVE"
            else -> "CANDIDATE"
        }
    }

    private fun riskFor(category: String): String = when (category) {
        "CRYPTO_GAMBLING", "SKIN_GAMBLING", "PREDICTION_MARKETS" -> "HIGH"
        else -> "MEDIUM"
    }

    /**
     * Keyword classifier over the DDNS / SLD / registrable labels. The first matched
     * category wins; categories are ordered most-specific first so `crypto-casino` is not
     * mislabelled as plain `CASINO`.
     */
    fun classifyForcedBlocklist(domain: String): Pair<String?, String?> {
        val labels = domain.split('.')
        val hay = buildString {
            append(labels.takeLast(2).joinToString("."))
            append(' ')
            append(domain)
        }.lowercase()
        for ((category, keywords) in CATEGORY_KEYWORDS) {
            for (keyword in keywords) {
                if (keyword in hay) return category to keyword
            }
        }
        return null to null
    }

    private val CATEGORY_KEYWORDS: List<Pair<String, List<String>>> = listOf(
        "CRYPTO_GAMBLING" to listOf(
            "cryptocasino", "cryptobet", "cryptodice", "cryptogambling", "bitcasino",
            "ethcasino", "bitcoinbetting", "crashcasino", "crash-gambling", "crypto-gamble",
            "csgocasino",
        ),
        "SKIN_GAMBLING" to listOf(
            "csgobet", "cs-gambling", "skincasino", "csgogamble", "skin-betting",
            "csgobetting",
        ),
        "SPORTSBOOK" to listOf(
            "sportsbook", "sports-betting", "sportbetting", "betting-exchange",
            "football-betting", "bookmaker",
        ),
        "POKER" to listOf("pokerroom", "poker-casino", "texasholdem", "pokergame"),
        "LOTTERY" to listOf("onlinelotto", "lottery-online", "lotteries", "lottosg"),
        "BINGO" to listOf("bingosite", "onlinebingo", "bingo-hall"),
        "CASINO" to listOf(
            "casino", "slotszone", "online-slots", "vegas-slots", "jackpot",
            "slot-machine", "vivarosso",
        ),
        "AFFILIATE" to listOf("affiliation-casino", "casinoaffiliate"),
    )
}