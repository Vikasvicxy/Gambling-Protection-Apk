package dev.gamblock.core.model

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Test

/**
 * These tests exist mostly to pin down that Shield cannot overstate savings.
 *
 * The behaviour being defended: a user who was unprotected for three weeks is
 * not owed three weeks of money saved, and the app must not imply otherwise
 * next to a currency symbol.
 */
class RecoveryCoverageTest {

    private val utc = ZoneOffset.UTC

    private fun at(date: LocalDate): Long = date.atStartOfDay(utc).toInstant().toEpochMilli()

    private val start = LocalDate.of(2026, 1, 1)

    @Test
    fun `marking is idempotent within a day`() {
        val now = at(LocalDate.of(2026, 3, 10))
        val once = RecoveryCoverage.mark(emptySet(), now, utc)
        val twice = RecoveryCoverage.mark(once, now, utc)
        assertThat(twice).isEqualTo(once)
        assertThat(twice).hasSize(1)
    }

    @Test
    fun `a day is marked in the local zone, not UTC`() {
        // 00:30 on the 10th in Kolkata is still the 9th in UTC. The user's day
        // is what counts, or "protected today" flickers backwards at midnight.
        val kolkata = ZoneId.of("Asia/Kolkata")
        val justAfterMidnight = LocalDate.of(2026, 3, 10)
            .atStartOfDay(kolkata)
            .plusMinutes(30)
            .toInstant()
            .toEpochMilli()
        val marked = RecoveryCoverage.mark(emptySet(), justAfterMidnight, kolkata)
        assertThat(marked).containsExactly(LocalDate.of(2026, 3, 10).toEpochDay())
    }

    @Test
    fun `consecutive days each count once`() {
        var days = emptySet<Long>()
        for (day in 1..5) {
            days = RecoveryCoverage.mark(days, at(start.plusDays((day - 1).toLong())), utc)
        }
        val protected = RecoveryCoverage.protectedDays(
            protectedEpochDays = days,
            startEpochMs = at(start),
            nowEpochMs = at(start.plusDays(4)),
            zoneId = utc,
        )
        assertThat(protected).isEqualTo(5)
    }

    @Test
    fun `a gap in coverage is not counted`() {
        // Protected on the 1st, 2nd, 10th and 11th: four days, not eleven.
        val protected = setOf(1L, 2L, 10L, 11L).map { start.plusDays(it - 1).toEpochDay() }.toSet()
        val count = RecoveryCoverage.protectedDays(
            protectedEpochDays = protected,
            startEpochMs = at(start),
            nowEpochMs = at(start.plusDays(10)),
            zoneId = utc,
        )
        assertThat(count).isEqualTo(4)
    }

    @Test
    fun `days before the recovery start are never claimed`() {
        // Shield installed late cannot retroactively protect the weeks before.
        val early = setOf(start.minusDays(40).toEpochDay(), start.toEpochDay())
        val count = RecoveryCoverage.protectedDays(
            protectedEpochDays = early,
            startEpochMs = at(start),
            nowEpochMs = at(start.plusDays(2)),
            zoneId = utc,
        )
        assertThat(count).isEqualTo(1)
    }

    @Test
    fun `no coverage observed means no protected days`() {
        val count = RecoveryCoverage.protectedDays(
            protectedEpochDays = emptySet(),
            startEpochMs = at(start),
            nowEpochMs = at(start.plusDays(30)),
            zoneId = utc,
        )
        assertThat(count).isEqualTo(0)
        assertThat(RecoveryCoverage.isKnown(emptySet())).isFalse()
    }

    @Test
    fun `a missing start date means no protected days`() {
        val count = RecoveryCoverage.protectedDays(
            protectedEpochDays = setOf(start.toEpochDay()),
            startEpochMs = null,
            nowEpochMs = at(start.plusDays(5)),
            zoneId = utc,
        )
        assertThat(count).isEqualTo(0)
    }

    @Test
    fun `a clock that moved backwards does not produce a negative count`() {
        val count = RecoveryCoverage.protectedDays(
            protectedEpochDays = setOf(start.toEpochDay()),
            startEpochMs = at(start),
            nowEpochMs = at(start.minusDays(5)),
            zoneId = utc,
        )
        assertThat(count).isEqualTo(0)
    }

    @Test
    fun `the tracked set is capped so storage cannot grow forever`() {
        val overshoot = 50
        val lastIndex = (RecoveryCoverage.MAX_TRACKED_DAYS + overshoot - 1).toLong()
        var days = emptySet<Long>()
        repeat(RecoveryCoverage.MAX_TRACKED_DAYS + overshoot) { index ->
            days = RecoveryCoverage.mark(days, at(start.plusDays(index.toLong())), utc)
        }
        assertThat(days).hasSize(RecoveryCoverage.MAX_TRACKED_DAYS)
        // The cap keeps the most recent days, which are the ones that matter,
        // and drops the oldest rather than an arbitrary slice.
        assertThat(days).contains(start.plusDays(lastIndex).toEpochDay())
        assertThat(days).doesNotContain(start.toEpochDay())
        assertThat(days.min()).isEqualTo(start.plusDays(overshoot.toLong()).toEpochDay())
    }

