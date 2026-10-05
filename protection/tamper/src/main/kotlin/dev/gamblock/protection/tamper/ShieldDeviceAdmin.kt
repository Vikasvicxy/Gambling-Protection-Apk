package dev.gamblock.protection.tamper

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import dev.gamblock.core.common.logging.ShieldLogger
import javax.inject.Inject

/**
 * Device Administrator fallback for the uninstall guard.
 *
 * ## Why this exists
 *
 * Google Play restricts the Accessibility API to apps that are, in effect,
 * accessibility tools. Shield's uninstall guard is not one: it reads window-class
 * names to notice an uninstall attempt, and uses the Accessibility API purely to get
 * a callback at the moment the user is about to remove their own protection. That
 * leaves a real possibility that Play rejects the service permanently, so this
 * receiver keeps a second, policy-friendlier mechanism ready to swap in.
 *
 * ## How the mechanism actually works
 *
 * `device_admin_policies.xml` declares [DevicePolicyManager.POLICY_FORCE_LOCK]. A
 * device administrator holding at least one active policy is not removable by the
 * ordinary Settings path: the system blocks the uninstall and demands the
 * administrator be deactivated first. That is the whole of the protection, and it is
 * worth being precise about its limits:
 *
 *  - It stops the *impulsive* path, which is the entire premise of the guard. It does
 *    not stop a determined user: `adb shell dpm device-admin remove`, a factory reset,
 *    or deactivating the admin with the device PIN all defeat it.
 *  - It cannot lock the screen when an uninstall screen appears, so it cannot run the
 *    PIN challenge the Accessibility guard runs. It is a stiffer wall with no
 *    courtesy dialog behind it, which makes it a worse experience and a better wall.
 *  - It cannot be the default without the user's explicit consent in system settings,
 *    exactly like the Accessibility service. Nothing here runs unprompted.
 *
 * ## Compliance position
 *
 * Google Play also governs the Device Administrator API: it requires a declaration
 * and is intended for enterprise and remote-management apps. So this is a fallback
 * with its own review risk attached, not a clean escape from the Accessibility
 * policy. It is deliberately not surfaced in the UI, and the app never requests
 * activation on its own; [activationIntent] is the only way in, and nothing calls it
 * until a maintainer decides to. See `docs/store/accessibility_declaration.md` for
 * the declaration text that has to accompany any switch to this path.
 *
 * ## What it deliberately does not do
 *
 * It requests no camera, no location, no password, no wipe and no password-reset
 * policy. Those would be Play-restricted in their own right and are not needed to
 * hold an uninstall in place, so declaring them would trade a small amount of
 * review friction for a large amount of user distrust.
 */
@AndroidEntryPoint
class ShieldDeviceAdmin : DeviceAdminReceiver() {

    @Inject lateinit var logger: ShieldLogger

    override fun onEnabled(context: Context, intent: Intent) {
        // Intentionally logs nothing about the user: activation is a fact about the
        // device, and the guard's own state is the single source of truth for whether
        // protection is meant to be on.
        logger.i(TAG, "device admin fallback activated")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        logger.i(TAG, "device admin fallback deactivated")
    }

    companion object {
        private const val TAG = "DeviceAdminFallback"

        /**
         * Not in `Settings`, so it is declared here.
         *
         * Stable since API 17 and present in AOSP, but undocumented, which is why
         * [deactivationIntent] documents it as best-effort.
         */
        private const val ACTION_DEVICE_ADMIN_SETTINGS =
            "android.settings.DEVICE_ADMIN_SETTINGS"

        fun componentName(context: Context): ComponentName =
            ComponentName(context, ShieldDeviceAdmin::class.java)

        /** True when Shield currently holds device administrator rights. */
        fun isActive(context: Context): Boolean = manager(context)
            ?.isAdminActive(componentName(context)) == true

        /**
         * The system prompt that asks the user to grant device administrator rights.
         *
         * There is no public API to grant it directly, so the user has to confirm on
         * the system screen. Offering less than that would misrepresent what the app
         * can do.
         */
        fun activationIntent(context: Context): Intent =
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).putExtra(
                DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                componentName(context),
            )

        /**
         * Hands the rights back.
         *
         * `removeActiveAdmin` is the public API an administrator uses to deactivate
         * itself, and it is the only deactivation route that needs no hidden Settings
         * constant: there is no public `ACTION_..._DEVICE_ADMIN_DISABLE_REQUESTED`,
         * because deactivating an admin is something the system expects the user to do
         * from Settings > Device admin apps, or the app to do on its own behalf.
         *
         * Returns false when the rights were not held to begin with or the platform
         * refused. A caller must surface that rather than assume it worked, because
         * silently failing to release administrator rights would leave the user unable
         * to uninstall Shield.
         */
        fun deactivate(context: Context): Boolean {
            val manager = manager(context) ?: return false
            val component = componentName(context)
            if (!manager.isAdminActive(component)) return false
            return runCatching { manager.removeActiveAdmin(component) }.isSuccess
        }

        /**
         * The system screen where the user reviews and revokes administrator rights.
         *
         * Offered as the user-driven counterpart to [deactivate], because revocation is
         * exactly the kind of power a user should be able to exercise themselves.
         *
         * `Settings.ACTION_DEVICE_ADMIN_SETTINGS` is not public API, so the action
         * string is spelled out here and callers must treat starting it as
         * best-effort: on a build that does not recognise it the user simply lands in
         * Security settings, where the entry lives. Failing to open this screen must
         * never be the reason a user cannot revoke Shield's rights, which is why
         * [deactivate] exists as the reliable path.
         */
        fun deactivationIntent(context: Context): Intent =
            Intent(ACTION_DEVICE_ADMIN_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /**
         * The owning policy manager, or null where device policy is unavailable.
         *
         * Some builds and managed profiles return null rather than throwing, and a
         * guardian feature must not crash a settings screen because of that.
         */
        private fun manager(context: Context): DevicePolicyManager? =
            context.getSystemService(DevicePolicyManager::class.java)
    }
}