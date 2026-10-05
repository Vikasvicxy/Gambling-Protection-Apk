package dev.gamblock.protection.tamper

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The uninstall guard's decision rules.
 *
 * These are the load-bearing behaviours: a false positive locks the user out of
 * Settings, and a false negative lets an uninstall through unchallenged. Both are
 * worse than the status quo, so each rule below is asserted directly.
 */
class UninstallGuardDetectorTest {

    private val self = "dev.gamblock.shield"

    private fun challenge(
        pkg: String?,
        cls: String?,
        pinConfigured: Boolean = true,
    ) = UninstallGuardDetector.shouldChallenge(
        eventPackageName = pkg,
        eventClassName = cls,
        selfPackageName = self,
        guardianPinConfigured = pinConfigured,
    )

    // ---- Challenges that must fire ----

    @Test
    fun `challenges the stock app info screen`() {
        assertThat(challenge("com.android.settings", "com.android.settings.applications.AppInfoDashboardActivity"))
            .isTrue()
    }

    @Test
    fun `challenges the google package installer`() {
        assertThat(challenge("com.google.android.packageinstaller", "com.google.android.packageinstaller.UninstallActivity"))
            .isTrue()
    }

    @Test
    fun `challenges the samsung app manager`() {
        assertThat(challenge("com.samsung.android.appmanager", "com.samsung.android.appmanager.uninstall.UninstallActivity"))
            .isTrue()
    }

    @Test
    fun `challenges an unnamed vendor uninstall activity`() {
        assertThat(challenge("com.acme.corp.manager", "com.acme.corp.AppUninstallActivity"))
            .isTrue()
    }

    @Test
    fun `challenges an unlisted oem app details activity`() {
        // Package allowlists go stale as vendors ship new ones; matching the
        // activity name is what keeps coverage without maintenance.
        assertThat(challenge("com.some.future.oem", "com.some.future.oem.InstalledAppDetails"))
            .isTrue()
    }

    @Test
    fun `matches activity hints case insensitively`() {
        assertThat(challenge("com.android.settings", "COM.ANDROID.SETTINGS.APPINFODASHBOARDACTIVITY"))
            .isTrue()
    }

    @Test
    fun `treats the bare android package as settings`() {
        assertThat(challenge("android", "com.android.settings.AppInfo"))
            .isTrue()
    }

    // ---- Events that must not fire ----

    @Test
    fun `never challenges shield's own screens`() {
        // If this fired, the user could be locked out of the app that owns the
        // guard, with no in-app route back.
        assertThat(challenge(self, "dev.gamblock.shield.MainActivity")).isFalse()
    }

    @Test
    fun `never challenges shield's own settings screen`() {
        assertThat(challenge(self, "dev.gamblock.shield.ui.settings.AppInfoActivity")).isFalse()
    }

    @Test
    fun `ignores shield package case differences`() {
        assertThat(challenge("DEV.GAMBLOCK.SHIELD", "dev.gamblock.shield.MainActivity")).isFalse()
    }

    @Test
    fun `does not challenge ordinary settings screens`() {
        assertThat(challenge("com.android.settings", "com.android.settings.WifiSettingsActivity")).isFalse()
    }

    @Test
    fun `does not challenge wifi settings`() {
        assertThat(challenge("com.android.settings", "com.android.settings.NetworkDashboardActivity")).isFalse()
    }

    @Test
    fun `does not challenge third party apps`() {
        assertThat(challenge("com.spotify.music", "com.spotify.music.MainActivity")).isFalse()
    }

    @Test
    fun `does not challenge the launcher`() {
        assertThat(challenge("com.google.android.apps.nexuslauncher", "com.google.android.apps.nexuslauncher.NexusLauncherActivity"))
            .isFalse()
    }

    @Test
    fun `does not challenge a browser`() {
        assertThat(challenge("com.android.chrome", "com.google.android.apps.chrome.Main")).isFalse()
    }

    @Test
    fun `does not challenge a null package`() {
        assertThat(challenge(null, "com.android.settings.AppInfo")).isFalse()
    }

    @Test
    fun `does not challenge an empty package`() {
        assertThat(challenge("   ", "com.android.settings.AppInfo")).isFalse()
    }

    @Test
    fun `does not challenge when no class name is reported`() {
        assertThat(challenge("com.android.settings", null)).isFalse()
    }

    @Test
    fun `does not challenge when both are null`() {
        assertThat(challenge(null, null)).isFalse()
    }

    @Test
    fun `does not challenge a bare settings package with no uninstall activity`() {
        assertThat(challenge("com.android.settings", "com.android.settings.Settings")).isFalse()
    }

    // ---- PIN precondition ----

    @Test
    fun `does not challenge when no guardian pin is configured`() {
        // Nothing to verify against, so prompting would be an unusable wall.
        assertThat(challenge("com.android.settings", "com.android.settings.AppInfo", pinConfigured = false))
            .isFalse()
    }

    @Test
    fun `challenges when a pin is configured`() {
        assertThat(challenge("com.android.settings", "com.android.settings.AppInfo", pinConfigured = true))
            .isTrue()
    }

    // ---- isUninstallActivity ----

    @Test
    fun `recognises app info`() {
        assertThat(UninstallGuardDetector.isUninstallActivity("com.android.settings.applications.AppInfoDashboardActivity"))
            .isTrue()
    }

    @Test
    fun `recognises installed app details`() {
        assertThat(UninstallGuardDetector.isUninstallActivity("com.android.settings.InstalledAppDetails")).isTrue()
    }

    @Test
    fun `rejects an unrelated activity`() {
        assertThat(UninstallGuardDetector.isUninstallActivity("com.android.settings.BluetoothSettings"))
            .isFalse()
    }

    @Test
    fun `rejects null`() {
        assertThat(UninstallGuardDetector.isUninstallActivity(null)).isFalse()
    }

    @Test
    fun `rejects empty`() {
        assertThat(UninstallGuardDetector.isUninstallActivity("   ")).isFalse()
    }

    // ---- isGuardianPinSatisfied ----

    @Test
    fun `is satisfied when no pin exists`() {
        assertThat(UninstallGuardDetector.isGuardianPinSatisfied(guardianPinConfigured = false, guardianPinVerified = false))
            .isTrue()
    }

    @Test
    fun `is satisfied when the pin verified`() {
        assertThat(UninstallGuardDetector.isGuardianPinSatisfied(guardianPinConfigured = true, guardianPinVerified = true))
            .isTrue()
    }

    @Test
    fun `is not satisfied when the pin failed`() {
        assertThat(UninstallGuardDetector.isGuardianPinSatisfied(guardianPinConfigured = true, guardianPinVerified = false))
            .isFalse()
    }
}