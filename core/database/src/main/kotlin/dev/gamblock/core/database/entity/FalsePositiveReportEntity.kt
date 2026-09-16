package dev.gamblock.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.gamblock.core.model.ReportStatus

@Entity(
    tableName = "false_positive_reports",
    indices = [Index(value = ["normalizedDomain"])],
)
data class FalsePositiveReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val normalizedDomain: String,
    val category: String,
    val blocklistVersion: Int,
    val appVersion: String,
    val note: String?,
    val anonymousInstallId: String,
    val createdAtEpochMs: Long,
    val status: ReportStatus,
)

fun FalsePositiveReportEntity.toModel() = dev.gamblock.core.model.FalsePositiveReport(
    id = id,
    normalizedDomain = normalizedDomain,
    category = dev.gamblock.core.model.Category.fromStorage(category),
    blocklistVersion = blocklistVersion,
    appVersion = appVersion,
    note = note,
    anonymousInstallId = anonymousInstallId,
    createdAtEpochMs = createdAtEpochMs,
    status = status,
)

fun dev.gamblock.core.model.FalsePositiveReport.toEntity() = FalsePositiveReportEntity(
    id = id,
    normalizedDomain = normalizedDomain,
    category = category.name,
    blocklistVersion = blocklistVersion,
    appVersion = appVersion,
    note = note,
    anonymousInstallId = anonymousInstallId,
    createdAtEpochMs = createdAtEpochMs,
    status = status,
)