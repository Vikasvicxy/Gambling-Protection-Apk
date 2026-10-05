package dev.gamblock.protection.tamper

import android.content.res.XmlResourceParser
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The capability set Shield asks of the Accessibility API, pinned against the real
 * merged resource.
 *
 * Play decides the Accessibility question from what this service declares, not from
 * what the app says about itself, and every attribute here narrows what Shield can
 * observe. A future edit that widens any of them is a compliance change that should
 * fail the build rather than reach a review unannounced.
 *
 * The two directions pull against each other, which is why both are asserted.
 * `canRetrieveWindowContent` stays false so the app can honestly tell users no screen
 * content is read. `packageNames` stays absent because a per-vendor allowlist would
 * stop the guard recognising real OEM uninstall screens.
 *
 * Read via [R] rather than a hardcoded path: the assertion has to cover the merged
 * resource the service actually points at, not the file on disk.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccessibilityGuardConfigTest {

    @Test
    fun `does not retrieve window content`() {
        // The load-bearing attribute. If it becomes true, the prominent disclosure in
        // feature:settings stops being accurate and the Play declaration quoted
        // alongside it becomes a false statement to users and to reviewers.
        assertThat(guardAttribute("canRetrieveWindowContent")).isEqualTo("false")
    }

    @Test
    fun `requests only window state changes`() {
        // typeWindowStateChanged is the one signal UninstallGuardDetector reads.
        // Requesting more would widen observation for no functional gain.
        assertThat(guardAttribute("accessibilityEventTypes")).isEqualTo(TYPE_WINDOW_STATE_CHANGED)
    }

    @Test
    fun `requests no window content and no extra feedback`() {
        // feedbackGeneric and flagDefault are the least capable settings available.
        assertThat(guardAttribute("accessibilityFeedbackType")).isEqualTo(FEEDBACK_GENERIC)
        assertThat(guardAttribute("accessibilityFlags")).isEqualTo(FLAG_DEFAULT)
    }

    @Test
    fun `restricts no package names`() {
        // Deliberate. Settings and package-installer package names differ per vendor
        // and per Android version, so an allowlist would break the guard on exactly
        // the devices it is meant to protect.
        assertThat(guardAttribute("packageNames")).isNull()
    }

    @Test
    fun `describes the service in wording the user can check against reality`() {
        // Shown to the user on the Android consent screen and quoted in the Play
        // declaration, so the wording is a compliance artefact rather than a label.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val description = context.getString(R.string.uninstall_guard_service_description)
        assertThat(description).contains("does not read screen contents")
        assertThat(description).contains("collect anything")
        assertThat(description).contains("send it anywhere")
    }

    @Test
    fun `labels the service as a guardian guard, not an assistive tool`() {
        // The system list shows this name next to a switch. Calling it anything
        // assistive would invite a user to grant it for the wrong reason.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertThat(context.getString(R.string.uninstall_guard_service_label))
            .contains("uninstall guard")
    }

    /**
     * The `accessibility-service` attributes are resolved to their compiled integer
     * form by aapt, so the expectations are written as the same constants rather than
     * as the decimal or hex they happen to compile to today. The comparison still
     * catches a widened request; it does not break if aapt changes its encoding.
     */
    private fun guardAttribute(name: String): String? {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.resources.getXml(R.xml.shield_accessibility_guard).use { parser ->
            return parser.attribute(name)
        }
    }
}

/** Walks this single-element config document for one attribute value. */
private fun XmlResourceParser.attribute(name: String): String? {
    var event = next()
    while (event != XmlResourceParser.END_DOCUMENT) {
        if (event == XmlResourceParser.START_TAG) {
            for (index in 0 until attributeCount) {
                // Names come back without the android: prefix.
                if (getAttributeName(index) == name) return getAttributeValue(index)
            }
        }
        event = next()
    }
    return null
}

// AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
private const val TYPE_WINDOW_STATE_CHANGED = "0x20"

// AccessibilityServiceInfo.FEEDBACK_GENERIC
private const val FEEDBACK_GENERIC = "0x10"

// AccessibilityServiceInfo.FLAG_DEFAULT
private const val FLAG_DEFAULT = "0x1"