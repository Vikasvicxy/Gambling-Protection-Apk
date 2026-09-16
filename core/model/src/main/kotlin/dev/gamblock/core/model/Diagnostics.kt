package dev.gamblock.core.model

import kotlinx.serialization.Serializable

@Serializable
data class DiagnosticsEntry(
    val label: String,
    val passed: Boolean?,
    val detail: String,
    val measuredAtEpochMs: Long,
)

@Serializable
data class DiagnosticsReport(
    val appVersion: String,
    val androidSdk: Int,
    val recordedAtEpochMs: Long,
    val entries: List<DiagnosticsEntry>,
) {
    /** Export payload - deliberately excludes browsing history, passwords and personal data. */
    fun toPlainText(): String = buildString {
        appendLine("Shield diagnostics ${appVersion} (SDK ${androidSdk}) recorded ${recordedAtEpochMs}")
        entries.forEach { e ->
            appendLine("• ${e.label}: ${e.detail} (${e.passed?.let { if (it) "PASS" else "FAIL" } ?: "N/A"})")
        }
    }
}