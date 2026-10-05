package dev.gamblock.core.model

import java.time.LocalDate

/**
 * The per-day state behind the recovery heatmap.
 *
 * Kept in `core:model` and free of both Compose and Android so the calendar
 * arithmetic can be tested directly, which is where a contribution-graph layout
 * goes wrong: off-by-one errors in row/column indexing produce a grid that looks
 * plausible and is quietly wrong, and a heatmap that misreports recovery history
 * is worse than no heatmap.
 */
object RecoveryHeatmap {

    /** Days shown: twelve weeks, matching a conventional contribution graph. */
    const val WEEKS: Int = 12
    const val DAYS: Int = WEEKS * 7

    /**
     * One cell's state.
     *
     * [EMPTY] is a day with no data at all and [GAP] is a day inside the tracked
     * window that was not protected. They are deliberately distinct: a gap is a
     * fact about the user's recovery worth seeing, while an empty cell means we
     * simply have nothing to say about that date.
     */
    enum class Level {
        /** Outside the tracked window; nothing to report. */
        EMPTY,

        /** Inside the window, Shield was not observed running. */
        GAP,

        /** Protected, but at least one urge was logged that day. */
        STREAK_WITH_LAPSE,

        /** Protected with no logged urge. */
        CLEAN,
        ;

        val isProtected: Boolean get() = this == CLEAN || this == STREAK_WITH_LAPSE
    }

    /**
     * Builds the grid, oldest cell first, in row-major order.
     *
     * The grid always holds exactly [DAYS] cells covering the 84 days ending on
     * [today]. The first column is padded with [Level.EMPTY] so that every column
     * is a whole calendar week starting on the configured first day of week.
     *
     * @param protectedEpochDays days on which Shield was observed running.
     * @param urgeEpochDays days on which the user logged at least one urge.
     * @param startEpochMs recovery start, or null when no streak is running.
     * @param today injected rather than read from the clock so tests are stable.
     * @param firstDayOfWeek 1 = Monday through 7 = Sunday, matching
     *   [java.time.DayOfWeek.getValue].
     */
    fun build(
        protectedEpochDays: Set<Long>,
        urgeEpochDays: Set<Long>,
        startEpochMs: Long?,
        today: LocalDate,
        zoneId: java.time.ZoneId = java.time.ZoneId.systemDefault(),
        firstDayOfWeek: Int = 1,
    ): List<List<Level>> {
        val windowEnd = today
        val windowStart = windowEnd.minusDays((DAYS - 1).toLong())
        val padding = paddingBefore(windowStart, firstDayOfWeek)

        val cells = ArrayList<Level>(padding + DAYS)
        repeat(padding) { cells.add(Level.EMPTY) }
        for (offset in 0 until DAYS) {
            val date = windowStart.plusDays(offset.toLong())
            cells.add(
                levelOf(
                    date = date,
                    protectedEpochDays = protectedEpochDays,
                    urgeEpochDays = urgeEpochDays,
                    startEpochMs = startEpochMs,
                    today = windowEnd,
                    zoneId = zoneId,
                ),
            )
        }
        // A leading partial week would otherwise leave the last column short.
        val trailing = (DAYS + padding) % 7
        if (trailing != 0) repeat(7 - trailing) { cells.add(Level.EMPTY) }

        return cells.chunked(7)
    }

    /**
     * How many [Level.EMPTY] cells precede [windowStart] so the grid starts on the
     * configured first day of week.
     */
    private fun paddingBefore(windowStart: LocalDate, firstDayOfWeek: Int): Int {
        val normalized = ((firstDayOfWeek - 1) % 7 + 7) % 7 + 1
        val offset = windowStart.dayOfWeek.value - normalized
        return ((offset % 7) + 7) % 7
    }

    /** The single day's state, before any layout concerns. */
    fun levelOf(
        date: LocalDate,
        protectedEpochDays: Set<Long>,
        urgeEpochDays: Set<Long>,
        startEpochMs: Long?,
        today: LocalDate,
        zoneId: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): Level {
        if (date.isAfter(today)) return Level.EMPTY
        // No declared streak means there is no recovery to chart. Protection may
        // well have been running, but colouring those days green next to a "no
        // streak yet" headline would claim a recovery the user has not started.
        val startDay = startEpochMs?.let { epochDayOf(it, zoneId) } ?: return Level.EMPTY
        val epochDay = date.toEpochDay()
        // Likewise, days before the streak began are not gaps; nothing was owed yet.
        if (epochDay < startDay) return Level.EMPTY

        if (epochDay !in protectedEpochDays) return Level.GAP
        return if (epochDay in urgeEpochDays) Level.STREAK_WITH_LAPSE else Level.CLEAN
    }

    /** Counts by level, for the summary line under the grid. */
    fun summarise(levels: List<Level>): Map<Level, Int> =
        levels.groupingBy { it }.eachCount()

    private fun epochDayOf(epochMs: Long, zoneId: java.time.ZoneId): Long =
        java.time.Instant.ofEpochMilli(epochMs).atZone(zoneId).toLocalDate().toEpochDay()
}