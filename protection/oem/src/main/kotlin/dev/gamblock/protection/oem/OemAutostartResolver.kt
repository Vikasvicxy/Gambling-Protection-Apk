package dev.gamblock.protection.oem

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gamblock.core.model.OemKind
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Picks the best vendor autostart screen for the current device, if one is needed.
 *
 * The platform exposes no API for vendor autostart screens, so this probes a short
 * per-OEM candidate list ([OemAutostartLinks]) and returns the first entry that
 * resolves. Returns `null` when the app is already exempt, or when no vendor screen
 * exists (Pixel/Fairphone/emulator), letting callers fall back to the generic
 * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` dialog.
 */
@Singleton
class OemAutostartResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val oemInfoRepository: OemInfoRepository,
) {
    private val probe: (OemAutostartTarget) -> Boolean = { target ->
        runCatching {
            context.packageManager.resolveActivity(
                Intent().setComponent(ComponentName(target.packageName, target.className)),
                0,
            )
        }.getOrNull() != null
    }

    fun bestAutostartTarget(): OemAutostartTarget? = select(
        kind = oemInfoRepository.oemInfo.kind,
        isExempt = oemInfoRepository.batteryStatus.isIgnoringBatteryOptimizations,
        probe = probe,
    )

    companion object {
        /** Pure selection logic, split out so it is testable without Android framework stubs. */
        fun select(
            kind: OemKind,
            isExempt: Boolean,
            probe: (OemAutostartTarget) -> Boolean,
        ): OemAutostartTarget? {
            if (isExempt) return null
            return OemAutostartLinks.candidates(kind).firstOrNull(probe)
        }
    }
}