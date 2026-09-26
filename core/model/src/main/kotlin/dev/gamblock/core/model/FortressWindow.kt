package dev.gamblock.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.Serializable

@Serializable
data class FortressWindow(
    val id: String,
    val label: String = "",
    val enabled: Boolean = true,
    val startMinuteOfDay: Int = 20 * 60,
    val endMinuteOfDay: Int = 5 * 60,
    val activeDays: Set<WeekDay> = WeekDay.entries.toSet(),
    /**
     * How many days after an active start day the window finishes. An overnight
     * window is `1`. A continuous weekend lock starts Friday and finishes on
     * Monday, which is `3`. Making this explicit avoids having to infer the end
     * from the next active day, which silently produced a multi-day lock that
     * also covered the middle of the week.
     */
    val endDayOffset: Int = if (endMinuteOfDay < startMinuteOfDay) 1 else 0,
) {
    init {
        require(startMinuteOfDay in 0..MINUTE_LIMIT) { "startMinuteOfDay out of range" }
        require(endMinuteOfDay in 0..MINUTE_LIMIT) { "endMinuteOfDay out of range" }
        require(endDayOffset in 0..MAX_DAY_OFFSET) { "endDayOffset out of range" }
    }

    /** True when the window continues past midnight into the following day. */
    val isOvernight: Boolean
        get() = endDayOffset > 0 || endMinuteOfDay < startMinuteOfDay

    val alwaysActive: Boolean
        get() = enabled && activeDays.size == WeekDay.entries.size &&
            startMinuteOfDay == 0 && endMinuteOfDay == MINUTE_LIMIT && endDayOffset == 0

    val timeRangeLabel: String
        get() {
            val start = FortressPolicy.formatMinuteOfDay(startMinuteOfDay)
            val end = FortressPolicy.formatMinuteOfDay(endMinuteOfDay)
            return when (endDayOffset) {
                0 -> "$start - $end"
                1 -> "$start - $end (+1 day)"
                else -> "$start - $end (+$endDayOffset days)"
            }
        }

    val daysLabel: String
        get() = when {
            activeDays.isEmpty() -> "No days selected"
            activeDays.size == WeekDay.entries.size -> "Every day"
            else -> WeekDay.entries
                .filter { it in activeDays }
                .joinToString(separator = ", ") { it.displayName().take(3) }
        }

    companion object {
        const val MINUTE_LIMIT: Int = 24 * 60 - 1
        const val MAX_DAY_OFFSET: Int = 6

        /** Locks every night, for example 11 PM to 5 AM. */
        fun overnightDaily(startHour: Int, endHour: Int): FortressWindow = FortressWindow(
            id = "daily-$startHour-$endHour",
            label = "Nightly lock",
            enabled = true,
            startMinuteOfDay = startHour * 60,
            endMinuteOfDay = endHour * 60,
            activeDays = WeekDay.entries.toSet(),
            endDayOffset = 1,
        )

        /** A continuous lock from Friday evening to Monday morning. */
        fun weekendToMonday(startHour: Int = 20, endHour: Int = 6): FortressWindow = FortressWindow(
            id = "weekend-lock",
            label = "Weekend lock",
            enabled = true,
            startMinuteOfDay = startHour * 60,
            endMinuteOfDay = endHour * 60,
            activeDays = setOf(WeekDay.FRIDAY),
            endDayOffset = 3,
        )

        /**
         * Clamps values that may have come from an older or corrupted store
         * instead of throwing, so a bad value cannot break the whole flow.
         */
        fun sanitize(window: FortressWindow): FortressWindow = window.copy(
            startMinuteOfDay = window.startMinuteOfDay.coerceIn(0, MINUTE_LIMIT),
            endMinuteOfDay = window.endMinuteOfDay.coerceIn(0, MINUTE_LIMIT),
            endDayOffset = window.endDayOffset.coerceIn(0, MAX_DAY_OFFSET),
            activeDays = window.activeDays.ifEmpty { WeekDay.entries.toSet() },
        )
    }
}

data class FortressStatus(
    val lockedDown: Boolean = false,
    val activeWindow: FortressWindow? = null,
    val nextWindow: FortressWindow? = null,
    val nextWindowStartsAtEpochMs: Long? = null,
) {
    val isProtected: Boolean
        get() = lockedDown
}

