package dev.gamblock.core.model

import kotlinx.serialization.Serializable

/** Days of the week used by [ProtectionSchedule]. */
@Serializable
enum class WeekDay {
    MONDAY,
    TUESDAY,
    WEDNESDAY,
    THURSDAY,
    FRIDAY,
    SATURDAY,
    SUNDAY,
}

/**
 * Optional protection schedule. Phase 1 stores and enforces a simple daily window:
 * outside the active window the DNS engine passes all traffic. A schedule with
 * [enabled] == false means "always protect".
 */
@Serializable
data class ProtectionSchedule(
    val enabled: Boolean = false,
    val startHour: Int = 0,
    val startMinute: Int = 0,
    val endHour: Int = 23,
    val endMinute: Int = 59,
    val activeDays: Set<WeekDay> = WeekDay.entries.toSet(),
) {
    init {
        require(startHour in 0..23) { "startHour out of range" }
        require(endHour in 0..23) { "endHour out of range" }
        require(startMinute in 0..59) { "startMinute out of range" }
        require(endMinute in 0..59) { "endMinute out of range" }
    }

    fun isSystemCurrentlyActive(
        nowEpochMs: Long,
        zoneId: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): Boolean {
        if (!enabled) return true
        val dateTime = java.time.Instant.ofEpochMilli(nowEpochMs).atZone(zoneId)
        val day = WeekDay.valueOf(dateTime.dayOfWeek.name)
        if (day !in activeDays) return false
        val minuteOfDay = dateTime.hour * 60 + dateTime.minute
        val start = startHour * 60 + startMinute
        val end = endHour * 60 + endMinute
        return if (start <= end) minuteOfDay in start..end else minuteOfDay >= start || minuteOfDay <= end
    }
}