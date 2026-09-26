package dev.gamblock.data.repository

import android.content.Context
import android.graphics.pdf.PdfDocument
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gamblock.core.common.clock.WallClock
import dev.gamblock.core.common.logging.Logs
import dev.gamblock.core.common.logging.ShieldLogger
import dev.gamblock.core.model.RecoveryCalculator
import dev.gamblock.core.model.RecoveryMetrics
import dev.gamblock.data.preferences.RecoveryRepository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

data class SobrietyReport(
    val file: File,
    val title: String,
    val generatedAtEpochMs: Long,
)

data class ProtectionUptimeSummary(
    val connectedSessions: Long = 0L,
    val totalSessions: Long = 0L,
    val daysWithoutInterruption: Int = 0,
    val queriesBlocked: Long = 0L,
    val quicDrops: Long = 0L,
) {
    val uptimePercent: Double
        get() = if (totalSessions <= 0L) 0.0 else (connectedSessions * 100.0) / totalSessions
}

@Singleton
class SobrietyReportGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recoveryRepository: RecoveryRepository,
    private val wallClock: WallClock,
    private val logger: ShieldLogger,
) {

    suspend fun generate(
        metrics: RecoveryMetrics? = null,
        uptime: ProtectionUptimeSummary = ProtectionUptimeSummary(),
    ): SobrietyReport = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val resolvedMetrics = metrics ?: recoveryRepository.metricsSnapshot()
        val now = wallClock.nowEpochMillis()
        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
        var pageNumber = 0
        var page = document.startPage(pageInfo)
        var canvas = page.canvas

        fun newPage() {
            document.finishPage(page)
            pageNumber++
            page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber + 1).create())
            canvas = page.canvas
        }

        var cursorY = MARGIN
        fun writeLine(text: String, size: Float, gap: Float, bold: Boolean = false) {
            if (cursorY + gap > PAGE_HEIGHT - MARGIN) newPage()
            val paint = android.graphics.Paint().apply {
                this.textSize = size
                this.isAntiAlias = true
                this.color = android.graphics.Color.DKGRAY
                this.isFakeBoldText = bold
            }
            canvas.drawText(text, MARGIN, cursorY, paint)
            cursorY += gap
        }

        writeLine("Shield Recovery Report", 22f, 34f, bold = true)
        writeLine("Generated on device - no browsing history included", 11f, 20f)
        writeLine("Created: ${formatDate(now)}", 11f, 20f)
        cursorY += 10

        writeLine("Recovery progress", 15f, 24f, bold = true)
        writeLine("Days clean: ${resolvedMetrics.daysClean}", 12f, 20f)
        writeLine(
            "Estimated money saved: " +
                RecoveryCalculator.formatMoney(resolvedMetrics.moneySavedMinor, resolvedMetrics.currency),
            12f,
            20f,
        )
        if (resolvedMetrics.reachedMilestones.isNotEmpty()) {
            writeLine(
                "Milestones reached: " +
                    resolvedMetrics.reachedMilestones.joinToString(", ") { it.title },
                12f,
                20f,
            )
        }
        resolvedMetrics.nextMilestone?.let { milestone ->
            writeLine("Next milestone: ${milestone.title} (${milestone.days} days)", 12f, 20f)
        }
        cursorY += 10

        writeLine("Protection reliability", 15f, 24f, bold = true)
        writeLine(String.format("Shield uptime: %.1f%%", uptime.uptimePercent), 12f, 20f)
        writeLine("Days protected without interruption: ${uptime.daysWithoutInterruption}", 12f, 20f)
        writeLine("Gambling lookups blocked: ${uptime.queriesBlocked}", 12f, 20f)
        writeLine("Encrypted-bypass attempts stopped: ${uptime.quicDrops}", 12f, 20f)
        cursorY += 10

        writeLine("How to read this report", 15f, 24f, bold = true)
        writeLine("This summary contains only protection activity recorded locally on this", 11f, 18f)
        writeLine("device. It deliberately excludes the websites and searches you visited.", 11f, 18f)
        writeLine("Uptime reflects Shield being connected and actively filtering DNS.", 11f, 18f)
        cursorY += 10

        writeLine("Recovery sign-off", 15f, 24f, bold = true)
        writeLine("This report was generated offline by Shield and is not uploaded anywhere.", 11f, 18f)
        document.finishPage(page)

        val directory = File(context.cacheDir, REPORT_DIR).apply { mkdirs() }
        val file = File(directory, "shield-recovery-${now}.pdf")
        file.outputStream().use { stream -> document.writeTo(stream) }
        document.close()
        logger.i(Logs.SECURITY, "sobriety report written: ${file.absolutePath}")
        SobrietyReport(file = file, title = "Shield Recovery Report", generatedAtEpochMs = now)
    }

    private fun formatDate(epochMs: Long): String =
        java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm")
            .withZone(java.time.ZoneId.systemDefault())
            .format(java.time.Instant.ofEpochMilli(epochMs))

    companion object {
        private const val PAGE_WIDTH = 595
        private const val PAGE_HEIGHT = 842
        private const val MARGIN = 48f
        private const val REPORT_DIR = "reports"
    }
}
