package dev.gamblock.core.model

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.Serializable

@Serializable
enum class CravingTrigger(val displayName: String) {
    BOREDOM("Boredom"),
    STRESS("Stress"),
    CHASING_LOSSES("Chasing Losses"),
    SPORTS_AD("Sports Ad"),
    ALCOHOL("Alcohol"),
    SOCIAL_MEDIA("Social Media"),
    ;

    companion object {
        fun fromStorageOrDefault(value: String?): CravingTrigger =
            entries.firstOrNull { it.name == value } ?: BOREDOM
    }
}

@Serializable
data class CravingEntry(
    val id: Long = 0L,
    val occurredAtEpochMs: Long,
    val intensity: Int = DEFAULT_INTENSITY,
    val triggers: Set<CravingTrigger> = emptySet(),
    val note: String = "",
    val blockedDomain: String? = null,
) {
    init {
        require(intensity in MIN_INTENSITY..MAX_INTENSITY) { "intensity out of range" }
    }

    companion object {
        const val MIN_INTENSITY: Int = 1
        const val MAX_INTENSITY: Int = 5
        const val DEFAULT_INTENSITY: Int = 3
        const val TRIGGER_SEPARATOR: String = ","

        fun sanitizeIntensity(value: Int): Int = value.coerceIn(MIN_INTENSITY, MAX_INTENSITY)

        fun joinTriggers(triggers: Set<CravingTrigger>): String =
            triggers.map { it.name }.sorted().joinToString(TRIGGER_SEPARATOR)

        fun parseTriggerList(stored: String): Set<CravingTrigger> =
            stored.split(TRIGGER_SEPARATOR)
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { name -> CravingTrigger.entries.firstOrNull { it.name == name } }
                .toSet()
    }
}

@Serializable
data class CravingInsight(
    val totalEntries: Int = 0,
    val averageIntensity: Double = 0.0,
    val peakWindowLabel: String? = null,
    val peakTrigger: CravingTrigger? = null,
    val triggerCounts: Map<CravingTrigger, Int> = emptyMap(),
) {
    val hasData: Boolean
        get() = totalEntries > 0
}

object CravingInsights {

    const val WINDOW_HOURS: Int = 4

    fun analyze(
        entries: List<CravingEntry>,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): CravingInsight {
        if (entries.isEmpty()) return CravingInsight()
        val totalIntensity = entries.sumOf { it.intensity }
        val triggerCounts = LinkedHashMap<CravingTrigger, Int>()
        entries.forEach { entry ->
            entry.triggers.forEach { trigger ->
                triggerCounts[trigger] = (triggerCounts[trigger] ?: 0) + 1
            }
        }
        val peak = peakWindow(entries, zoneId)
        return CravingInsight(
            totalEntries = entries.size,
            averageIntensity = totalIntensity.toDouble() / entries.size,
            peakWindowLabel = peak?.label,
            peakTrigger = triggerCounts.maxByOrNull { it.value }?.key,
            triggerCounts = triggerCounts,
        )
    }

    fun peakWindow(
        entries: List<CravingEntry>,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): CravingPeakWindow? {
        if (entries.isEmpty()) return null
        val buckets = HashMap<PeakBucket, Int>()
        entries.forEach { entry ->
            val dateTime = Instant.ofEpochMilli(entry.occurredAtEpochMs).atZone(zoneId)
            val day = WeekDay.valueOf(dateTime.dayOfWeek.name)
            val bucketStart = (dateTime.hour / WINDOW_HOURS) * WINDOW_HOURS
            val key = PeakBucket(day, bucketStart)
            buckets[key] = (buckets[key] ?: 0) + 1
        }
        val best = buckets.maxByOrNull { it.value } ?: return null
        val end = ((best.key.bucketStartHour + WINDOW_HOURS) % 24)
        val endLabel = formatHour(end)
        return CravingPeakWindow(
            day = best.key.day,
            startHour = best.key.bucketStartHour,
            count = best.value,
            label = "${best.key.day.displayName()} ${formatHour(best.key.bucketStartHour)} - $endLabel",
        )
    }

    private fun formatHour(hour: Int): String = when {
        hour == 0 -> "12 AM"
        hour == 12 -> "12 PM"
        hour < 12 -> "$hour AM"
        else -> "${hour - 12} PM"
    }
}

data class CravingPeakWindow(
    val day: WeekDay,
    val startHour: Int,
    val count: Int,
    val label: String,
)

private data class PeakBucket(val day: WeekDay, val bucketStartHour: Int)

fun WeekDay.displayName(): String = when (this) {
    WeekDay.MONDAY -> "Monday"
    WeekDay.TUESDAY -> "Tuesday"
    WeekDay.WEDNESDAY -> "Wednesday"
    WeekDay.THURSDAY -> "Thursday"
    WeekDay.FRIDAY -> "Friday"
    WeekDay.SATURDAY -> "Saturday"
    WeekDay.SUNDAY -> "Sunday"
}