    @Test
    fun `coverage percent is clamped to a sane range`() {
        assertThat(RecoveryCoverage.coveragePercent(30, 30)).isEqualTo(100)
        assertThat(RecoveryCoverage.coveragePercent(0, 30)).isEqualTo(0)
        assertThat(RecoveryCoverage.coveragePercent(45, 30)).isEqualTo(100)
        assertThat(RecoveryCoverage.coveragePercent(5, 0)).isEqualTo(0)
    }
}

class RecoveryMetricsCoverageTest {

    private val utc = ZoneOffset.UTC

    private fun at(date: LocalDate): Long = date.atStartOfDay(utc).toInstant().toEpochMilli()

    private val start = LocalDate.of(2026, 1, 1)

    private fun profile(spendMinor: Long = 7_000L) = FinancialProfile(
        weeklySpendMinor = spendMinor,
        currencyCode = "USD",
        recoveryStartEpochMs = at(start),
    )

    @Test
    fun `savings follow protected days, not elapsed days`() {
        val thirtyDaysIn = at(start.plusDays(29))
        val allDays = (0L..29L).map { start.plusDays(it).toEpochDay() }.toSet()
        val metrics = RecoveryCalculator.metrics(
            profile = profile(),
            nowEpochMs = thirtyDaysIn,
            zoneId = utc,
            protectedEpochDays = allDays,
        )
        // 7000 minor * 30 days / 7 = 30000 minor
        assertThat(metrics.daysClean).isEqualTo(30)
        assertThat(metrics.protectedDays).isEqualTo(30)
        assertThat(metrics.moneySavedMinor).isEqualTo(30_000L)
    }

    @Test
    fun `a mostly unprotected month does not claim a full month of savings`() {
        val thirtyDaysIn = at(start.plusDays(29))
        val threeDays = setOf(0L, 1L, 2L).map { start.plusDays(it).toEpochDay() }.toSet()
        val metrics = RecoveryCalculator.metrics(
            profile = profile(),
            nowEpochMs = thirtyDaysIn,
            zoneId = utc,
            protectedEpochDays = threeDays,
        )
        // 7000 * 3 / 7 = 3000 minor, not 30000.
        assertThat(metrics.moneySavedMinor).isEqualTo(3_000L)
        assertThat(metrics.protectedDays).isEqualTo(3)
        assertThat(metrics.daysClean).isEqualTo(30)
    }

    @Test
    fun `the streak is not shrunk by coverage gaps`() {
        // The streak is what the user is achieving; quietly reducing it would
        // punish them for a reboot.
        val metrics = RecoveryCalculator.metrics(
            profile = profile(),
            nowEpochMs = at(start.plusDays(29)),
            zoneId = utc,
            protectedEpochDays = setOf(start.toEpochDay()),
        )
        assertThat(metrics.daysClean).isEqualTo(30)
        assertThat(metrics.protectedDays).isEqualTo(1)
        assertThat(metrics.unprotectedDays).isEqualTo(29)
    }

    @Test
    fun `no coverage yet reports unknown rather than zero savings`() {
        // "We have not observed protection" and "you saved nothing" are
        // different claims, and the UI needs to tell them apart.
        val metrics = RecoveryCalculator.metrics(
            profile = profile(),
            nowEpochMs = at(start.plusDays(29)),
            zoneId = utc,
            protectedEpochDays = emptySet(),
        )
        assertThat(metrics.coverageKnown).isFalse()
        assertThat(metrics.moneySavedMinor).isEqualTo(0L)
        assertThat(metrics.coveragePercent).isEqualTo(0)
    }

    @Test
    fun `coverage percent reflects the gap`() {
        val protected = (0L..14L).map { start.plusDays(it).toEpochDay() }.toSet()
        val metrics = RecoveryCalculator.metrics(
            profile = profile(),
            nowEpochMs = at(start.plusDays(29)),
            zoneId = utc,
            protectedEpochDays = protected,
        )
        assertThat(metrics.coveragePercent).isEqualTo(50)
    }

    @Test
    fun `protected days never exceed the streak`() {
        // Defensive: a corrupt store must not produce a nonsensical ratio.
        val bogus = (-50L..50L).map { start.plusDays(it).toEpochDay() }.toSet()
        val metrics = RecoveryCalculator.metrics(
            profile = profile(),
            nowEpochMs = at(start.plusDays(29)),
            zoneId = utc,
            protectedEpochDays = bogus,
        )
        assertThat(metrics.protectedDays).isAtMost(metrics.daysClean)
        assertThat(metrics.coveragePercent).isAtMost(100)
    }
}
