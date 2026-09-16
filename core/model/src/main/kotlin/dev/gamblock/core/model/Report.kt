package dev.gamblock.core.model

import kotlinx.serialization.Serializable

enum class ReportStatus {
    QUEUED,
    PENDING_UPLOAD,
    UPLOADED,
    REJECTED,
}

/**
 * A false-positive / incorrect-block report. Explicitly minimal and privacy-safe.
 * No browsing history, page contents, passwords, contacts or location.
 */
@Serializable
data class FalsePositiveReport(
    val id: Long = 0L,
    val normalizedDomain: String,
    val category: Category = Category.GAMBLING,
    val blocklistVersion: Int,
    val appVersion: String,
    val note: String? = null,
    val anonymousInstallId: String,
    val createdAtEpochMs: Long,
    val status: ReportStatus = ReportStatus.QUEUED,
)

/** A local event feed entry used by the reports / blocked-content screen. */
@Serializable
data class BlockedContentEntry(
    val domain: String,
    val category: Category,
    val count: Int,
    val lastAttemptEpochMs: Long,
)