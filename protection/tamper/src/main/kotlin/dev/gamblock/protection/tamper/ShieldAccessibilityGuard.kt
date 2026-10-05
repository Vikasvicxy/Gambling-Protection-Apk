package dev.gamblock.protection.tamper

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import dagger.hilt.android.AndroidEntryPoint
import dev.gamblock.data.preferences.GuardianPinRepository
import dev.gamblock.data.preferences.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Blocks the Settings uninstall flow for Shield until the guardian PIN is entered.
 *
 * ## What this does and does not do
 *
 * It raises a PIN challenge when a system app-details or uninstall screen is on
 * top. It does not, and cannot, remove the uninstall button: Shield does not own
 * that window. What stands between the user and the tap is the overlay prompt,
 * which is dismissed only by the correct PIN.
 *
 * Shield's own screens are explicitly never challenged, and the overlay can always
 * be dismissed, so a user who knows the PIN can disable the guard from inside the
 * app. That is an intentional limit rather than an oversight: a guardian feature
 * that can strand someone on their own phone is worse than no guardian feature.
 *
 * ## Play policy
 *
 * Google Play restricts the Accessibility API to accessibility tools and requires a
 * prominent disclosure. This is opt-in, off by default, disclosed in Settings >
 * Security, and self-disableable from inside the app, but shipping it on Play still
 * carries real review risk that the maintainers must weigh before release.
 */
@AndroidEntryPoint
class ShieldAccessibilityGuard : AccessibilityService() {

    @Inject lateinit var guardianPinRepository: GuardianPinRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var state: UninstallGuardState

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var overlay: View? = null
    private var challenge: UninstallGuardChallenge.Handle? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        state.onRunningChanged(isRunning = true)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString()
        val cls = event.className?.toString()

        scope.launch {
            if (!settingsRepository.settings.value.uninstallGuardEnabled) {
                dismissOverlay()
                return@launch
            }
            val challengeWanted = UninstallGuardDetector.shouldChallenge(
                eventPackageName = pkg,
                eventClassName = cls,
                selfPackageName = packageName,
                guardianPinConfigured = guardianPinRepository.isConfigured.value,
            )
            if (challengeWanted) showOverlay() else dismissOverlay()
        }
    }

    /**
     * Adds the challenge overlay unless it is already showing.
     *
     * TYPE_WINDOW_STATE_CHANGED fires repeatedly for a single screen, so the guard
     * against stacking overlays is simply "is one already attached".
     */
    private fun showOverlay() {
        if (overlay != null) return
        val handle = UninstallGuardChallenge.build(
            context = this,
            host = object : UninstallGuardChallenge.Host {
                override fun onPinEntered(pin: String, view: View) {
                    challenge?.onVerificationPending()
                    scope.launch {
                        if (guardianPinRepository.verify(pin)) {
                            // The repository's unlocked state carries forward, so the
                            // next window change does not re-prompt immediately.
                            dismissOverlay()
                        } else {
                            challenge?.onVerificationFailed()
                        }
                    }
                }

                override fun onDismissed() = dismissOverlay()
            },
        )
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            // An accessibility service may add overlay windows without
            // SYSTEM_ALERT_WINDOW, which is what keeps this feature from demanding a
            // permission the user would rightly be wary of granting.
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // FLAG_NOT_FOCUSABLE is deliberately NOT set. It would stop the window
            // receiving input focus, which in turn stops the PIN EditText from
            // raising the keyboard -- the prompt would be visible but impossible to
            // answer. Focus is taken so the field can be typed into immediately.
            //
            // FLAG_NOT_TOUCH_MODAL is kept so the overlay only takes input it draws.
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }

        val manager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { manager.addView(handle.root, params) }
            .onSuccess {
                overlay = handle.root
                challenge = handle
                state.onChallengeRaised()
                // Move focus to the PIN field now the window has focus. Without this
                // the user taps the field manually before anything is accepted.
                handle.root.findFocus()?.clearFocus()
                handle.root.requestFocus()
            }
            // If the window cannot be added we simply do not challenge. Failing to
            // attach a view must never take the service down.
            .onFailure { state.onWindowCleared() }
    }

    private fun dismissOverlay() {
        val view = overlay ?: return
        overlay = null
        challenge = null
        val manager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { manager.removeView(view) }
        state.onWindowCleared()
    }

    private fun teardown() {
        dismissOverlay()
        state.onRunningChanged(isRunning = false)
        scope.cancel()
    }

    companion object {
        /**
         * The Settings screen for enabling this service.
         *
         * There is no public intent that turns a specific accessibility service on,
         * so the user has to confirm it themselves. Offering anything less would
         * misrepresent what the app can do.
         */
        fun accessibilitySettingsIntent(): Intent =
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        /** True when this service is currently enabled in system Settings. */
        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, ShieldAccessibilityGuard::class.java)
            return try {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                ).orEmpty().split(':').any { entry ->
                    ComponentName.unflattenFromString(entry)?.equals(expected) == true
                }
            } catch (_: SecurityException) {
                false
            }
        }
    }
}

/**
 * Process-wide view of the guard so the settings screen can report live status
 * without holding a reference to the service.
 */
@Singleton
class UninstallGuardState @Inject constructor() {

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _challenging = MutableStateFlow(false)
    val challenging: StateFlow<Boolean> = _challenging.asStateFlow()

    fun onRunningChanged(isRunning: Boolean) {
        _running.value = isRunning
        if (!isRunning) _challenging.value = false
    }

    fun onChallengeRaised() {
        _challenging.value = true
    }

    fun onWindowCleared() {
        _challenging.value = false
    }
}