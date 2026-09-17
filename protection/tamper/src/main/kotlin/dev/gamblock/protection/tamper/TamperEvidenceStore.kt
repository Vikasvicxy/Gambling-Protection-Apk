package dev.gamblock.protection.tamper

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.gamblock.core.common.logging.Logs
import dev.gamblock.core.common.logging.ShieldLogger
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Persistence seam for the tamper-evidence log. */
interface TamperEvidenceStore {
    suspend fun load(): List<TamperEvidence>
    suspend fun save(records: List<TamperEvidence>)
}

private val Context.tamperEvidenceDataStore by preferencesDataStore(name = "tamper_evidence")

/**
 * Production store: the full chain lives in one preferences file. Records are
 * small (a handful of small maps); the whole list is rewritten on each append.
 * On read corruption the log is treated as empty and flagged via [TamperCategory.EVIDENCE_CHAIN_INVALID]
 * by the caller - never silently trusted.
 */
class DataStoreTamperEvidenceStore(
    private val context: Context,
    private val logger: ShieldLogger,
) : TamperEvidenceStore {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val serializer = ListSerializer(TamperEvidence.serializer())

    override suspend fun load(): List<TamperEvidence> {
        val raw = context.tamperEvidenceDataStore.data.first()[KEY]
        if (raw == null) return emptyList()
        return try {
            json.decodeFromString(serializer, raw)
        } catch (t: Exception) {
            logger.w(Logs.SECURITY, "Tamper evidence log unreadable; starting fresh", t)
            emptyList()
        }
    }

    override suspend fun save(records: List<TamperEvidence>) {
        val raw = try {
            json.encodeToString(serializer, records)
        } catch (t: Exception) {
            logger.e(Logs.SECURITY, "Failed to encode tamper evidence; log not persisted", t)
            return
        }
        context.tamperEvidenceDataStore.edit { it[KEY] = raw }
    }

    private companion object {
        val KEY = stringPreferencesKey("evidence_chain")
    }
}

/** In-memory store for tests and process-local use. */
class InMemoryTamperEvidenceStore : TamperEvidenceStore {
    private var records: List<TamperEvidence> = emptyList()

    override suspend fun load(): List<TamperEvidence> = records

    override suspend fun save(records: List<TamperEvidence>) {
        this.records = records.toList()
    }
}