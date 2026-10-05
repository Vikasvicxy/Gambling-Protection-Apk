package dev.gamblock.feature.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The prominent disclosure text, pinned.
 *
 * This test exists because of the Play Console declaration rather than because of the
 * UI. The declaration form quotes the app's disclosure verbatim, and a reviewer
 * comparing the two is entitled to find them identical. Inline copy would let the
 * string change silently at some later edit, and nothing here would notice until a
 * submission is rejected.
 *
 * The required statements, not the whole marketing sentence, are what get asserted.
 * Play checks that the disclosure covers what the service does, what data is handled
 * and how to decline; reordering or rewording around them is allowed, dropping one is
 * not.
 */
class AccessibilityDisclosureTextTest {

    @Test
    fun `states that the service detects uninstall attempts and app settings`() {
        val body = DISCLOSURE_BODY
        assertThat(body).contains("Accessibility Service API")
        assertThat(body).contains("attempt to uninstall the app")
        assertThat(body).contains("open App Settings")
    }

    @Test
    fun `states that the purpose is locking the screen for the guardian pin`() {
        assertThat(DISCLOSURE_BODY).contains("lock the screen")
        assertThat(DISCLOSURE_BODY).contains("Guardian PIN")
    }

    @Test
    fun `states that no other screen content is read`() {
        // Must stay in step with canRetrieveWindowContent="false" in
        // shield_accessibility_guard.xml. If the service ever starts retrieving
        // window content, this sentence becomes a false claim to users and to Play.
        assertThat(DISCLOSURE_BODY).contains("No other screen content is read")
    }

    @Test
    fun `states that no data is collected or leaves the device`() {
        assertThat(DISCLOSURE_BODY).contains("no data is collected or sent off your device")
    }

    @Test
    fun `matches the declaration text verbatim`() {
        // The clause tests above cover what Play requires the disclosure to say. This
        // one covers the other half of the promise: that the form quotes this exact
        // string, so the app and the submission are saying one thing rather than two
        // near-identical ones. Rewording the app copy means updating the declaration
        // in the same commit, which is the point of the failure.
        assertThat(DISCLOSURE_BODY).isEqualTo(
            "Shield uses the Accessibility Service API to detect when you attempt to " +
                "uninstall the app or open App Settings during a moment of weakness. " +
                "This allows Shield to lock the screen and ask for your Guardian PIN. " +
                "No other screen content is read, and no data is collected or sent off " +
                "your device.",
        )
    }

    @Test
    fun `is written as prose rather than a placeholder`() {
        // Cheap guard against a stubbed-out constant reaching a real submission.
        assertThat(DISCLOSURE_BODY.length).isAtLeast(200)
        assertThat(DISCLOSURE_BODY).doesNotContain("TODO")
        assertThat(DISCLOSURE_BODY).doesNotContain("Lorem")
    }
}