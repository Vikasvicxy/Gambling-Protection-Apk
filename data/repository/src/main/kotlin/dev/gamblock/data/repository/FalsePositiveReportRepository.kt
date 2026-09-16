package dev.gamblock.data.repository

import dev.gamblock.core.common.clock.WallClock
import dev.gamblock.core.common.dispatcher.DispatchersProvider
import dev.gamblock.core.common.logging.Logs
import dev.gamblock.core.common.logging.ShieldLogger
import dev.gamblock.core.database.ShieldDatabase
import dev.gamblock.core.database.entity.FalsePositiveReportEntity
import dev.gamblock.core.database.entity.toModel
import dev.gamblock.core.model.Category
import dev.gamblock.core.model.FalsePositiveReport
import dev.gamblock.core.model.ReportStatus
import dev.gamblock.protection.domainengine.DomainNormalizer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
class FalsePositiveReportRepository @Inject constructor(
    private val db: ShieldDatabase,
    private val wallClock: WallClock,
    private val dispatchers: DispatchersProvider,
    private val logger: ShieldLogger,
) {
    private val dao get() = db.falsePositiveReportDao()

    fun observeRecent(limit: Int = 50): Flow<List<FalsePositiveReport>> =
        dao.observeRecent(limit).map { list -> list.map { it.toModel() } }

    suspend fun submit(
        domain: String,
        category: Category,
        blocklistVersion: Int,
        appVersion: String,
        installId: String,
        note: String?,
    ): Long = withContext(dispatchers.io) {
        val normalized = DomainNormalizer.normalize(domain) ?: domain.lowercase()
        val entity = FalsePositiveReportEntity(
            normalizedDomain = normalized,
            category = category.name,
            blocklistVersion = blocklistVersion,
            appVersion = appVersion,
            note = note,
            anonymousInstallId = installId,
            createdAtEpochMs = wallClock.nowEpochMillis(),
            status = ReportStatus.QUEUED,
        )
        val id = dao.insert(entity)
        logger.i(Logs.UI, "false-positive report #$id queued for $normalized")
        id
    }

    suspend fun updateStatus(id: Long, status: ReportStatus) = withContext(dispatchers.io) {
        dao.setStatus(id, status)
    }
}