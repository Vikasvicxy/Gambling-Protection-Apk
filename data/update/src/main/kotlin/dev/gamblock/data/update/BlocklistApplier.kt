package dev.gamblock.data.update

import androidx.room.withTransaction
import dev.gamblock.core.common.logging.Logs
import dev.gamblock.core.common.logging.ShieldLogger
import dev.gamblock.core.database.ShieldDatabase
import dev.gamblock.core.database.dao.MetaDao
import dev.gamblock.core.database.entity.DomainEntity
import dev.gamblock.core.database.entity.MetaEntity
import dev.gamblock.core.database.entity.toModel
import dev.gamblock.core.model.BlockStatus
import dev.gamblock.core.model.Category
import dev.gamblock.core.model.Confidence
import dev.gamblock.core.model.Operator
import dev.gamblock.core.model.RiskLevel
import dev.gamblock.core.model.UpdateMeta
import dev.gamblock.core.release.ReleaseDomainRecord
import dev.gamblock.data.blocklist.BlocklistRepository
import dev.gamblock.protection.domainengine.DomainIndexCompiler
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome + verified facts about a successfully applied release. */
data class AppliedRelease(
    val version: Int,
    val releaseId: String,
    val signingKeyId: String,
    val viaDelta: Boolean,
    val appliedEpochMs: Long,
)

/**
 * The ONLY writer that touches the canonical rule table for updates. Everything is
 * applied inside one Room transaction after every integrity check has already passed:
 * the signature, the artifact hash and the payload semantics were verified BEFORE this
 * class is ever called, so a failed/corrupt download can never clobber the
 * last-known-good database.
 *
 * Previous-good is tracked as durable version bookkeeping (the version/release/digest
 * that was live before the current one). Emergency rollback is delivered as a NEWLY
 * SIGNED payload by the pipeline, never fabricated on-device.
 */
@Singleton
class BlocklistApplier @Inject constructor(
    private val db: ShieldDatabase,
    private val blocklistRepository: BlocklistRepository,
    private val logger: ShieldLogger,
) {

    private val domainDao = db.domainDao()
    private val metaDao: MetaDao = db.metaDao()

    /** Applies a verified full record set and swaps the compiled index. */
    suspend fun apply(
        records: List<ReleaseDomainRecord>,
        version: Int,
        releaseId: String,
        signingKeyId: String,
        viaDelta: Boolean,
        rollback: Boolean = false,
    ) {
        // A signed-but-empty release must not silently wipe protection. The only
        // intended way to ship a reduced set is an explicitly-flagged signed rollback.
        if (records.isEmpty() && !rollback) {
            throw IllegalArgumentException(
                "refusing to apply an empty blocklist (release $releaseId v$version has no rules; requires an explicit signed rollback)",
            )
        }
        val entities = records.map { it.toDomainEntity() }
        val models = entities.map { it.toModel() }
        val digest = DomainIndexCompiler.integrityDigest(models)
        val now = System.currentTimeMillis()

        db.withTransaction {
            domainDao.deleteAll()
            domainDao.upsertAll(entities)

            // Move the currently-live release into previous-good history.
            val prevVersion = metaDao.get(BlocklistRepository.KEY_VERSION)?.toIntOrNull()
            val prevReleaseId = metaDao.get(UpdateMeta.KEY_ACTIVE_RELEASE_ID)?.takeIf { it.isNotBlank() }
            if (prevVersion != null && prevVersion > 0) {
                metaDao.put(MetaEntity(UpdateMeta.KEY_PREVIOUS_GOOD_VERSION, prevVersion.toString()))
                metaDao.put(MetaEntity(UpdateMeta.KEY_PREVIOUS_GOOD_RELEASE_ID, prevReleaseId.orEmpty()))
                metaDao.put(
                    MetaEntity(
                        UpdateMeta.KEY_PREVIOUS_GOOD_DIGEST,
                        metaDao.get(BlocklistRepository.KEY_DIGEST)?.orEmpty().orEmpty(),
                    ),
                )
                metaDao.put(MetaEntity(UpdateMeta.KEY_PREVIOUS_GOOD_APPLIED_EPOCH_MS, now.toString()))
            }

            metaDao.put(MetaEntity(BlocklistRepository.KEY_VERSION, version.toString()))
            metaDao.put(MetaEntity(BlocklistRepository.KEY_DIGEST, digest))
            metaDao.put(MetaEntity(UpdateMeta.KEY_ACTIVE_RELEASE_ID, releaseId))
            metaDao.put(MetaEntity(UpdateMeta.KEY_ACTIVE_SIGNING_KEY_ID, signingKeyId))
            metaDao.put(MetaEntity(UpdateMeta.KEY_ROLLBACK_EVER_APPLIED, rollback.toString()))
        }

        blocklistRepository.rebuildIndex()
        logger.i(Logs.DB, "applied blocklist v$version ($releaseId, ${entities.size} rows, delta=$viaDelta)")
    }

    /** All rows currently in the rule table (base for a delta application). */
    suspend fun currentRecords(): List<DomainEntity> = domainDao.findAll()

    companion object {
        fun mapStatus(status: String): BlockStatus = when (status.uppercase()) {
            "VERIFIED", "ACTIVE" -> BlockStatus.ACTIVE
            else -> runCatching { BlockStatus.valueOf(status.uppercase()) }.getOrDefault(BlockStatus.CANDIDATE)
        }
    }
}

private fun ReleaseDomainRecord.toDomainEntity(): DomainEntity = DomainEntity(
    domain = domain,
    normalizedDomain = normalizedDomain,
    category = Category.fromStorage(category),
    status = BlocklistApplier.mapStatus(status),
    confidence = runCatching { Confidence.valueOf(confidence) }.getOrDefault(Confidence.UNKNOWN),
    riskLevel = runCatching { RiskLevel.valueOf(riskLevel) }.getOrDefault(RiskLevel.MEDIUM),
    firstSeenEpochMs = firstSeenEpochMs,
    lastVerifiedEpochMs = lastVerifiedEpochMs,
    source = sourceIds.joinToString(","),
    operatorId = runCatching { Operator.valueOf(operatorId.orEmpty()) }.getOrDefault(Operator.GAMBLOCK_SEED),
    databaseVersion = databaseVersion,
    appliesToSubdomains = appliesToSubdomains,
)

/** Converts an installed row back to a release record (delta base). */
fun DomainEntity.toReleaseRecord(): ReleaseDomainRecord = ReleaseDomainRecord(
    domain = domain,
    normalizedDomain = normalizedDomain,
    category = category.name,
    confidence = confidence.name,
    status = status.name,
    riskLevel = riskLevel.name,
    sourceIds = if (source.isBlank()) emptyList() else listOf(source),
    operatorId = operatorId.name,
    firstSeenEpochMs = firstSeenEpochMs,
    lastVerifiedEpochMs = lastVerifiedEpochMs,
    databaseVersion = databaseVersion,
    appliesToSubdomains = appliesToSubdomains,
)