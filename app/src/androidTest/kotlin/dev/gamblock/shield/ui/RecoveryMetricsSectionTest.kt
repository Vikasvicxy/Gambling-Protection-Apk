package dev.gamblock.shield.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.gamblock.core.model.RecoveryCurrency
import dev.gamblock.core.model.RecoveryMetrics
import dev.gamblock.core.model.RecoveryMilestone
import dev.gamblock.feature.dashboard.RecoveryMetricsSection
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test 3 - Dashboard metrics widget.
 *
 * The savings figure and the streak counter are what convince someone the
 * effort is paying off, so both are asserted together with the currency symbol
 * for several locales, plus the empty state so a brand-new user is not shown a
 * misleading "0 days" streak.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryMetricsSectionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun show(metrics: RecoveryMetrics, onClick: () -> Unit = {}) {
        composeTestRule.setContent {
            RecoveryMetricsSection(metrics = metrics, onStartOrRestartStreak = onClick)
        }
    }

    @Test
    fun rendersStreakDaysAndSavings() {
        show(
            RecoveryMetrics(
                daysClean = 42,
                moneySavedMinor = 61_250L,
                currencyCode = "USD",
                reachedMilestones = listOf(RecoveryMilestone.SEVEN_DAYS, RecoveryMilestone.THIRTY_DAYS),
                nextMilestone = RecoveryMilestone.NINETY_DAYS,
                hasStartDate = true,
            ),
        )

        composeTestRule.onNodeWithText("Protected for 42 day(s)").assertIsDisplayed()
        // 61250 minor units = 612.50
        composeTestRule.onNodeWithText("Estimated money saved: \$612.50").assertIsDisplayed()
        composeTestRule.onNodeWithText("Reached: One week strong").assertIsDisplayed()
        composeTestRule.onNodeWithText("Reached: One month strong").assertIsDisplayed()
        composeTestRule.onNodeWithText("Next: Three months strong in 48 day(s)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Restart streak today").assertIsDisplayed()
    }

    /** Asserts 1234.56 renders with [code]'s symbol; see the tests below. */
    private fun assertSavingsRendered(code: String, expected: String) {
        show(
            RecoveryMetrics(
                daysClean = 7,
                moneySavedMinor = 123_456L,
                currencyCode = code,
                hasStartDate = true,
            ),
        )
        composeTestRule.onNodeWithText(expected).assertIsDisplayed()
    }

    @Test
    fun savingsUseTheIndianRupeeSymbol() {
        assertSavingsRendered("INR", "Estimated money saved: ₹1,234.56")
    }

    @Test
    fun savingsUseTheBritishPoundSymbol() {
        assertSavingsRendered("GBP", "Estimated money saved: £1,234.56")
    }

    @Test
    fun savingsUseTheJapaneseYenSymbol() {
        assertSavingsRendered("JPY", "Estimated money saved: ¥1,234.56")
    }

    @Test
    fun emptyStateDoesNotClaimAStreak() {
        show(RecoveryMetrics(daysClean = 0, moneySavedMinor = 0L))

        composeTestRule.onNodeWithText("No streak yet").assertIsDisplayed()
        composeTestRule.onNodeWithText("Estimated money saved: \$0.00").assertIsDisplayed()
        composeTestRule.onNodeWithText("Start streak today").assertIsDisplayed()
    }

    @Test
    fun startButtonReportsBack() {
        var clicks = 0
        show(RecoveryMetrics(daysClean = 0), onClick = { clicks++ })

        composeTestRule.onNodeWithText("Start streak today").performClick()
        assertThat(clicks).isEqualTo(1)
    }

    @Test
    fun currencySymbolComesFromTheProfileNotTheDeviceLocale() {
        // Guards the mapping: every supported currency must resolve to its own
        // symbol, so a mismatched code cannot silently fall back to "$".
        val expected = mapOf(
            "USD" to "$", "INR" to "₹", "GBP" to "£", "EUR" to "€", "JPY" to "¥",
        )
        expected.forEach { (code, symbol) ->
            assertThat(RecoveryCurrency.fromCodeOrSymbol(code, null).symbol).isEqualTo(symbol)
        }
        // An unknown code still degrades to the documented default.
        assertThat(RecoveryCurrency.fromCodeOrSymbol("XXX", null).symbol).isEqualTo("$")
    }
}
