package dev.gamblock.shield

import dev.gamblock.core.model.BlockDecision
import dev.gamblock.core.model.Category
import dev.gamblock.core.model.Confidence
import dev.gamblock.core.model.DecisionKind
import dev.gamblock.core.model.RuleHit
import dev.gamblock.core.model.util.stableHash
import dev.gamblock.data.blocklist.BlocklistRepository
import dev.gamblock.data.preferences.ReviewerModePolicy
import dev.gamblock.data.preferences.ReviewerModeRepository
import dev.gamblock.protection.domainengine.DomainBlocker
import dev.gamblock.protection.domainengine.DomainNormalizer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReviewerModeDomainBlocker @Inject constructor(
    private val delegate: BlocklistRepository,
    private val reviewerMode: ReviewerModeRepository,
) : DomainBlocker {
    override val ruleCount: Int
        get() = delegate.ruleCount

    override val isReady: Boolean
        get() = delegate.isReady

    override fun decide(host: String, scheduleActive: Boolean): BlockDecision {
        val normalized = DomainNormalizer.normalize(host)
        if (BuildConfig.REVIEWER_MODE_ENABLED &&
            reviewerMode.enabled.value &&
            normalized != null &&
            ReviewerModePolicy.isBlockedHost(normalized)
        ) {
            return BlockDecision(
                decision = DecisionKind.BLOCK,
                ruleHit = RuleHit(
                    normalizedDomain = normalized,
                    category = Category.GAMBLING,
                    confidence = Confidence.HIGH,
                ),
                signature = stableHash("reviewer-demo:$normalized").toString(),
                reason = "reviewer demo scenario",
            )
        }
        return delegate.decide(host, scheduleActive)
    }
}
