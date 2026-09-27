package dev.gamblock.core.model

import java.time.Instant
import java.time.ZoneId

/**
 * Tracks which days protection was actually running, so the money-saved figure
 * can be earned rather than assumed.
 *
 * Why this exists: `RecoveryCalculator` originally computed savings as
 * `weeklySpend * daysSinceStart / 7`. That counts every calendar day from the
 * start date, including the days the VPN was off, the days the phone was in
 * someone's bag, and the days before the user finished onboarding. It is a
 * straight-line extrapolation from one self-reported number, presented next to a
 * currency symbol, and it is the number users screenshot and share as proof that
 * something worked. It should not be able to overstate.
 *
 * So the accounting is driven by observed protection instead of by the calendar,
 * and the two are reported separately:
 *  - [RecoveryMetrics.daysClean] stays calendar-based. The streak is what the
 *    user is actually trying to achieve, and quietly shrinking it would punish
 *    someone for a reboot.
 *  - [RecoveryMetrics.protectedDays] counts only days on which the VPN actually
 *    established.
 *  - Savings are derived from protected days, and the coverage ratio is shown
 *    next to them so the number is legible rather than authoritative.
 *
 * Granularity is one day, and a day is marked as soon as protection is up. That
 * is a real limitation: this measures *whether* Shield ran, not how long it ran
 * for. A user who enables Shield for ten seconds each morning to satisfy a
 * streak will show full coverage. It is documented rather than hidden, and the
 * alternative - persisting per-minute uptime - would not make the figure any
 * more meaningful about whether the user was protected when it mattered.
 */
object RecoveryCoverage {

    /** Days of history worth keeping. Older entries stop changing the total. */
    const val MAX_TRACKED_DAYS: Int = 400

    /**
     * The epoch-day number for a timestamp in the given zone.
     *
     * Using the local date rather than a UTC boundary is deliberate: "you were
     * protected today" should mean the user's today. At 00:30 local, UTC has
     * already rolled over and a UTC-based counter would credit yesterday.
     */
    fun epochDay(epochMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(epochMs).atZone(zoneId).toLocalDate().toEpochDay()

    /**
     * Records that protection was running, returning the updated set.
     *
     * Idempotent, and capped at [MAX_TRACKED_DAYS] most-recent entries so the
     * persisted value cannot grow without bound over a multi-year install.
     */
    fun mark(
        protectedEpochDays: Set<Long>,
        nowEpochMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Set<Long> {
        val day = epochDay(nowEpochMs, zoneId)
        val merged = protectedEpochDays + day
        if (merged.size <= MAX_TRACKED_DAYS) return merged
        return merged.sortedDescending().take(MAX_TRACKED_DAYS).toSortedSet()
    }

    /**
     * Days with observed protection inside the tracked window.
     *
     * Counted from [startEpochMs] onward so an app installed after a user's
     * recovery start date cannot claim protection for the weeks before they
     * installed it.
     */
    fun protectedDays(
        protectedEpochDays: Set<Long>,
        startEpochMs: Long?,
        nowEpochMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Int {
        if (startEpochMs == null || protectedEpochDays.isEmpty()) return 0
        val firstDay = epochDay(startEpochMs, zoneId)
        val lastDay = epochDay(nowEpochMs, zoneId)
        if (lastDay < firstDay) return 0
        return protectedEpochDays.count { it in firstDay..lastDay }
    }

    /**
     * Fraction of elapsed days that had protection, as a percentage 0..100.
     *
     * Zero when the elapsed span is unknown, so the UI can show nothing rather
     * than a misleading 0% or 100%.
     */
    fun coveragePercent(
        protectedDays: Int,
        daysElapsed: Int,
    ): Int {
        if (daysElapsed <= 0) return 0
        return ((protectedDays.coerceAtLeast(0).toDouble() / daysElapsed) * 100.0)
            .toInt()
            .coerceIn(0, 100)
    }

    /**
     * Whether any coverage has been observed at all.
     *
     * Separate from [protectedDays] being zero because the two mean different
     * things to the user. "Shield has not run yet, so there is nothing to
     * estimate from" is honest; "you were protected for zero of thirty days" is
     * a statement about them that the data does not support.
     */
    fun isKnown(protectedEpochDays: Set<Long>): Boolean = protectedEpochDays.isNotEmpty()
}
