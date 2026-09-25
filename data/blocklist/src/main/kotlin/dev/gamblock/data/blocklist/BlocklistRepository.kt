package dev.gamblock.data.blocklist

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gamblock.core.common.clock.WallClock
import dev.gamblock.core.common.dispatcher.DispatchersProvider
import dev.gamblock.core.common.logging.ShieldLogger
import dev.gamblock.core.database.ShieldDatabase
import dev.gamblock.core.database.entity.MetaEntity
import dev.gamblock.core.database.entity.toEntity
import dev.gamblock.core.database.entity.toModel
import dev.gamblock.core.model.BlockDecision
import dev.gamblock.core.model.BlocklistStats
import dev.gamblock.core.model.DecisionKind
import dev.gamblock.core.model.UpdateState
import dev.gamblock.core.model.util.stableHash
import dev.gamblock.protection.domainengine.CompiledIndex
import dev.gamblock.protection.domainengine.CustomDomainExceptionMatcher
import dev.gamblock.protection.domainengine.DecisionEngine
import dev.gamblock.protection.domainengine.DomainBlocker
import dev.gamblock.protection.domainengine.DomainIndexCompiler
import dev.gamblock.protection.domainengine.DomainNormalizer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/** Immutable snapshot of the in-memory block engine exposed to the UI. */
data class BlocklistSnapshot(
    val compiled: CompiledIndex,
    val stats: BlocklistStats,
    val sourceVersion: Int,
)

private data class PublishedIndex(
    val engine: DecisionEngine,
    val compiled: CompiledIndex,
)

/**
 * Canonical blocklist owner. Room is the source of truth; the hot path is served
 * from a compiled index in memory. Block queries never touch SQLite.
 */
@Singleton
class BlocklistRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: ShieldDatabase,
    private val seedBlocklistLoader: SeedBlocklistLoader,
    private val dispatchers: DispatchersProvider,
    private val wallClock: WallClock,
    private val logger: ShieldLogger,
) : DomainBlocker {

    private val domainDao = db.domainDao()
    private val metaDao = db.metaDao()

    private val _state = MutableStateFlow<BlocklistSnapshot?>(null)
    val state: StateFlow<BlocklistSnapshot?> = _state.asStateFlow()

    private val publishedIndex = AtomicReference<PublishedIndex?>(null)

    /** In-memory overlay (normalized domain -> covered) for custom user exceptions. */
    @Volatile
    private var exceptionOverlay: Set<String> = emptySet()

    /** Swaps the exception overlay; the hot path never touches SQLite. */
    fun setCustomExceptions(exceptions: Set<String>) {
        exceptionOverlay = exceptions
    }

    override val ruleCount: Int
        get() = _state.value?.compiled?.enabledCount ?: 0

    override val isReady: Boolean
        get() = publishedIndex.get() != null

    /** Loads the seed once, then compiles the working index. Idempotent. */
    suspend fun initialize() {
        withContext(dispatchers.io) {
            if (metaDao.get(KEY_VERSION) == null) {
                seedFromAssets()
            } else {
                logger.i(TAG, "blocklist v${metaDao.get(KEY_VERSION)} already seeded")
            }
            rebuildIndex()
        }
    }

    /** Re-reads the rule table and swaps the compiled index. Call after mutations. */
    suspend fun rebuildIndex() {
        withContext(dispatchers.io) {
            val rows = domainDao.enabledRules()
            val records = rows.map { it.toModel() }
            val compiled = DomainIndexCompiler.compile(records, sourceVersion = metaDao.get(KEY_VERSION)?.toInt() ?: 1)
            publishCompiledIndex(compiled)
            logger.i(
                TAG,
                "index rebuilt: ${compiled.enabledCount} blocked, ${compiled.allowlistCount} allowlisted, digest ${compiled.digest.take(12)}",
            )
        }
    }

    /** Publishes a fully compiled index without exposing a partially built engine. */
    fun publishCompiledIndex(compiled: CompiledIndex) {
        publishedIndex.set(PublishedIndex(DecisionEngine(compiled.index), compiled))
        _state.value = BlocklistSnapshot(
            compiled = compiled,
            stats = BlocklistStats(
                databaseVersion = compiled.sourceVersion,
                enabledRuleCount = compiled.enabledCount,
                allowlistedRuleCount = compiled.allowlistCount,
                lastLoadedEpochMs = wallClock.nowEpochMillis(),
                integrityDigest = compiled.digest,
                updateState = UpdateState.IDLE,
            ),
            sourceVersion = compiled.sourceVersion,
        )
    }

    /** Adds an allowlist rule; rebuilds the index. */
    suspend fun addAllowlist(normalizedDomain: String) {
        withContext(dispatchers.io) {
            val existing = domainDao.findByNormalized(normalizedDomain)
            if (existing != null) {
                domainDao.upsert(
                    existing.copy(
                        status = dev.gamblock.core.model.BlockStatus.ALLOWLISTED,
                        operatorId = dev.gamblock.core.model.Operator.USER_ALLOWLIST,
                        source = "user",
                    ),
                )
            } else {
                domainDao.upsert(
                    dev.gamblock.core.model.DomainRecord(
                        domain = normalizedDomain,
                        normalizedDomain = normalizedDomain,
                        status = dev.gamblock.core.model.BlockStatus.ALLOWLISTED,
                        confidence = dev.gamblock.core.model.Confidence.UNKNOWN,
                        riskLevel = dev.gamblock.core.model.RiskLevel.LOW,
                        operatorId = dev.gamblock.core.model.Operator.USER_ALLOWLIST,
                        source = "user",
                    ).toEntity(),
                )
            }
            rebuildIndex()
        }
    }

    override fun decide(host: String, scheduleActive: Boolean): BlockDecision {
        val active = publishedIndex.get()
        if (active == null) {
            return BlockDecision(
                decision = dev.gamblock.core.model.DecisionKind.ALLOW,
                ruleHit = null,
                signature = stableHash("no-index:$host").toString(),
                reason = "blocklist index not ready: fail-open",
            )
        }
        val decision = active.engine.decide(host, scheduleActive)
        if (decision.decision == dev.gamblock.core.model.DecisionKind.BLOCK) {
            val exceptions = exceptionOverlay
            if (exceptions.isNotEmpty()) {
                val normalized = DomainNormalizer.normalize(host)
                if (normalized != null && CustomDomainExceptionMatcher.matches(normalized, exceptions)) {
                    return BlockDecision(
                        decision = dev.gamblock.core.model.DecisionKind.ALLOW,
                        ruleHit = null,
                        signature = stableHash("exception:$normalized").toString(),
                        reason = "custom exception override",
                        bypassViaException = true,
                    )
                }
            }
        }
        return decision
    }

    private suspend fun seedFromAssets() {
        val records = seedBlocklistLoader.loadAsset()
        val entities = records.map { it.toEntity() }
        domainDao.upsertAll(entities)
        val version = records.firstOrNull()?.databaseVersion ?: 1
        metaDao.put(MetaEntity(KEY_VERSION, version.toString()))
        metaDao.put(MetaEntity(KEY_DIGEST, DomainIndexCompiler.integrityDigest(records)))
        logger.i(TAG, "seeded blocklist: ${entities.size} rules (v$version)")
    }

    companion object {
        private const val TAG = "BlocklistRepository"
        const val KEY_VERSION = "blocklist_version"
        const val KEY_DIGEST = "blocklist_digest"
    }
}