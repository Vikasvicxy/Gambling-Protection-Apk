package dev.gamblock.shield.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import dev.gamblock.data.repository.ProtectionActionResult
import dev.gamblock.data.repository.ProtectionGateHolder
import dev.gamblock.feature.dashboard.ProtectionGateDialog
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test 1 - Iron Shield Guardian PIN dialog.
 *
 * The dialog is the only thing between a typed PIN and a destructive action, so
 * both halves of the contract are asserted: a wrong PIN must leave the action
 * parked, and the correct PIN must release it.
 */
@RunWith(AndroidJUnit4::class)
class GuardianPinDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val evaluator = FakeGateEvaluator().apply { pinRequired = true }
    private val holder = ProtectionGateHolder(evaluator, ImmediateDispatchers, MutableClock())

    /** Shows the shared gate dialog with a destructive action parked behind it. */
    private fun parkDestructiveAction(onRun: () -> Unit) {
        composeTestRule.setContent {
            val state by holder.state.collectAsState()
            ProtectionGateDialog(state = state, holder = holder)
        }
        composeTestRule.runOnIdle {
            val result = runBlocking { holder.request { onRun() } }
            assertThat(result).isEqualTo(ProtectionActionResult.GuardianPinRequired)
        }
    }

    @Test
    fun wrongPinIsRejectedAndKeepsTheActionParked() {
        var ran = false
        parkDestructiveAction { ran = true }

        composeTestRule.onNodeWithText("Guardian PIN required").assertIsDisplayed()
        composeTestRule.onNodeWithText("This action is protected by the 4-digit PIN you set. " +
            "It is separate from your phone lock.").assertIsDisplayed()

        // A partial PIN must not offer to unlock.
        composeTestRule.onNode(hasSetTextAction()).performTextInput("99")
        composeTestRule.onNodeWithText("Unlock").assertIsNotEnabled()

        // Complete it into a wrong 4-digit PIN: the verifier must reject it.
        composeTestRule.onNode(hasSetTextAction()).performTextInput("99")
        composeTestRule.onNodeWithText("Unlock").assertIsEnabled().performClick()

        composeTestRule
            .onNodeWithText("Incorrect PIN. 2 attempts left before a cooldown.")
            .assertIsDisplayed()
        // The dialog stays up and the destructive action never ran.
        composeTestRule.onNodeWithText("Guardian PIN required").assertIsDisplayed()
        composeTestRule.runOnIdle { assertThat(ran).isFalse() }
    }

    @Test
    fun correctPinUnlocksTheGate() {
        var ran = false
        parkDestructiveAction { ran = true }

        composeTestRule.onNode(hasSetTextAction()).performTextInput(CORRECT_PIN)
        composeTestRule.onNodeWithText("Unlock").assertIsEnabled().performClick()

        // The dialog is gone and the parked action was released.
        composeTestRule.onNodeWithText("Guardian PIN required").assertDoesNotExist()
        composeTestRule.runOnIdle {
            assertThat(ran).isTrue()
            // The unlock is single-use and must not outlive the action.
            assertThat(evaluator.unlocked).isFalse()
            assertThat(evaluator.relockCount).isAtLeast(1)
        }
    }

    @Test
    fun cancelLeavesProtectionOn() {
        var ran = false
        parkDestructiveAction { ran = true }

        composeTestRule.onNodeWithText("Cancel").performClick()

        composeTestRule.onNodeWithText("Guardian PIN required").assertDoesNotExist()
        composeTestRule.runOnIdle { assertThat(ran).isFalse() }
    }
}
