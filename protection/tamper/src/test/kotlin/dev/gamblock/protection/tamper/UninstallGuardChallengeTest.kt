package dev.gamblock.protection.tamper

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric coverage for the challenge overlay.
 *
 * This exists because two defects shipped in this file that no JVM-level unit test
 * could see:
 *
 *  1. [UninstallGuardChallenge.Handle] captured its tagged children in the
 *     constructor, before [UninstallGuardChallenge.build] had added them. The fields
 *     are non-null Kotlin types, so the nulls bound silently and every status
 *     update threw at runtime -- the guard worked exactly once, on a user's device,
 *     and no test noticed.
 *  2. The overlay window was created with FLAG_NOT_FOCUSABLE, so the PIN field could
 *     never take input focus and the soft keyboard never appeared. The prompt was
 *     visible and unanswerable.
 *
 * Both are now asserted below, because "it compiled" is not evidence either works.
 */
@RunWith(RobolectricTestRunner::class)
// The module compiles against SDK 37, which Robolectric refuses to emulate on
// Java 17. The overlay is plain View construction with no SDK-37-only API, so
// SDK 35 exercises the same code path. This matches the @Config(sdk = [...])
// convention already used by the Robolectric suites in data/*.
@Config(sdk = [35])
class UninstallGuardChallengeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class RecordingHost : UninstallGuardChallenge.Host {
        val entered = mutableListOf<String>()
        var dismissed = 0
        override fun onPinEntered(pin: String, view: View) {
            entered += pin
        }

