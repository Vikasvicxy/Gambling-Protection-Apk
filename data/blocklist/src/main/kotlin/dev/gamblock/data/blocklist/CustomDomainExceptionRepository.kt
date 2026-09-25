package dev.gamblock.data.blocklist

import dev.gamblock.core.common.clock.WallClock
import dev.gamblock.core.common.dispatcher.DispatchersProvider
import dev.gamblock.core.common.logging.ShieldLogger
import dev.gamblock.core.database.ShieldDatabase
import dev.gamblock.core.database.entity.CustomDomainExceptionEntity
import dev.gamblock.core.database.entity.toModel
import dev.gamblock.core.model.CustomDomainException
import dev.gamblock.protection.domainengine.DomainNormalizer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns user-created custom domain exceptions. Persists rows in Room; keeps the
 * in-memory blocklist overlay in sync so the TUN hot path stays SQLite-free.
 */
@Singleton
class CustomDomainExceptionRepository @Inject constructor(
    private val db: ShieldDatabase,
    private val blocklistRepository: BlocklistRepository,
    private val dispatchers: DispatchersProvider,
    private val wallClock: WallClock,
    private val logger: ShieldLogger,
) {

    private val dao = db.customDomainExceptionDao()
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)

    init {
        // Reflect any change (including the initial load) into the hot-path overlay.
        scope.launch {
            dao.observeAll().collect { rows ->
                blocklistRepository.setCustomExceptions(rows.mapTo(HashSet()) { it.normalizedDomain })
            }
        }
        // Occasional self-clean of expired exceptions while the app process lives.
        scope.launch {
            while (isActive) {
                delay(EXPIRY_SWEEP_MS)
                val removed: Int = dao.deleteExpired(wallClock.nowEpochMillis())
                if (removed > 0) logger.i(TAG, "swept $removed expired custom exceptions")
            }
        }
    }

    fun observeExceptions(): Flow<List<CustomDomainException>> =
        dao.observeAll().map { rows -> rows.map { it.toModel() } }

    /**
     * Adds an exception. Returns the normalized domain on success, or a message
     * on failure (invalid/empty domain).
     */
    suspend fun addException(domain: String, durationHours: Long?, note: String = ""): Result<String> =
        withContext(dispatchers.io) {
            val normalized = DomainNormalizer.normalize(domain)
            if (normalized == null) {
                return@withContext Result.failure(
                    IllegalArgumentException("Not a valid domain"),
                )
            }
            val now = wallClock.nowEpochMillis()
            dao.upsert(
                CustomDomainExceptionEntity(
                    normalizedDomain = normalized,
                    createdAtEpochMs = now,
                    expiresAtEpochMs = durationHours?.let { now + it * HOUR_MS },
                    note = note,
                ),
            )
            Result.success(normalized)
        }

    suspend fun removeException(id: Long) {
        dao.delete(id)
    }

    companion object {
        private const val TAG = "CustomDomainExceptionRepository"
        private const val EXPIRY_SWEEP_MS = 15 * 60 * 1_000L
        private const val HOUR_MS = 3_600_000L
    }
}