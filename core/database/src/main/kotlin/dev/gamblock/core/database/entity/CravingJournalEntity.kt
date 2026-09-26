package dev.gamblock.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.gamblock.core.model.CravingEntry
import dev.gamblock.core.model.CravingTrigger

/**
 * A single user-logged urge event. Stored locally in Room so the journal can
 * be queried and aggregated for peak-time analytics without leaving the device.
 * Nothing here is ever uploaded; there is no sync path.
 */
@Entity(
    tableName = "craving_journal_entries",
    indices = [
        Index(value = ["occurredAtEpochMs"]),
        Index(value = ["intensity"]),
    ],
)
data class CravingJournalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val occurredAtEpochMs: Long,
    val intensity: Int,
    /** Comma-joined [CravingTrigger] names; unknown names are dropped on read. */
    val triggers: String,
    val note: String,
    val blockedDomain: String? = null,
)

fun CravingJournalEntity.toModel(): CravingEntry = CravingEntry(
    id = id,
    occurredAtEpochMs = occurredAtEpochMs,
    intensity = intensity,
    triggers = CravingEntry.parseTriggerList(triggers),
    note = note,
    blockedDomain = blockedDomain,
)
