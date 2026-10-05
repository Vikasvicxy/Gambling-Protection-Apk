package dev.gamblock.protection.tamper

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Per-vendor coverage for the uninstall flow.
 *
 * The guard matches on activity class name rather than package, because OEM package
 * names change far more often than the class that actually shows app details. That
 * choice only holds if the class names below really do match, so each vendor's real
 * activity is asserted here rather than left to a device test nobody runs.
 *
 * Sources are the vendor Settings apps themselves; a mismatch shows up as a
 * failing test on the next pass, not as a silently unchallenged uninstall.
 */
class UninstallGuardVendorCoverageTest {

    private val self = "dev.gamblock.shield"

    private fun challenge(pkg: String, cls: String) =
        UninstallGuardDetector.shouldChallenge(
            eventPackageName = pkg,
            eventClassName = cls,
            selfPackageName = self,
            guardianPinConfigured = true,
        )

    // ---- Samsung One UI ----

    @Test
    fun `samsung one ui app info`() {
        assertThat(
            challenge(
                "com.android.settings",
                "com.android.settings.applications.AppInfoDashboardActivity",
            ),
        ).isTrue()
    }

    @Test
    fun `samsung one ui installed app details top`() {
        // The Samsung-specific details screen named in the stage brief. One UI adds
        // a "Top" variant reached from the app list, distinct from the dashboard.
        assertThat(
            challenge(
                "com.android.settings",
                "com.android.settings.applications.InstalledAppDetailsTop",
            ),
        ).isTrue()
    }

    @Test
    fun `samsung one ui uninstall confirmation`() {
        assertThat(
            challenge(
                "com.android.settings",
                "com.android.settings.applications.UninstallApplications",
            ),
        ).isTrue()
    }

    @Test
    fun `samsung settings does not challenge unrelated screens`() {
        // One UI is the most aggressive restyler of AOSP Settings, so the negative
        // cases matter more here than on any other vendor.
        assertThat(
            challenge(
                "com.android.settings",
                "com.android.settings.NetworkDashboardActivity",
            ),
        ).isFalse()
        assertThat(
            challenge("com.android.settings", "com.android.settings.BluetoothSettings"),
        ).isFalse()
        assertThat(
            challenge("com.android.settings", "com.android.settings.PrivacySettings"),
        ).isFalse()
    }

    // ---- Xiaomi / MIUI / HyperOS ----

    @Test
    fun `xiaomi miui app info`() {
        assertThat(
            challenge(
                "com.android.settings",
                "com.android.settings.applications.AppInfoDashboardActivity",
            ),
        ).isTrue()
    }

    @Test
    fun `xiaomi miui app details in the security centre`() {
        // MIUI routes app details through its own security app rather than Settings.
        assertThat(
            challenge(
                "com.miui.securitycenter",
                "com.miui.enterprise.ApplicationDetails",
            ),
        ).isTrue()
    }

    @Test
    fun `xiaomi app info settings activity`() {
        assertThat(
            challenge(
                "com.android.settings",
                "com.android.settings.AppInfoDashboardActivity",
            ),
        ).isTrue()
    }

    @Test
    fun `xiaomi security centre does not challenge unrelated screens`() {
        assertThat(
            challenge(
                "com.miui.securitycenter",
                "com.miui.enterprise.MiuiEnterpriseMainActivity",
            ),
        ).isFalse()
    }

    // ---- OnePlus OxygenOS / ColorOS ----

    @Test
    fun `oneplus oxygenos app info`() {
        assertThat(
            challenge(
                "com.android.settings",
                "com.android.settings.applications.AppInfoDashboardActivity",
            ),
        ).isTrue()
    }

    @Test
    fun `oneplus oxygenos app details`() {
        assertThat(
            challenge(
                "com.android.settings",
                "com.android.settings.applications.AppDetailsDashboardActivity",
            ),
        ).isTrue()
    }

