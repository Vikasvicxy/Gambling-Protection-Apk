package dev.gamblock.feature.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The disclosure-before-prompt gate for the Accessibility service.
 *
 * ## Why this is a separate object rather than two booleans in the view model
 *
 * Google Play's Accessibility API policy requires the prominent disclosure to be
 * shown *before* the app sends the user to the system permission screen. That makes
 * the ordering between two steps a policy requirement rather than a UI preference, and
 * ordering is exactly what quietly breaks: someone tidying the settings screen can
 * point the button straight at the redirect, and every test that only asserts "a
 * dialog exists somewhere in the composable tree" keeps passing.
 *
 * So the sequence is owned by one small, directly testable object and the view model
 * only carries out what it decides. The view model cannot start the system screen
 * without this gate having handed it permission, so there is no second route in.
 *
 * ## Deliberately stateless across sessions
 *
 * No "already disclosed" flag is kept, in memory or otherwise. Caching consent would
 * let a later request skip the disclosure, which is the precise pattern Play
 * prohibits, and it would also mean a reviewer watching a second attempt sees no
 * disclosure at all.
 */
class AccessibilityConsentGate {

    /** What the caller is permitted to do next. */
    enum class Decision {
        /** Show the prominent disclosure. Do not open any system screen. */
        ShowDisclosure,

        /** The user agreed: Android's accessibility settings may be opened. */
        OpenAccessibilitySettings,

        /** The user declined: do nothing further. */
        Cancelled,
    }

    private val _disclosureVisible = MutableStateFlow(false)

    /** True while the pre-prompt disclosure is on screen. */
    val disclosureVisible: StateFlow<Boolean> = _disclosureVisible.asStateFlow()

    /**
     * The user asked to turn the guard on. Shows the disclosure and stops there.
     *
     * No system activity is started by this call. That is the whole contract, and
     * returning [Decision.ShowDisclosure] rather than [Decision.OpenAccessibilitySettings]
     * makes the distinction impossible to miss at the call site.
     */
    fun requestConsent(): Decision {
        _disclosureVisible.value = true
        return Decision.ShowDisclosure
    }

    /**
     * The user explicitly agreed to the disclosure.
     *
     * Returns [Decision.OpenAccessibilitySettings], so the redirect is available only
     * through this path.
     */
    fun acceptDisclosure(): Decision {
        _disclosureVisible.value = false
        return Decision.OpenAccessibilitySettings
    }

    /**
     * The user declined.
     *
     * Declining is a supported answer and must leave nothing behind, so this cannot
     * return anything that would let the caller continue to the system screen.
     */
    fun declineDisclosure(): Decision {
        _disclosureVisible.value = false
        return Decision.Cancelled
    }
}