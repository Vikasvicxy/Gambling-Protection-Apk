package dev.gamblock.protection.vpn

/**
 * Maps an Android vendor to the screen that disables its battery optimisation for this app.
 *
 * ## Why this is a per-vendor problem
 *
 * Stock Android exposes a single documented behaviour: a foreground service holding an active VPN
 * is not killed for battery reasons. Every major OEM overrides that in some way, and the overrides
 * are inconsistent enough that a single hardcoded intent works on one device family and lands on
 * the wrong screen, or crashes, on the next.
 *
 * The recurring failures, in order of how damaging they are:
 *
 *  - Xiaomi/MIUI, Huawei, Oppo, Vivo and OnePlus kill a backgrounded VPN service outright and
 *    refuse to restart it, which ends protection until the user opens the app.
 *  - The same vendors add a second "app auto-start" or "protected apps" list that has to be set
 *    separately, and there is no public API for it. Those are documented in-app instead, because
 *    a wrong deep link is worse than an honest instruction.
 *  - Some builds accept the generic `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` intent but not
 *    the `EXTRA_REQUESTED_IGNORE_OPTIMIZATIONS` direct-request variant, which requires a
 *    permission that Play rejects at install time if used carelessly.
 *
 * So this returns a best-effort target, and the UI always offers the generic Settings screen as a
 * fallback rather than implying the deep link succeeded.
 *
 * No Android types here on purpose: the mapping is pure data and this is the part that must be
 * exhaustively testable, since it cannot be exercised on a test device.
 */
object OemBatteryPolicies {

    /** Vendor families that need more than the stock screen. */
    enum class Vendor(val label: String, val needsManualAutostartStep: Boolean) {
        STOCK("Android", false),
        XIAOMI("MIUI / HyperOS", true),
        HUAWEI("EMUI / HarmonyOS", true),
        OPPO("ColorOS", true),
        VIVO("FuntouchOS / OriginOS", true),
        ONEPLUS("OxygenOS", true),
        SAMSUNG("One UI", true),
        ASUS("ZenUI", false),
        LENOVO("ZUI", false),
        UNKNOWN("this device", false),
    }

    /**
     * Explicit intent component for OEMs that ship a dedicated screen.
     *
     * These are activity class names discovered from the OEM's own system UI packages. They are
     * not part of any public API and change between firmware versions, which is why each is only
     * ever used as a *candidate*: the caller tries them and falls back on any failure.
     */
    private val dedicatedComponents: Map<Vendor, List<String>> = mapOf(
        Vendor.XIAOMI to listOf(
            "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.miui.powerkeeper/com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
        ),
        Vendor.HUAWEI to listOf(
            "com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager/.appcontrol.activity.StartupAppControlActivity",
            "com.huawei.systemmanager/.optimize.process.ProtectActivity",
        ),
        Vendor.OPPO to listOf(
            "com.coloros.safecenter/.startupapp.StartupAppListActivity",
            "com.coloros.safecenter/.permission.startup.StartupAppListActivity",
            "com.oppo.safe/.permission.startup.StartupAppListActivity",
        ),
        Vendor.VIVO to listOf(
            "com.vivo.permissionmanager/.activity.BgStartUpManagerActivity",
            "com.iqoo.secure/.ui.phoneoptimize.AddWhiteListActivity",
            "com.vivo.permissionmanager/.activity.SoftPermissionDetailActivity",
        ),
        Vendor.ONEPLUS to listOf(
            "com.oneplus.security/.chainlaunch.view.view.ChainLaunchAppListActivity",
            "com.oneplus.security/.appconfig.AppConfigActivity",
        ),
        Vendor.SAMSUNG to listOf(
            "com.samsung.android.lool/com.samsung.android.sm.ui.battery.BatteryActivity",
        ),
        Vendor.ASUS to listOf("com.asus.mobilemanager/.autostart.AutoStartActivity"),
        Vendor.LENOVO to listOf("com.lenovo.security/.autostart.AutoStartActivity"),
    )

    /** Classifies a [Build.MANUFACTURER] value. */
    fun vendorFor(manufacturer: String?): Vendor {
        val m = manufacturer?.lowercase()?.trim().orEmpty()
        if (m.isEmpty()) return Vendor.UNKNOWN
        return when {
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> Vendor.XIAOMI
            m.contains("huawei") || m.contains("honor") -> Vendor.HUAWEI
            m.contains("oppo") || m.contains("realme") -> Vendor.OPPO
            m.contains("vivo") || m.contains("iqoo") -> Vendor.VIVO
            m.contains("oneplus") -> Vendor.ONEPLUS
            m.contains("samsung") -> Vendor.SAMSUNG
            m.contains("asus") || m.contains("rog") || m.contains("zenfone") -> Vendor.ASUS
            m.contains("lenovo") || m.contains("motorola") || m.contains("moto") -> Vendor.LENOVO
            m == "google" || m == "aosp" || m == "unknown" -> Vendor.STOCK
            // An unrecognised vendor is far more likely to be an aggressive skin than stock, so
            // it gets the manual auto-start guidance rather than a reassuring "nothing to do".
            else -> Vendor.UNKNOWN
        }
    }

    /** Candidate activity class names for [vendor], most specific first. */
    fun dedicatedComponentsFor(vendor: Vendor): List<String> = dedicatedComponents[vendor].orEmpty()

/**
     * The action to request an exemption for this package directly.
     *
     * Requires `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, which is a normal permission and is only
     * held so this intent can be fired. The request dialog is shown by the system and states
     * plainly that the app is a VPN, so there is nothing misleading about it.
     *
     * Deliberately `val` and not `const val`: a const is inlined at every call site and so leaves
     * no bytecode reference for Kotlin's incremental compiler to depend on, which makes a newly
     * added constant resolve as unresolved in a file compiled alongside it. These are read once
     * per settings screen, so there is nothing to gain from inlining.
     */
    val ACTION_REQUEST_IGNORE_OPTIMIZATIONS: String =
        "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"

    /** The read-only list screen, which always exists and never needs a permission. */
    val ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS: String =
        "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS"

    /** Whether the package is already exempt, so the UI can hide the prompt. */
    fun isExempt(exempt: Boolean): Boolean = exempt

    /** Human-readable name for the diagnostics card. */
    fun displayLabel(vendor: Vendor): String = vendor.label
}