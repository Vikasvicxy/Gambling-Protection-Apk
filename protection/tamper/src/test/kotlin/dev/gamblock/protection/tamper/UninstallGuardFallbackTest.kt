package dev.gamblock.protection.tamper

import android.content.Intent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The uninstall-guard fallback notice: when it fires, and when it deliberately
 * does not.
 *
 * This is the safety net for the one failure the platform will not report: an
 * AccessibilityService being switched off produces no callback, so the guard can
 * go quiet while the settings screen still claims it is on. These tests pin both
 * directions, because a notice that nags about a guard the user never enabled is
 * as much a defect as one that stays silent when the guard is genuinely off.
 */
class UninstallGuardFallbackTest {

    private fun shouldNotify(
        guardEnabled: Boolean,
        serviceEnabled: Boolean,
    ) = UninstallGuardFallbackPolicy.shouldNotify(
        guardEnabled = guardEnabled,
        serviceEnabledInSystem = serviceEnabled,
    )

    // ---- Cases that must fire ----

    @Test
    fun `notifies when the guard is on but the service is unbound`() {
        // This is the whole point: a silent guard is the failure mode to catch.
        assertThat(shouldNotify(guardEnabled = true, serviceEnabled = false)).isTrue()
    }

    // ---- Cases that must stay quiet ----

    @Test
    fun `stays quiet when the guard is on and the service is running`() {
        // Reporting a problem that does not exist teaches people to dismiss the
        // real one.
        assertThat(shouldNotify(guardEnabled = true, serviceEnabled = true)).isFalse()
    }

    @Test
    fun `stays quiet when the user never enabled the guard`() {
        // Telling someone their guard is off when they never turned it on is noise.
        assertThat(shouldNotify(guardEnabled = false, serviceEnabled = false)).isFalse()
    }

    @Test
    fun `stays quiet when the guard is off and the service is running`() {
        assertThat(shouldNotify(guardEnabled = false, serviceEnabled = true)).isFalse()
    }

    @Test
    fun `the guard being off is the only state that notifies`() {
        // Exhaustive truth table, so a future change to the policy cannot quietly
        // widen the trigger condition.
        val combinations = listOf(
            true to true,
            true to false,
            false to true,
            false to false,
        )

        assertThat(
            combinations.filter { (enabled, service) ->
                shouldNotify(guardEnabled = enabled, serviceEnabled = service)
            },
        ).containsExactly(true to false)
    }

    // ---- Trigger actions ----

    @Test
    fun `valid actions are the post-update and post-reboot events`() {
        // Notified after an app update or a reboot, because both are points where a
        // previously enabled service can come back disabled.
        assertThat(UninstallGuardFallbackReceiver.VALID_ACTIONS).containsExactly(
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
        )
    }

    @Test
    fun `unrelated actions are not watched for`() {
        // Battery, connectivity, screen and locale broadcasts all arrive constantly.
        // Watching them would turn a safety net into spam.
        val noise = listOf(
            Intent.ACTION_BATTERY_LOW,
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_LOCALE_CHANGED,
            Intent.ACTION_AIRPLANE_MODE_CHANGED,
            Intent.ACTION_USER_PRESENT,
            Intent.ACTION_TIME_CHANGED,
        )

        assertThat(UninstallGuardFallbackReceiver.VALID_ACTIONS)
            .containsNoneIn(noise)
    }

    @Test
    fun `no action is watched more than once`() {
        assertThat(UninstallGuardFallbackReceiver.VALID_ACTIONS)
            .hasSize(UninstallGuardFallbackReceiver.VALID_ACTIONS.toSet().size)
    }
}