package dev.gamblock.data.blocklist

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gamblock.core.common.dispatcher.DispatchersProvider
import dev.gamblock.core.common.logging.ShieldLogger
import dev.gamblock.core.model.DomainRecord
import dev.gamblock.core.model.Operator
import dev.gamblock.protection.domainengine.DomainNormalizer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

@Singleton
class SeedBlocklistLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatchersProvider,
    private val logger: ShieldLogger,
) {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    suspend fun loadAsset(assetName: String = ASSET_NAME): List<DomainRecord> = withContext(dispatchers.io) {
        val raw = context.assets.open(assetName).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val file = json.decodeFromString<SeedBlocklistFile>(raw)
        file.entries.mapNotNull { entry ->
            val normalized = DomainNormalizer.normalize(entry.domain)
            if (normalized == null) {
                logger.w(TAG, "seed entry skipped (unparseable): ${entry.domain}")
                null
            } else {
                DomainRecord(
                    domain = entry.domain,
                    normalizedDomain = normalized,
                    category = entry.category,
                    status = entry.status,
                    confidence = entry.confidence,
                    riskLevel = entry.riskLevel,
                    source = entry.source,
                    operatorId = Operator.GAMBLOCK_SEED,
                    databaseVersion = file.version,
                    firstSeenEpochMs = if (file.releasedAtEpochMs > 0) file.releasedAtEpochMs else 0L,
                    appliesToSubdomains = entry.appliesToSubdomains,
                )
            }
        }.also {
            logger.i(TAG, "loaded ${it.size} seed rules (asset $assetName v${file.version})")
        }
    }

    companion object {
        const val ASSET_NAME = "seed_blocklist_v1.json"
        private const val TAG = "SeedBlocklistLoader"
    }
}