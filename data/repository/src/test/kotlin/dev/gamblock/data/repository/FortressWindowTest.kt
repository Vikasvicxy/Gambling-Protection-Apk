package dev.gamblock.data.repository

import com.google.common.truth.Truth.assertThat
import dev.gamblock.core.model.FortressWindow
import dev.gamblock.core.model.WeekDay
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Test

/**
 * Fortress window arithmetic. These run without Android so the recurring
 * schedule logic is verified in isolation from the DataStore plumbing.
 */
class FortressWindowTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    private fun at(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int = 0,
    ): Long = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `an overnight window covers the small hours of the next day`() {
        val window = FortressWindow.overnightDaily(startHour = 23, endHour = 5)

        val status = dev.gamblock.core.model.FortressPolicy.status(
            windows = listOf(window),
            nowEpochMs = at(2026, 3, 11, 2),
            zoneId = zone,
        )

        assertThat(status.lockedDown).isTrue()
    }

    @Test
    fun `an overnight window covers the late evening it starts`() {
        val window = FortressWindow.overnightDaily(startHour = 23, endHour = 5)

        val status = dev.gamblock.core.model.FortressPolicy.status(
            windows = listOf(window),
            nowEpochMs = at(2026, 3, 11, 23, 30),
            zoneId = zone,
        )

        assertThat(status.lockedDown).isTrue()
    }

    @Test
    fun `the middle of the day is not inside the overnight window`() {
        val window = FortressWindow.overnightDaily(startHour = 23, endHour = 5)

        val status = dev.gamblock.core.model.FortressPolicy.status(
            windows = listOf(window),
            nowEpochMs = at(2026, 3, 11, 12),
            zoneId = zone,
        )

        assertThat(status.lockedDown).isFalse()
    }

    @Test
    fun `the window start is inclusive and the end is exclusive`() {
        val window = FortressWindow.overnightDaily(startHour = 23, endHour = 5)

        val atStart = dev.gamblock.core.model.FortressPolicy.status(
            windows = listOf(window), nowEpochMs = at(2026, 3, 11, 23), zoneId = zone,
        )
        val lastMinute = dev.gamblock.core.model.FortressPolicy.status(
            windows = listOf(window), nowEpochMs = at(2026, 3, 11, 4, 59), zoneId = zone,
        )
        val atEnd = dev.gamblock.core.model.FortressPolicy.status(
            windows = listOf(window), nowEpochMs = at(2026, 3, 11, 5), zoneId = zone,
        )
        val justAfter = dev.gamblock.core.model.FortressPolicy.status(
            windows = listOf(window), nowEpochMs = at(2026, 3, 11, 6), zoneId = zone,
        )

        assertThat(atStart.lockedDown).isTrue()
        assertThat(lastMinute.lockedDown).isTrue()
        assertThat(atEnd.lockedDown).isFalse()
        assertThat(justAfter.lockedDown).isFalse()
    }

    @Test
    fun `a weekend window spans the inactive days between friday and monday`() {
        val window = FortressWindow.weekendToMonday(startHour = 20, endHour = 6)

        val saturday = dev.gamblock.core.model.FortressPolicy.status(
            windows = listOf(window), nowEpochMs = at(2026, 3, 14, 21), zoneId = zone,
        )
        val sunday = dev.gamblock.core.model.FortressPolicy.status(
            windows = listOf(window), nowEpochMs = at(2026, 3, 15, 21), zoneId = zone,
        )

        assertThat(saturday.lockedDown).isTrue()
        assertThat(sunday.lockedDown).isTrue()
    }

    @Test
    fun `the weekend window is not active in the middle of the week`() {
        val window = FortressWindow.weekendToMonday(startHour = 20, endHour = 6)

        val wednesday = dev.gamblock.core.model.FortressPolicy.status(
            windows = listOf(window), nowEpochMs = at(2026, 3, 11, 21), zoneId = zone,
        )

        assertThat(wednesday.lockedDown).isFalse()
    }

    @Test
    fun `no windows means never locked`() {
        val status = dev.gamblock.core.model.FortressPolicy.status(
            windows = emptyList(),
            nowEpochMs = at(2026, 3, 11, 2),
            zoneId = zone,
        )

        assertThat(status.lockedDown).isFalse()
    }

    @Test
    fun `two overlapping windows still lock once`() {
        val overnight = FortressWindow.overnightDaily(startHour = 23, endHour = 5)
        val weekend = FortressWindow.weekendToMonday(startHour = 20, endHour = 6)

        val status = dev.gamblock.core.model.FortressPolicy.status(
            windows = listOf(overnight, weekend),
            nowEpochMs = at(2026, 3, 14, 2),
            zoneId = zone,
        )

        assertThat(status.lockedDown).isTrue()
    }

    @Test
    fun `a corrupted window is clamped rather than throwing`() {
        // A value that could only come from an older or damaged store.
        val corrupted = FortressWindow(
            id = "w",
            startMinuteOfDay = 0,
            endMinuteOfDay = 0,
            activeDays = emptySet(),
        )

        val sanitized = FortressWindow.sanitize(corrupted)

        assertThat(sanitized.startMinuteOfDay).isAtLeast(0)
        assertThat(sanitized.endMinuteOfDay).isAtMost(FortressWindow.MINUTE_LIMIT)
        assertThat(sanitized.activeDays).isNotEmpty()
    }

    @Test
    fun `a weekend lock reports the day span in its label`() {
        val window = FortressWindow.weekendToMonday(startHour = 20, endHour = 6)

        assertThat(window.activeDays).containsExactly(WeekDay.FRIDAY)
        assertThat(window.endDayOffset).isEqualTo(3)
        assertThat(window.timeRangeLabel).contains("20:00")
        assertThat(window.timeRangeLabel).contains("06:00")
    }
}
