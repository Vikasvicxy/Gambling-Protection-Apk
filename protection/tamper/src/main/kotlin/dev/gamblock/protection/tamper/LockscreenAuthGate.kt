package dev.gamblock.protection.tamper

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin, injectable wrapper around the platform device-credential prompt
 * ([KeyguardManager]). Uses PIN/pattern/password/biometric with no extra
 * dependencies; the decision logic lives in the pure [LockscreenAuthPolicy].
 */
@Singleton
class LockscreenAuthGate @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun isDeviceProtected(): Boolean = try {
        keyguardManager().isDeviceSecure
    } catch (_: Throwable) {
        false
    }

    fun confirmIntent(title: String, description: String? = null): Intent? = try {
        keyguardManager().createConfirmDeviceCredentialIntent(title, description)
    } catch (_: Throwable) {
        null
    }

    private fun keyguardManager(): KeyguardManager =
        context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
}