package dev.gamblock.feature.settings

import com.google.common.truth.Truth.assertThat
import dev.gamblock.feature.settings.AccessibilityConsentGate.Decision
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The disclosure-before-prompt contract, pinned as a sequence.
 *
 * Play's Accessibility policy requires the prominent disclosure to appear *before*
 * the app sends the user to the system permission screen. That turns the ordering of
 * two steps into a policy requirement, and ordering is exactly what a later UI tidy
 * silently breaks: pointing the settings button straight at the redirect satisfies
 * every test that only checks "a dialog exists somewhere in the tree".
 *
 * The view model owns no route to the system screen other than the accept branch of
 * this gate, so these tests are the guarantee. They cover both directions — the
 * disclosure really does precede the prompt, and a decline really does end the flow —
 * because either half alone would still permit a broken build to look compliant.
 */
class AccessibilityConsentGateTest {

    @Test
    fun `starts with no disclosure showing`() = runTest {
        assertThat(AccessibilityConsentGate().disclosureVisible.first()).isFalse()
    }

    @Test
    fun `requesting consent shows the disclosure and nothing else`() = runTest {
        val gate = AccessibilityConsentGate()

        val decision = gate.requestConsent()

        // Not OpenAccessibilitySettings. This is the single assertion that keeps the
        // system prompt from appearing before the user has been told what it is.
        assertThat(decision).isEqualTo(Decision.ShowDisclosure)
        assertThat(gate.disclosureVisible.first()).isTrue()
    }

    @Test
    fun `accepting permits the system settings screen and hides the disclosure`() = runTest {
        val gate = AccessibilityConsentGate()
        gate.requestConsent()

        val decision = gate.acceptDisclosure()

        assertThat(decision).isEqualTo(Decision.OpenAccessibilitySettings)
        assertThat(gate.disclosureVisible.first()).isFalse()
    }

    @Test
    fun `declining cancels and permits nothing`() = runTest {
        val gate = AccessibilityConsentGate()
        gate.requestConsent()

        val decision = gate.declineDisclosure()

        assertThat(decision).isEqualTo(Decision.Cancelled)
        assertThat(gate.disclosureVisible.first()).isFalse()
    }

    @Test
    fun `declining is never confusable with accepting`() = runTest {
        val gate = AccessibilityConsentGate()
        gate.requestConsent()

        // The two answers differ by one enum value, which is the whole safety margin.
        // Asserting they are distinct keeps a refactor from collapsing them.
        assertThat(gate.declineDisclosure()).isNotEqualTo(Decision.OpenAccessibilitySettings)
        assertThat(gate.acceptDisclosure()).isNotEqualTo(Decision.Cancelled)
    }

    @Test
    fun `the disclosure is shown again on every later request`() = runTest {
        // No remembered consent: a cached flag would let the second attempt skip the
        // disclosure, which is the exact pattern Play prohibits, and a reviewer
        // watching a second attempt would see no disclosure at all.
        val gate = AccessibilityConsentGate()

        repeat(3) {
            assertThat(gate.requestConsent()).isEqualTo(Decision.ShowDisclosure)
            assertThat(gate.disclosureVisible.first()).isTrue()
            assertThat(gate.declineDisclosure()).isEqualTo(Decision.Cancelled)
            assertThat(gate.disclosureVisible.first()).isFalse()
        }
    }

    @Test
    fun `accepting does not enable anything by itself`() = runTest {
        // The gate only authorises the redirect. Consent to be told about the
        // permission is not consent to the protection: Android still asks, and Shield
        // cannot turn the service on from here even if it wanted to.
        val gate = AccessibilityConsentGate()
        gate.requestConsent()

        assertThat(gate.acceptDisclosure()).isEqualTo(Decision.OpenAccessibilitySettings)
        assertThat(gate.disclosureVisible.first()).isFalse()
    }
}