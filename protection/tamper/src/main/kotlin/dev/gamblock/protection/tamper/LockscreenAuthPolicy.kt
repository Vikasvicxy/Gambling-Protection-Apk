package dev.gamblock.protection.tamper

/** Policy outcome for a credential-protected action. */
enum class LockscreenAuthOutcome {
    /** No auth required; perform the action. */
    ALLOW,

    /** Launch the device credential prompt before performing the action. */
    REQUIRE_PROMPT,

    /** Auth was required but the device has no lock configured; refuse to bypass. */
    DENY_NO_LOCK,

    /** Auth was required, the prompt ran, and the user cancelled/failed. */
    DENY_CANCELLED,
}

/**
 * Pure decision logic for gating destructive/sensitive actions behind the device
 * credential (PIN / pattern / password / biometric). Kept framework-free for tests.
 */
object LockscreenAuthPolicy {

    fun decide(requireAuth: Boolean, deviceProtected: Boolean, authGranted: Boolean = false): LockscreenAuthOutcome =
        when {
            !requireAuth -> LockscreenAuthOutcome.ALLOW
            !deviceProtected -> LockscreenAuthOutcome.DENY_NO_LOCK
            authGranted -> LockscreenAuthOutcome.ALLOW
            else -> LockscreenAuthOutcome.REQUIRE_PROMPT
        }

    /** Resolves a prompt result: granted leads to ALLOW, cancellation stays refused. */
    fun onPromptResult(requireAuth: Boolean, deviceProtected: Boolean, granted: Boolean): LockscreenAuthOutcome =
        when {
            !requireAuth -> LockscreenAuthOutcome.ALLOW
            !deviceProtected -> LockscreenAuthOutcome.DENY_NO_LOCK
            granted -> LockscreenAuthOutcome.ALLOW
            else -> LockscreenAuthOutcome.DENY_CANCELLED
        }
}