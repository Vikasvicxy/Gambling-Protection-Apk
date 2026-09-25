package dev.gamblock.core.model

import kotlinx.serialization.Serializable

/** High-risk content categories. Phase 1 ships a small seed set but the full taxonomy exists. */
@Serializable
enum class Category(val displayName: String) {
    GAMBLING("Gambling"),
    CASINO("Online casino"),
    SPORTSBOOK("Sports betting"),
    POKER("Poker"),
    LOTTERY("Lottery"),
    CRYPTO_GAMBLING("Crypto gambling"),
    AFFILIATE("Gambling affiliate"),
    UNKNOWN("Unknown");

    companion object {
        private val byName = entries.associateBy { it.name }

        fun fromStorage(name: String?): Category {
            if (name == null) return UNKNOWN
            val upper = name.uppercase()
            if (byName.containsKey(upper)) return byName.getValue(upper)
            if (upper.startsWith("AFFILIATE")) return AFFILIATE
            return when (upper) {
                "GAMBLING", "GAMBLE", "CASINO", "BETTING", "BET" -> GAMBLING
                "SPORT", "SPORTSBOOK", "SPORTS_BETTING" -> SPORTSBOOK
                else -> UNKNOWN
            }
        }
    }
}

enum class BlockStatus {
    ACTIVE,
    ALLOWLISTED,
    CANDIDATE,
    FALSE_POSITIVE,
    DISABLED,
}

enum class Confidence {
    HIGH,
    MEDIUM,
    LOW,
    UNKNOWN,
}

enum class RiskLevel {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW,
    UNKNOWN,
}

/**
 * A remote administrative operator for a rule. Phase 1 local rules use [Source.SEED].
 * Future pipeline: source normalisation → dedupe → classify → version → sign → release.
 */
enum class Operator {
    GAMBLOCK_SEED,
    FUTURE_EXTERNAL,
    USER_ALLOWLIST,
    CORE_ALLOWLIST,
}

@Serializable
data class DomainRecord(
    val domain: String,
    val normalizedDomain: String,
    val category: Category = Category.GAMBLING,
    val status: BlockStatus = BlockStatus.ACTIVE,
    val confidence: Confidence = Confidence.HIGH,
    val riskLevel: RiskLevel = RiskLevel.MEDIUM,
    val firstSeenEpochMs: Long = 0L,
    val lastVerifiedEpochMs: Long = 0L,
    val source: String = "seed-v1",
    val operatorId: Operator = Operator.GAMBLOCK_SEED,
    val databaseVersion: Int = 1,
    /** When [true], the rule matches the domain and all of its subdomains. */
    val appliesToSubdomains: Boolean = true,
)

/** A matched rule produced by the in-memory domain index. */
@Serializable
data class RuleHit(
    val normalizedDomain: String,
    val category: Category,
    val confidence: Confidence = Confidence.HIGH,
    val matchedAs: MatchKind = MatchKind.EXACT,
) {
    enum class MatchKind {
        EXACT,
        SUBDOMAIN,
    }
}

/** Result of evaluating a domain against the block engine. */
enum class DecisionKind {
    BLOCK,
    ALLOW,
}

@Serializable
data class BlockDecision(
    val decision: DecisionKind,
    val ruleHit: RuleHit? = null,
    /** Stable hash used to collapse repeated events. */
    val signature: String,
    /** Reason string for debugging / diagnostics. */
    val reason: String = "",
    /** True when a custom user exception overrode an otherwise blocked verdict. */
    val bypassViaException: Boolean = false,
)

/** A user-created domain bypass (temporary or permanent). */
@Serializable
data class CustomDomainException(
    val id: Long,
    val normalizedDomain: String,
    val createdAtEpochMs: Long,
    /** Null = permanent; otherwise the exception self-removes after this instant. */
    val expiresAtEpochMs: Long? = null,
    val note: String = "",
)

@Serializable
data class DomainQuery(
    val rawHost: String,
    val normalizedDomain: String,
    val epochMs: Long,
)

/** Statistics about the loaded blocklist. */
@Serializable
data class BlocklistStats(
    val databaseVersion: Int,
    val enabledRuleCount: Int,
    val allowlistedRuleCount: Int,
    val lastLoadedEpochMs: Long,
    val lastUpdateAttemptEpochMs: Long = 0L,
    val integrityDigest: String,
    val updateState: UpdateState = UpdateState.IDLE,
)

enum class UpdateState {
    IDLE,
    CHECKING,
    UP_TO_DATE,
    UPDATE_AVAILABLE,
    FAILED,
    NOT_CONFIGURED,
}