        override fun onDismissed() {
            dismissed++
        }
    }

    private fun build(host: UninstallGuardChallenge.Host = RecordingHost()) =
        UninstallGuardChallenge.build(context, host) to host

    private fun ViewGroup.findTagged(tag: String): View? = findViewWithTag<View>(tag)

    // ---------- the two regressions that shipped broken ----------

    @Test
    fun `handle resolves every tagged child instead of binding null`() {
        val (handle, _) = build()

        // Must not throw. With eager construction this threw on first use.
        assertThat(handle.assertWired()).isTrue()

        val pinField = handle.root.findTagged("guard_pin")
        val status = handle.root.findTagged("guard_status")
        val confirm = handle.root.findTagged("guard_confirm")
        assertThat(pinField).isNotNull()
        assertThat(status).isNotNull()
        assertThat(confirm).isNotNull()
    }

    @Test
    fun `status updates do not throw on any state transition`() {
        val (handle, _) = build()

        // Each of these touched a field that was null at runtime before the fix.
        handle.showBadFormat()
        handle.onVerificationPending()
        handle.onVerificationFailed()
        handle.onVerificationPending()
        handle.onVerificationFailed()
    }

    @Test
    fun `pending state disables the input and failed state re-enables it`() {
        val (handle, _) = build()
        val pin = handle.root.findTagged("guard_pin") as EditText
        val confirm = handle.root.findTagged("guard_confirm") as Button

        handle.onVerificationPending()
        assertThat(pin.isEnabled).isFalse()
        assertThat(confirm.isEnabled).isFalse()

        handle.onVerificationFailed()
        assertThat(pin.isEnabled).isTrue()
        assertThat(confirm.isEnabled).isTrue()
        // The wrong PIN must not be left on screen.
        assertThat(pin.text.toString()).isEmpty()
    }

    @Test
    fun `failed verification clears the entered pin`() {
        val (handle, _) = build()
        val pin = handle.root.findTagged("guard_pin") as EditText
        pin.setText("1234")

        handle.onVerificationFailed()

        assertThat(pin.text.toString()).isEmpty()
    }

    @Test
    fun `status line renders a message for every state`() {
        val (handle, _) = build()
        val status = handle.root.findTagged("guard_status") as TextView

        handle.showBadFormat()
        val badFormat = status.text.toString()
        handle.onVerificationFailed()
        val wrong = status.text.toString()
        handle.onVerificationPending()
        val checking = status.text.toString()

        assertThat(badFormat).isNotEmpty()
        assertThat(wrong).isNotEmpty()
        assertThat(checking).isNotEmpty()
        assertThat(listOf(badFormat, wrong, checking).distinct()).hasSize(3)
    }

    // ---------- input handling ----------

    @Test
    fun `confirm submits a well formed pin to the host`() {
        val host = RecordingHost()
        val (handle, _) = build(host)
        val pin = handle.root.findTagged("guard_pin") as EditText
        val confirm = handle.root.findTagged("guard_confirm") as Button

        pin.setText("1234")
        confirm.performClick()

        assertThat(host.entered).containsExactly("1234")
    }

    @Test
    fun `a malformed pin is rejected locally and never reaches the host`() {
        val host = RecordingHost()
        val (handle, _) = build(host)
        val pin = handle.root.findTagged("guard_pin") as EditText
        val confirm = handle.root.findTagged("guard_confirm") as Button
        val status = handle.root.findTagged("guard_status") as TextView

        pin.setText("12")
        confirm.performClick()

        assertThat(host.entered).isEmpty()
        assertThat(status.text.toString())
            .isEqualTo(context.getString(R.string.uninstall_guard_challenge_bad_format))
    }

    @Test
    fun `pin entry is masked and length capped`() {
        val (handle, _) = build()
        val pin = handle.root.findTagged("guard_pin") as EditText

        // TYPE_NUMBER_VARIATION_PASSWORD renders dots, not the digits.
        assertThat(pin.inputType and android.text.InputType.TYPE_MASK_VARIATION)
            .isEqualTo(android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        assertThat(pin.inputType and android.text.InputType.TYPE_MASK_CLASS)
            .isEqualTo(android.text.InputType.TYPE_CLASS_NUMBER)
        assertThat(pin.filters.any { it is android.text.InputFilter.LengthFilter }).isTrue()
    }

    @Test
    fun `the cancel button always dismisses`() {
        val host = RecordingHost()
        val (handle, _) = build(host)

        // Dismissal must not depend on a PIN being correct. A prompt that can only be
        // escaped with a credential is a lockout waiting to happen.
        val buttons = collectButtons(handle.root)
        assertThat(buttons).isNotEmpty()
        buttons.last().performClick()

        assertThat(host.dismissed).isEqualTo(1)
        assertThat(host.entered).isEmpty()
    }

    @Test
    fun `the prompt always offers both a confirm and a cancel path`() {
        val (handle, _) = build()
        // Two buttons: confirm and cancel. A single button would be a dead end.
        assertThat(collectButtons(handle.root)).hasSize(2)
    }

    // ---------- accessibility / usability properties ----------

    @Test
    fun `every interactive element carries a content description or label`() {
        val (handle, _) = build()

        val pin = handle.root.findTagged("guard_pin") as EditText
        assertThat(pin.contentDescription.toString()).isNotEmpty()

        for (button in collectButtons(handle.root)) {
            assertThat(button.text.toString()).isNotEmpty()
        }
    }

    @Test
    fun `the root consumes touches so taps cannot reach the uninstall button`() {
        val (handle, _) = build()

        // The window floats above the system uninstall screen. If the root did not
        // consume touches, a tap near the edge would land on the real uninstall
        // button underneath and the guard would be theatre.
        assertThat(handle.root.isClickable).isTrue()
        assertThat(handle.root.isFocusable).isTrue()
    }

    @Test
    fun `the field is reachable by focus traversal so the keyboard can appear`() {
        val (handle, _) = build()
        val root = handle.root

        // FLAG_NOT_FOCUSABLE on the window made this impossible. The root must let
        // descendants take focus, and the field must be focusable itself.
        assertThat(root.descendantFocusability).isEqualTo(ViewGroup.FOCUS_AFTER_DESCENDANTS)
        val pin = handle.root.findTagged("guard_pin") as EditText
        assertThat(pin.isFocusable).isTrue()
        assertThat(pin.isFocusableInTouchMode).isTrue()
    }

    @Test
    fun `title and body text are non empty`() {
        val (handle, _) = build()

        val texts = collectTextViews(handle.root).filter { it !is EditText }
        assertThat(texts).isNotEmpty()
        assertThat(texts.any { it.text.isNullOrEmpty() && it.tag == null && it.visibility == View.VISIBLE })
            .isFalse()
    }

    private fun collectButtons(group: ViewGroup): List<Button> {
        val out = mutableListOf<Button>()
        fun walk(v: ViewGroup) {
            for (i in 0 until v.childCount) {
                val child = v.getChildAt(i)
                when {
                    child is Button -> out += child
                    child is ViewGroup -> walk(child)
                }
            }
        }
        walk(group)
        return out
    }

    private fun collectTextViews(group: ViewGroup): List<TextView> {
        val out = mutableListOf<TextView>()
        fun walk(v: ViewGroup) {
            for (i in 0 until v.childCount) {
                val child = v.getChildAt(i)
                when {
                    child is TextView -> out += child
                    child is ViewGroup -> walk(child)
                }
            }
        }
        walk(group)
        return out
    }
}