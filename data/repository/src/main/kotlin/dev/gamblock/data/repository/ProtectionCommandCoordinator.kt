package dev.gamblock.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gamblock.core.common.clock.WallClock
import dev.gamblock.core.common.dispatcher.DispatchersProvider
import dev.gamblock.core.common.logging.Logs
import dev.gamblock.core.common.logging.ShieldLogger
import dev.gamblock.core.model.FortressPolicy
import dev.gamblock.core.model.FortressStatus
import dev.gamblock.core.model.GuardianPinPolicy
import dev.gamblock.data.preferences.GuardianPinRepository
import dev.gamblock.data.preferences.GuardianPinUnlockState
import dev.gamblock.data.preferences.RecoveryRepository
import dev.gamblock.data.preferences.SettingsRepository
import dev.gamblock.protection.vpn.ShieldVpnService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

sealed interface ProtectionActionResult {
    data object Allowed : ProtectionActionResult
    data object UrgeTimerRequired : ProtectionActionResult
    data object GuardianPinRequired : ProtectionActionResult
    data class FortressLocked(val windowLabel: String?) : ProtectionActionResult
    data class Rejected(val reason: String) : ProtectionActionResult
}

/**
 * The gate decisions the shared [ProtectionGateHolder] needs. Narrowing the
 * surface keeps the destructive-action chain testable without DataStore, Room or
 * the Android framework.
 */
interface ProtectionGateEvaluator {
    suspend fun evaluateDisable(): ProtectionActionResult

    suspend fun evaluateSensitiveChange(requiresUrgeTimer: Boolean = false): ProtectionActionResult

    suspend fun verifyGuardianPin(pin: String): Boolean

    suspend fun reLockGuardian()

    fun guardianUnlockState(): GuardianPinUnlockState
}

/**
 * Single gatekeeper for every destructive protection action (turning protection
 * off, opening settings, whitelisting, clearing history, changing the recovery
 * date). Fortress windows and the Guardian PIN are enforced here so no screen can
 * bypass them, and the 15-minute urge timer is offered before a disable.
 */
@Singleton
class ProtectionCommandCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val recoveryRepository: RecoveryRepository,
    private val guardianPinRepository: GuardianPinRepository,
    private val wallClock: WallClock,
    private val dispatchers: DispatchersProvider,
    private val logger: ShieldLogger,
) : ProtectionGateEvaluator {

    suspend fun fortressStatus(): FortressStatus = withContext(dispatchers.io) {
        val settings = settingsRepository.settings.value
        if (!settings.fortressModeEnabled) return@withContext FortressStatus()
        recoveryRepository.fortressStatusAt(wallClock.nowEpochMillis())
    }

    /**
     * True when a Guardian PIN exists, enforcement is switched on, and the user
     * has not already cleared it for the action in front of them. The unlock is
     * deliberately single-use: it is re-locked as soon as the action runs.
     */
    private suspend fun pinRequired(): Boolean = withContext(dispatchers.io) {
        val settings = settingsRepository.settings.value
        settings.guardianPinEnabled &&
            guardianPinRepository.isConfigured.value &&
            !guardianPinRepository.unlocked.value
    }

    override suspend fun evaluateDisable(): ProtectionActionResult = withContext(dispatchers.io) {
        val settings = settingsRepository.settings.value
        if (settings.fortressModeEnabled) {
            val status = recoveryRepository.fortressStatusAt(wallClock.nowEpochMillis())
            if (status.lockedDown) {
                return@withContext ProtectionActionResult.FortressLocked(status.activeWindow?.label)
            }
        }
        if (pinRequired()) {
            return@withContext ProtectionActionResult.GuardianPinRequired
        }
        if (settings.urgeTimerEnabled) {
            return@withContext ProtectionActionResult.UrgeTimerRequired
        }
        ProtectionActionResult.Allowed
    }

    override suspend fun evaluateSensitiveChange(
        requiresUrgeTimer: Boolean,
    ): ProtectionActionResult = withContext(dispatchers.io) {
        val settings = settingsRepository.settings.value
        if (settings.fortressModeEnabled) {
            val status = recoveryRepository.fortressStatusAt(wallClock.nowEpochMillis())
            if (status.lockedDown) {
                return@withContext ProtectionActionResult.FortressLocked(status.activeWindow?.label)
            }
        }
        if (pinRequired()) {
            return@withContext ProtectionActionResult.GuardianPinRequired
        }
        if (requiresUrgeTimer && settings.urgeTimerEnabled) {
            return@withContext ProtectionActionResult.UrgeTimerRequired
        }
        ProtectionActionResult.Allowed
    }

    /** Re-arms the PIN so the next gated action has to clear it again. */
    override suspend fun reLockGuardian() {
        guardianPinRepository.lock()
    }

    override suspend fun verifyGuardianPin(pin: String): Boolean = withContext(dispatchers.io) {
        guardianPinRepository.verify(pin)
    }

    override fun guardianUnlockState(): GuardianPinUnlockState = guardianPinRepository.unlockState.value

    suspend fun disableProtection(reason: String): ProtectionActionResult = withContext(dispatchers.io) {
        when (val gate = evaluateDisable()) {
            ProtectionActionResult.Allowed -> {
                settingsRepository.setVpnEnabled(false)
                ShieldVpnService.stop(context)
                logger.i(Logs.SECURITY, "protection disabled after gates cleared: $reason")
                ProtectionActionResult.Allowed
            }
            else -> gate
        }
    }

    suspend fun setFortressWindowEnabled(enabled: Boolean): Boolean = withContext(dispatchers.io) {
        when (evaluateSensitiveChange()) {
            ProtectionActionResult.Allowed -> {
                recoveryRepository.setFortressEnabled(enabled)
                settingsRepository.setFortressModeEnabled(enabled)
                true
            }
            else -> false
        }
    }

    suspend fun isPinLockedOut(): Boolean = withContext(dispatchers.io) {
        when (val state = guardianPinRepository.unlockState.value) {
            is GuardianPinUnlockState.Locked -> true
            else -> false
        }
    }

    fun fortressNextLabel(): String? =
        recoveryRepository.fortress.value.nextWindowLabel

    fun fortressIsActive(): Boolean = recoveryRepository.fortress.value.lockedDown

    fun isFortressConfigured(): Boolean =
        recoveryRepository.fortress.value.hasWindows

    fun evaluateFortressLabel(windows: List<dev.gamblock.core.model.FortressWindow>, now: Long): String? =
        FortressPolicy.status(windows, now).activeWindow?.label

    fun pinLockRemainingMs(): Long {
        val state = guardianPinRepository.unlockState.value
        val now = wallClock.nowEpochMillis()
        return if (state is GuardianPinUnlockState.Locked) {
            GuardianPinPolicy.remainingLockMs(
                dev.gamblock.core.model.GuardianPinAttempt(lockedUntilEpochMs = now + state.remainingMs),
                now,
            )
        } else {
            0L
        }
    }
}
