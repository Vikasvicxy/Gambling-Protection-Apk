package dev.gamblock.shield.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.gamblock.core.model.UrgeTimerConfig
import dev.gamblock.data.repository.GateKind
import dev.gamblock.data.repository.ProtectionActionResult
import dev.gamblock.data.repository.ProtectionGateHolder
import dev.gamblock.data.repository.ProtectionGateUiState
import dev.gamblock.feature.dashboard.ProtectionGateDialog
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test 2 - Urge timer dialog: the countdown, the exact pledge, and cancelling.
 *
 * The dialog derives the remaining time from the deadline the holder publishes
 * instead of owning a timer, so every countdown case is driven by an explicit
 * deadline/now pair. That keeps the displayed mm:ss exact rather than flaky.
 */
@RunWith(AndroidJUnit4::class)
class UrgeTimerDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val clock = MutableClock()
    private val evaluator = FakeGateEvaluator().apply { timerEnabled = true }
    private val holder = ProtectionGateHolder(evaluator, ImmediateDispatchers, clock)
    private val pledge = UrgeTimerConfig.DEFAULT_PLEDGE
    private val start = 1_000L
    private val fullTimer = ProtectionGateHolder.URGE_TIMER_DURATION_MS

    /** Renders a timer gate with a fixed deadline, driven by [now]. */
    private fun showTimer(deadline: Long, now: Long) {
        val state = ProtectionGateUiState(
            kind = GateKind.UrgeTimer,
            urgeUnlockAtEpochMs = deadline,
            urgePledgePhrase = pledge,
        )
        composeTestRule.setContent {
            ProtectionGateDialog(state = state, holder = holder, nowProvider = { now })
        }
    }

    @Test
    fun countdownShowsTheFullFifteenMinutes() {
        showTimer(deadline = start + fullTimer, now = start)
        composeTestRule.onNodeWithText("15:00").assertIsDisplayed()
    }

    @Test
    fun countdownShowsRemainingMinutesAndSeconds() {
        showTimer(deadline = start + 95_000L, now = start + 5_000L)
        composeTestRule.onNodeWithText("1:30").assertIsDisplayed()
    }

    @Test
    fun pledgeFieldStaysLockedUntilTheTimerElapses() {
        showTimer(deadline = start + fullTimer, now = start)

        composeTestRule.onNodeWithText("Cooling-off timer").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "Most urges fade within minutes. Turning protection off has to wait.",
        ).assertIsDisplayed()
        // The exact phrase is shown, but the field is not editable and there is
        // no way to submit while time remains. (A disabled Compose text field
        // drops its SetText semantics action, so assert on its absence.)
        composeTestRule.onNodeWithText("Type: $pledge").assertIsDisplayed()
        composeTestRule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        composeTestRule.onNodeWithText("Continue").assertIsNotEnabled()
    }

    @Test
    fun pledgeMustMatchExactlyBeforeContinuing() {
        // Timer already elapsed, so the field is live but still gated.
        showTimer(deadline = start, now = start + fullTimer)
        composeTestRule.onNodeWithText("The 15 minutes are up. Type the pledge to continue.")
            .assertIsDisplayed()
        composeTestRule.onNode(hasSetTextAction()).assertIsEnabled()

        // Nearly right: one wrong character and Continue stays disabled.
        composeTestRule.onNode(hasSetTextAction())
            .performTextInput("I choose my future over a bet!")
        composeTestRule.onNodeWithText("Continue").assertIsNotEnabled()

        // Exactly right, then cleared and retyped to prove the gate is the
        // phrase itself rather than "something was typed".
        composeTestRule.onNode(hasSetTextAction()).performTextInput(pledge)
        composeTestRule.onNode(hasSetTextAction()).performTextClearance()
        composeTestRule.onNode(hasSetTextAction()).performTextInput(pledge)
        composeTestRule.onNodeWithText("Continue").assertIsEnabled()
    }

    @Test
    fun cancelResetsTheTimerAndKeepsProtectionOn() {
        var ran = false
        composeTestRule.setContent {
            val state by holder.state.collectAsState()
            ProtectionGateDialog(state = state, holder = holder, nowProvider = { clock.nowMs })
        }
        composeTestRule.runOnIdle {
            val result = runBlocking { holder.request { ran = true } }
            assertThat(result).isEqualTo(ProtectionActionResult.UrgeTimerRequired)
        }
        composeTestRule.onNodeWithText("Cooling-off timer").assertIsDisplayed()

        composeTestRule.onNodeWithText("Cancel & Stay Protected").performClick()

        // The gate is gone and the action never ran.
        composeTestRule.onNodeWithText("Cooling-off timer").assertDoesNotExist()
        composeTestRule.runOnIdle { assertThat(ran).isFalse() }

        // Asking again starts a full countdown rather than resuming the old one.
        composeTestRule.runOnIdle {
            clock.nowMs += 3 * 60_000L
            runBlocking { holder.request { ran = true } }
            assertThat(holder.state.value.urgeUnlockAtEpochMs)
                .isEqualTo(clock.nowMs + fullTimer)
            assertThat(ran).isFalse()
        }
    }
}