    @Test
    fun `oneplus app install package uninstall activity`() {
        assertThat(
            challenge(
                "com.oneplus.security",
                "com.oneplus.security.chainlaunch.view.ChainLaunchAppDetailActivity",
            ),
        ).isTrue()
    }

    @Test
    fun `coloros safe centre app details`() {
        assertThat(
            challenge(
                "com.coloros.safecenter",
                "com.coloros.safecenter.appdetails.AppDetailsActivity",
            ),
        ).isTrue()
    }

    @Test
    fun `coloros app list is not a details screen`() {
        // The startup app list shows every installed app but no per-app detail view,
        // so challenging it would block browsing the whole list behind a PIN.
        assertThat(
            challenge(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            ),
        ).isFalse()
    }

    // ---- Stock Pixel / AOSP ----

    @Test
    fun `pixel app info dashboard`() {
        assertThat(
            challenge(
                "com.android.settings",
                "com.android.settings.applications.AppInfoDashboardActivity",
            ),
        ).isTrue()
    }

    @Test
    fun `pixel installed app details`() {
        assertThat(
            challenge(
                "com.android.settings",
                "com.android.settings.applications.InstalledAppDetails",
            ),
        ).isTrue()
    }

    @Test
    fun `pixel uninstall confirmation`() {
        assertThat(
            challenge(
                "com.android.settings",
                "com.android.settings.ApplicationsDetails",
            ),
        ).isTrue()
    }

    @Test
    fun `aosp application settings does not challenge unrelated screens`() {
        assertThat(
            challenge("com.android.settings", "com.android.settings.NetworkDashboardActivity"),
        ).isFalse()
        assertThat(
            challenge("com.android.settings", "com.android.settings.DisplaySettings"),
        ).isFalse()
        assertThat(
            challenge("com.android.settings", "com.android.settings.DateTimeSettings"),
        ).isFalse()
    }

    @Test
    fun `google package installer uninstall is covered`() {
        assertThat(
            challenge(
                "com.google.android.packageinstaller",
                "com.google.android.packageinstaller.UninstallActivity",
            ),
        ).isTrue()
    }

    // ---- Every vendor, one invariant ----

    @Test
    fun `all vendors resolve their real activity`() {
        // If a future refactor narrows matching to a package allowlist, this is the
        // test that should fail first, listing every vendor it dropped.
        val vendorActivities = mapOf(
            "samsung one ui" to "com.android.settings.applications.InstalledAppDetailsTop",
            "xiaomi miui" to "com.miui.enterprise.ApplicationDetails",
            "oneplus oxygenos" to "com.android.settings.applications.AppDetailsDashboardActivity",
            "coloros" to "com.android.settings.applications.InstalledAppDetails",
            "pixel" to "com.android.settings.applications.InstalledAppDetails",
            "aosp package installer" to "com.google.android.packageinstaller.UninstallActivity",
        )

        val missed = vendorActivities.filter { (_, cls) -> !UninstallGuardDetector.isUninstallActivity(cls) }

        assertThat(missed).isEmpty()
    }

    @Test
    fun `every vendors package still resolves without a package allowlist`() {
        // Guards against the regression where package filtering was introduced and
        // silently stopped matching an unenumerated vendor package.
        val vendorCases = listOf(
            "com.android.settings" to "com.android.settings.applications.AppInfoDashboardActivity",
            "com.miui.securitycenter" to "com.miui.enterprise.ApplicationDetails",
            "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppDetailActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.appdetails.AppDetailsActivity",
            "com.samsung.android.appmanager" to "com.samsung.android.appmanager.uninstall.UninstallActivity",
            "com.google.android.packageinstaller" to "com.google.android.packageinstaller.UninstallActivity",
        )

        val missed = vendorCases.filterNot { (pkg, cls) -> challenge(pkg, cls) }

        assertThat(missed).isEmpty()
    }
}