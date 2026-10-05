package dev.gamblock.core.model

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Test

/**
 * The recovery heatmap's calendar arithmetic.
 *
 * A contribution graph is easy to get subtly wrong: an off-by-one in column
 * padding still renders as a plausible-looking grid. These assert the invariants
 * that make the layout honest rather than merely tidy.
 */
class RecoveryHeatmapTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 3, 15) // a Sunday
    private val start = 10L * 24 * 60 * 60 * 1000

    private fun grid(
        protectedDays: Set<Long> = emptySet(),
        urgeDays: Set<Long> = emptySet(),
        startEpochMs: Long? = start,
        today: LocalDate = this.today,
        firstDayOfWeek: Int = 1,
    ) = RecoveryHeatmap.build(
        protectedEpochDays = protectedDays,
        urgeEpochDays = urgeDays,
        startEpochMs = startEpochMs,
        today = today,
        zoneId = zone,
        firstDayOfWeek = firstDayOfWeek,
    )

    private fun days(vararg offsets: Int): Set<Long> =
        offsets.map { today.minusDays(it.toLong()).toEpochDay() }.toSet()

    // ---- Layout shape ----

    @Test
    fun `shows exactly twelve weeks`() {
        assertThat(grid().size).isEqualTo(RecoveryHeatmap.WEEKS)
    }

    @Test
    fun `each column holds seven days`() {
        assertThat(grid().all { it.size == 7 }).isTrue()
    }

    @Test
    fun `total cells never exceed the padded grid`() {
        // 84 days plus up to six days of leading padding.
        val total = grid().sumOf { it.size }
        assertThat(total).isAtMost(RecoveryHeatmap.DAYS + 6)
        assertThat(total).isAtLeast(RecoveryHeatmap.DAYS)
    }

    @Test
    fun `no leading padding is needed when the window starts on a monday`() {
        // 2026-03-15 minus 83 days is a Monday, so a Monday-start grid begins on a
        // real day and nothing is padded before it.
        assertThat(grid()[0].first()).isNotEqualTo(RecoveryHeatmap.Level.EMPTY)
    }

    @Test
    fun `sunday start pads by the days before the window`() {
        // The same Monday-aligned window needs exactly one empty cell before it
        // when weeks are rendered starting on Sunday.
        val grid = grid(firstDayOfWeek = 7)

        assertThat(grid[0][0]).isEqualTo(RecoveryHeatmap.Level.EMPTY)
        assertThat(grid[0][1]).isNotEqualTo(RecoveryHeatmap.Level.EMPTY)
    }

    @Test
    fun `padding never exceeds a full week`() {
        for (firstDay in 1..7) {
            val leading = grid(firstDayOfWeek = firstDay).first().count { it == RecoveryHeatmap.Level.EMPTY }
            // Real days can also be EMPTY, so this only asserts the padding bound.
            assertThat(leading).isAtMost(6)
        }
    }

    @Test
    fun `last real day is today`() {
        val flattened = grid().flatten()
        assertThat(flattened.last()).isNotEqualTo(RecoveryHeatmap.Level.EMPTY)
    }

    

    @Test
    fun `an out of range first day of week is normalised rather than crashing`() {
        val levels = grid(firstDayOfWeek = 0).flatten()
        assertThat(levels).isNotEmpty()
    }

    @Test
    fun `a nonsense first day of week is normalised rather than crashing`() {
        assertThat(grid(firstDayOfWeek = -3).flatten()).isNotEmpty()
    }

    // ---- Per-day classification ----

    @Test
    fun `a protected day with no urges is clean`() {
        val level = RecoveryHeatmap.levelOf(
            date = today,
            protectedEpochDays = days(0),
            urgeEpochDays = emptySet(),
            startEpochMs = start,
            today = today,
            zoneId = zone,
        )

        assertThat(level).isEqualTo(RecoveryHeatmap.Level.CLEAN)
    }

    @Test
    fun `a protected day with a logged urge shows the lapse shade`() {
        val level = RecoveryHeatmap.levelOf(
            date = today,
            protectedEpochDays = days(0),
            urgeEpochDays = days(0),
            startEpochMs = start,
            today = today,
            zoneId = zone,
        )

        assertThat(level).isEqualTo(RecoveryHeatmap.Level.STREAK_WITH_LAPSE)
    }

    @Test
    fun `an unprotected day inside the window is a gap`() {
        val level = RecoveryHeatmap.levelOf(
            date = today,
            protectedEpochDays = emptySet(),
            urgeEpochDays = emptySet(),
            startEpochMs = start,
            today = today,
            zoneId = zone,
        )

        assertThat(level).isEqualTo(RecoveryHeatmap.Level.GAP)
    }

    @Test
    fun `days before the streak started are empty`() {
        val dayBeforeStart = LocalDate.of(1970, 1, 9)
        val level = RecoveryHeatmap.levelOf(
            date = dayBeforeStart,
            protectedEpochDays = setOf(dayBeforeStart.toEpochDay()),
            urgeEpochDays = emptySet(),
            startEpochMs = start,
            today = today,
            zoneId = zone,
        )

        assertThat(level).isEqualTo(RecoveryHeatmap.Level.EMPTY)
    }

    @Test
    fun `future dates are empty`() {
        val level = RecoveryHeatmap.levelOf(
            date = today.plusDays(1),
            protectedEpochDays = setOf(today.plusDays(1).toEpochDay()),
            urgeEpochDays = emptySet(),
            startEpochMs = start,
            today = today,
            zoneId = zone,
        )

        assertThat(level).isEqualTo(RecoveryHeatmap.Level.EMPTY)
    }

    @Test
    fun `with no streak start nothing is reported as protected`() {
        val level = RecoveryHeatmap.levelOf(
            date = today,
            protectedEpochDays = days(0),
            urgeEpochDays = emptySet(),
            startEpochMs = null,
            today = today,
            zoneId = zone,
        )

        assertThat(level).isEqualTo(RecoveryHeatmap.Level.EMPTY)
    }

    @Test
    fun `a clean day counts as protected`() {
        assertThat(RecoveryHeatmap.Level.CLEAN.isProtected).isTrue()
        assertThat(RecoveryHeatmap.Level.STREAK_WITH_LAPSE.isProtected).isTrue()
        assertThat(RecoveryHeatmap.Level.GAP.isProtected).isFalse()
        assertThat(RecoveryHeatmap.Level.EMPTY.isProtected).isFalse()
    }

    // ---- Aggregation ----

    @Test
    fun `summary counts each level`() {
        val levels = listOf(
            RecoveryHeatmap.Level.CLEAN,
            RecoveryHeatmap.Level.CLEAN,
            RecoveryHeatmap.Level.GAP,
        )

        val counts = RecoveryHeatmap.summarise(levels)

        assertThat(counts[RecoveryHeatmap.Level.CLEAN]).isEqualTo(2)
        assertThat(counts[RecoveryHeatmap.Level.GAP]).isEqualTo(1)
    }

    @Test
    fun `summary of an empty grid is empty`() {
        assertThat(RecoveryHeatmap.summarise(emptyList())).isEmpty()
    }

    // ---- End to end ----

    @Test
    fun `a protected today shows up in the grid`() {
        val levels = grid(protectedDays = days(0)).flatten()

        assertThat(levels.count { it == RecoveryHeatmap.Level.CLEAN }).isEqualTo(1)
    }

    @Test
    fun `gaps are reported for days that were not protected`() {
        val levels = grid(protectedDays = days(0, 1, 2)).flatten()

        // Two protected days plus today; the rest of the window is a gap.
        assertThat(levels.count { it == RecoveryHeatmap.Level.GAP })
            .isEqualTo(RecoveryHeatmap.DAYS - 3)
    }

    @Test
    fun `lapse days are still counted as protected`() {
        val levels = grid(
            protectedDays = days(0, 1),
            urgeDays = days(1),
        ).flatten()

        assertThat(levels.count { it == RecoveryHeatmap.Level.STREAK_WITH_LAPSE }).isEqualTo(1)
        assertThat(levels.count { it == RecoveryHeatmap.Level.CLEAN }).isEqualTo(1)
    }

    @Test
    fun `a grid with no data at all is entirely empty or gap`() {
        val levels = grid(startEpochMs = null).flatten()

        assertThat(levels.all { it == RecoveryHeatmap.Level.EMPTY }).isTrue()
    }
}