object FortressPolicy {

    fun isLockedDown(
        windows: List<FortressWindow>,
        nowEpochMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Boolean = windows.firstOrNull { isWindowActive(it, nowEpochMs, zoneId) } != null

    fun status(
        windows: List<FortressWindow>,
        nowEpochMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): FortressStatus {
        val enabled = windows.filter { it.enabled }
        val active = enabled.firstOrNull { isWindowActive(it, nowEpochMs, zoneId) }
        if (active != null) {
            return FortressStatus(lockedDown = true, activeWindow = active)
        }
        val next = enabled
            .mapNotNull { window -> nextActivation(window, nowEpochMs, zoneId)?.let { window to it } }
            .minByOrNull { it.second }
        return FortressStatus(
            lockedDown = false,
            activeWindow = null,
            nextWindow = next?.first,
            nextWindowStartsAtEpochMs = next?.second,
        )
    }

    fun isWindowActive(
        window: FortressWindow,
        nowEpochMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        if (!window.enabled || window.activeDays.isEmpty()) return false
        if (window.alwaysActive) return true
        val now = Instant.ofEpochMilli(nowEpochMs).atZone(zoneId)
        val nowInstant = now.toInstant()
        // activeDays are the days the window *starts* on, so a small lookback
        // catches long windows that began on an earlier day.
        for (offset in 0..MAX_LOOKBACK_DAYS) {
            val startDate = now.toLocalDate().minusDays(offset.toLong())
            if (WeekDay.valueOf(startDate.dayOfWeek.name) !in window.activeDays) continue
            if (isWithinWindow(window, startDate, nowInstant, zoneId)) return true
        }
        return false
    }

    /**
     * A window covers the half-open instant range
     * `[startMinuteOfDay on startDate, endMinuteOfDay on startDate + endDayOffset)`.
     */
    private fun isWithinWindow(
        window: FortressWindow,
        startDate: LocalDate,
        nowInstant: Instant,
        zoneId: ZoneId,
    ): Boolean {
        val startAt = startDate
            .atStartOfDay(zoneId)
            .plusMinutes(window.startMinuteOfDay.toLong())
        val endDate = if (window.endDayOffset > 0) {
            startDate.plusDays(window.endDayOffset.toLong())
        } else {
            startDate
        }
        val endAt = endDate
            .atStartOfDay(zoneId)
            .plusMinutes(window.endMinuteOfDay.toLong())
        if (!endAt.isAfter(startAt)) return false
        return !nowInstant.isBefore(startAt.toInstant()) && nowInstant.isBefore(endAt.toInstant())
    }

    fun nextActivation(
        window: FortressWindow,
        nowEpochMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Long? {
        if (!window.enabled || window.activeDays.isEmpty()) return null
        if (window.alwaysActive) return null
        val zoneNow = Instant.ofEpochMilli(nowEpochMs).atZone(zoneId)
        var best: Long? = null
        for (offset in 0..MAX_LOOKBACK_DAYS) {
            val startDate = zoneNow.toLocalDate().plusDays(offset.toLong())
            if (WeekDay.valueOf(startDate.dayOfWeek.name) !in window.activeDays) continue
            val startAt = startDate
                .atStartOfDay(zoneId)
                .plusMinutes(window.startMinuteOfDay.toLong())
            val startMs = startAt.toInstant().toEpochMilli()
            if (startMs <= nowEpochMs) continue
            if (best == null || startMs < best) best = startMs
        }
        return best
    }

    fun nextActiveDate(
        startDate: LocalDate,
        activeDays: Set<WeekDay>,
    ): LocalDate {
        var candidate = startDate.plusDays(1)
        repeat(7) {
            if (WeekDay.valueOf(candidate.dayOfWeek.name) in activeDays) return candidate
            candidate = candidate.plusDays(1)
        }
        return startDate.plusDays(1)
    }

    fun formatMinuteOfDay(minuteOfDay: Int): String {
        val hour = (minuteOfDay / 60) % 24
        val minute = minuteOfDay % 60
        return String.format("%02d:%02d", hour, minute)
    }

    private const val MAX_LOOKBACK_DAYS: Int = 7
}
