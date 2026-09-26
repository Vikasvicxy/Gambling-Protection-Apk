package dev.gamblock.core.model

import com.google.common.truth.Truth.assertThat
import java.time.ZoneId
import org.junit.Test

class RecoveryCalculatorTest {

    private val utc = ZoneId.of("UTC")

    private fun at(year: Int, month: Int, day: Int): Long =
        java.time.LocalDate.of(year, month, day)
            .atStartOfDay(utc)
            .toInstant()
            .toEpochMilli()

    @Test
    fun `no recovery start means zero days clean`() {
        val metrics = RecoveryCalculator.metrics(
            profile = FinancialProfile(weeklySpendMinor = 10_000L),
            nowEpochMs = at(2026, 3, 1),
            zoneId = utc,
        )

        assertThat(metrics.daysClean).isEqualTo(0)
        assertThat(metrics.moneySavedMinor).isEqualTo(0L)
        assertThat(metrics.hasStartDate).isFalse()
    }

    @Test
    fun `streak day is inclusive of the start day`() {
        val metrics = RecoveryCalculator.metrics(
            profile = FinancialProfile(weeklySpendMinor = 7_000L, recoveryStartEpochMs = at(2026, 3, 1)),
            nowEpochMs = at(2026, 3, 1),
            zoneId = utc,
        )

        assertThat(metrics.daysClean).isEqualTo(1)
    }

    @Test
    fun `days clean grows by one per calendar day`() {
        val metrics = RecoveryCalculator.metrics(
            profile = FinancialProfile(weeklySpendMinor = 7_000L, recoveryStartEpochMs = at(2026, 3, 1)),
            nowEpochMs = at(2026, 3, 31),
            zoneId = utc,
        )

        assertThat(metrics.daysClean).isEqualTo(31)
    }

    @Test
    fun `a clock moved backwards never yields negative days`() {
        val metrics = RecoveryCalculator.metrics(
            profile = FinancialProfile(recoveryStartEpochMs = at(2026, 3, 10)),
            nowEpochMs = at(2026, 3, 1),
            zoneId = utc,
        )

        assertThat(metrics.daysClean).isEqualTo(0)
    }

    @Test
    fun `weekly spend prorates by days over seven`() {
        // $70/week, so 7 days clean is exactly one week of savings.
        val fullWeek = RecoveryCalculator.moneySavedMinor(
            weeklySpendMinor = 7_000L,
            daysClean = 7,
        )
        val halfWeek = RecoveryCalculator.moneySavedMinor(
            weeklySpendMinor = 7_000L,
            daysClean = 3,
        )

        assertThat(fullWeek).isEqualTo(7_000L)
        assertThat(halfWeek).isEqualTo(3_000L)
    }

    @Test
    fun `zero spend or zero days means nothing saved`() {
        assertThat(RecoveryCalculator.moneySavedMinor(0L, 30)).isEqualTo(0L)
        assertThat(RecoveryCalculator.moneySavedMinor(7_000L, 0)).isEqualTo(0L)
    }

    @Test
    fun `negative spend is sanitized to zero rather than credited`() {
        assertThat(RecoveryCalculator.moneySavedMinor(-5_000L, 30)).isEqualTo(0L)
    }

    @Test
    fun `savings never overflow`() {
        val saved = RecoveryCalculator.moneySavedMinor(
            weeklySpendMinor = FinancialProfile.MAX_WEEKLY_SPEND_MINOR,
            daysClean = RecoveryCalculator.MAX_TRACKED_DAYS,
        )

        assertThat(saved).isGreaterThan(0L)
    }

    @Test
    fun `milestones are reported in order and do not duplicate`() {
        val reached = RecoveryCalculator.reachedMilestones(daysClean = 95)

        assertThat(reached).containsExactly(
            RecoveryMilestone.SEVEN_DAYS,
            RecoveryMilestone.THIRTY_DAYS,
            RecoveryMilestone.NINETY_DAYS,
        ).inOrder()
        assertThat(RecoveryCalculator.nextMilestone(95)).isEqualTo(RecoveryMilestone.ONE_YEAR)
    }

    @Test
    fun `an exact milestone day is reached not pending`() {
        assertThat(RecoveryCalculator.nextMilestone(7)).isEqualTo(RecoveryMilestone.THIRTY_DAYS)
        assertThat(RecoveryCalculator.reachedMilestones(7))
            .containsExactly(RecoveryMilestone.SEVEN_DAYS)
    }

    @Test
    fun `beyond the last milestone there is no next one`() {
        assertThat(RecoveryCalculator.nextMilestone(400)).isNull()
    }

    @Test
    fun `spend parsing accepts plain and grouped input`() {
        assertThat(FinancialProfile.parseSpendInput("70")).isEqualTo(7_000L)
        assertThat(FinancialProfile.parseSpendInput("1,250.50")).isEqualTo(125_050L)
        assertThat(FinancialProfile.parseSpendInput("")).isEqualTo(0L)
    }

    @Test
    fun `spend parsing rejects nonsense and negatives`() {
        assertThat(FinancialProfile.parseSpendInput("abc")).isNull()
        assertThat(FinancialProfile.parseSpendInput("-5")).isNull()
    }

    @Test
    fun `currency falls back to the default for unknown codes`() {
        assertThat(RecoveryCurrency.fromCodeOrSymbol("ZZZ", null))
            .isEqualTo(RecoveryCurrency.default)
        assertThat(RecoveryCurrency.fromCodeOrSymbol("inr", null))
            .isEqualTo(RecoveryCurrency.INR)
    }

    @Test
    fun `money formatting includes the currency symbol`() {
        val formatted = RecoveryCalculator.formatMoney(125_050L, RecoveryCurrency.INR)

        assertThat(formatted).startsWith(RecoveryCurrency.INR.symbol)
        assertThat(formatted).contains("1,250.50")
    }
}
