package dev.gamblock.protection.tamper

/**
 * Decides whether an accessibility window-change event represents an attempt to
 * remove Shield, and if so whether the user must pass the guardian PIN.
 *
 * Kept free of Android types so the decision can be tested directly. The policy
 * here is deliberately narrow:
 *
 * - Only *system* packages count. Shield's own screens must never trip the guard,
 *   or the app would lock the user out of its own settings.
 * - Only the app-details screen counts. Uninstall is reached from there, and
 *   treating every Settings screen as an attempt would make the app hostile to
 *   use for no security gain.
 * - A guardian PIN is only required when one is actually configured. Without a
 *   PIN there is nothing to verify, and demanding one would block everyone out of
 *   a feature they cannot use.
 */
object UninstallGuardDetector {

    /**
     * Class names that mean "uninstall is on screen", regardless of package.
     *
     * OEM package names vary far more than activity names, so matching on the
     * activity is both more robust and less likely to miss a vendor's settings app
     * we have not enumerated.
     */
    private val UNINSTALL_ACTIVITY_HINTS = listOf(
        "AppInfo",
        "InstalledAppDetails",
        "ApplicationsDetails",
        "AppDetails",
        "Uninstall",
        "UninstallActivity",
        "UninstallApp",
        "applicationdetails",
    )

    /**
     * @param selfPackageName Shield's own package id, so its own screens never trip the guard.
     * @return true when the event should present the guardian-PIN challenge.
     */
    fun shouldChallenge(
        eventPackageName: String?,
        eventClassName: String?,
        selfPackageName: String,
        guardianPinConfigured: Boolean,
    ): Boolean {
        if (!guardianPinConfigured) return false
        val pkg = eventPackageName?.trim().orEmpty()
        val cls = eventClassName?.trim().orEmpty()
        if (pkg.isEmpty()) return false
        // Our own UI must stay reachable, otherwise there is no way back in.
        if (pkg.equals(selfPackageName, ignoreCase = true)) return false
        // The activity name is the actual signal: package names vary per OEM and
        // per Android version, so a package allowlist alone would miss vendors we
        // have not enumerated, and would keep needing maintenance as new ones ship.
        return isUninstallActivity(cls)
    }

    /** True when [className] looks like an app-details or uninstall screen. */
    fun isUninstallActivity(className: String?): Boolean {
        val cls = className?.trim().orEmpty()
        if (cls.isEmpty()) return false
        return UNINSTALL_ACTIVITY_HINTS.any { cls.contains(it, ignoreCase = true) }
    }

    /**
     * True when Shield is no longer installed.
     *
     * Accessibility services are torn down when their own package is removed, so
     * this is mainly used by callers that re-check state after a challenge rather
     * than as the primary detection path.
     */
    fun isGuardianPinSatisfied(
        guardianPinConfigured: Boolean,
        guardianPinVerified: Boolean,
    ): Boolean = !guardianPinConfigured || guardianPinVerified
}