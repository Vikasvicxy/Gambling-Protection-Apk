package dev.gamblock.core.model

/**
 * Categories of app a user may want to exempt from DNS filtering.
 *
 * Exempting an app means its DNS lookups never reach Shield, so an excluded app
 * can reach a domain that Shield would otherwise block. That is a real
 * reduction in protection, which is why the UI states the trade-off plainly and
 * why [ExcludedAppCategory.WORK_SECURITY] exists alongside the obvious ones:
 * corporate VPN clients routinely refuse to run inside another VPN.
 */
enum class ExcludedAppCategory(val displayName: String) {
    BANKING("Banking & UPI"),
    PAYMENTS("Payments"),
    WORK_SECURITY("Work & corporate VPN"),
    OTHER("Other"),
}

/** A suggested exemption, offered in the picker as a one-tap starting point. */
data class SuggestedExcludedApp(
    val packageName: String,
    val label: String,
    val category: ExcludedAppCategory,
) {
    init {
        require(packageName.isNotBlank()) { "packageName must not be blank" }
        require(label.isNotBlank()) { "label must not be blank" }
    }
}

/**
 * Pre-populated suggestions for apps that commonly break under a DNS VPN.
 *
 * Honesty note: this list is a convenience, not ground truth. Package names get
 * rebranded, regionalised and forked, and no static list can be correct forever.
 * It is therefore never used to *enforce* anything. The picker lists real
 * installed packages via `PackageManager`, so a user whose bank is missing here
 * can still find and select it. Treat the entries as hints, and expect some to
 * be stale.
 */
object ExcludedAppsCatalog {

    val SUGGESTIONS: List<SuggestedExcludedApp> = buildList {
        fun add(pkg: String, label: String, category: ExcludedAppCategory) {
            // Defensive: a typo in the table must not reach the platform API.
            if (AppExclusionFilter.isValidPackageName(pkg)) {
                add(SuggestedExcludedApp(pkg, label, category))
            }
        }

        // India — UPI and banking.
        add("com.google.android.apps.nbu.paisa.user", "Google Pay", ExcludedAppCategory.PAYMENTS)
        add("com.phonepe.app", "PhonePe", ExcludedAppCategory.PAYMENTS)
        add("in.org.npci.upiapp", "BHIM UPI", ExcludedAppCategory.PAYMENTS)
        add("in.amazon.mShop.android.shopping", "Amazon Shopping", ExcludedAppCategory.PAYMENTS)
        add("com.paytm.pktv", "Paytm", ExcludedAppCategory.PAYMENTS)
        add("com.one.android.paisauser", "Paytm Money", ExcludedAppCategory.PAYMENTS)
        add("com.dreamplug.androidapp", "CRED", ExcludedAppCategory.PAYMENTS)
        add("com.hdfc.hdfcbank", "HDFC Bank", ExcludedAppCategory.BANKING)
        add("com.icicibank.mobile", "ICICI Bank", ExcludedAppCategory.BANKING)
        add("com.axis.mobile", "Axis Bank", ExcludedAppCategory.BANKING)
        add("com.sbi.sbiinone", "SBI IN-ON", ExcludedAppCategory.BANKING)
        add("com.kotak.KotakMobileBanking", "Kotak Mahindra Bank", ExcludedAppCategory.BANKING)
        add("com.bob.mobilebanking", "Bank of Baroda", ExcludedAppCategory.BANKING)
        add("in.org.npci.bhimpkg", "BHIM", ExcludedAppCategory.BANKING)
        add("com.yesbank.yescitibank", "Yes Bank", ExcludedAppCategory.BANKING)

        // Global banking and payments.
        add("com.paypal.android.p2pmobile", "PayPal", ExcludedAppCategory.PAYMENTS)
        add("com.squareup.cash", "Cash App", ExcludedAppCategory.PAYMENTS)
        add("com.revolut.revolut", "Revolut", ExcludedAppCategory.BANKING)
        add("com.monzo.monzo.android", "Monzo", ExcludedAppCategory.BANKING)
        add("com.transferwise.android", "Wise", ExcludedAppCategory.PAYMENTS)
        add("com.zelle.android.zelle", "Zelle", ExcludedAppCategory.PAYMENTS)
        add("com.onedebit.chime", "Chime", ExcludedAppCategory.BANKING)

        // Work and security tooling that refuses to nest inside another VPN.
        add("com.microsoft.teams", "Microsoft Teams", ExcludedAppCategory.WORK_SECURITY)
        add("com.Slack", "Slack", ExcludedAppCategory.WORK_SECURITY)
        add("us.zoom.videomeetings", "Zoom", ExcludedAppCategory.WORK_SECURITY)
        add("com.cisco.anyconnect.vpn.android.avf", "Cisco Secure Client", ExcludedAppCategory.WORK_SECURITY)
        add("com.paloaltonetworks.globalprotect", "GlobalProtect", ExcludedAppCategory.WORK_SECURITY)
        add("com.cloudflare.onedotonedotonedot", "1.1.1.1 / WARP", ExcludedAppCategory.WORK_SECURITY)
        add("com.nordvpn.android", "NordVPN", ExcludedAppCategory.WORK_SECURITY)
        add("com.expressvpn.android", "ExpressVPN", ExcludedAppCategory.WORK_SECURITY)
    }.sortedBy { it.label.lowercase() }

    fun suggestionsFor(category: ExcludedAppCategory): List<SuggestedExcludedApp> =
        SUGGESTIONS.filter { it.category == category }
}

/**
 * An app the picker may offer, as far as the platform will let us see it.
 *
 * [isSystemApp] is surfaced because pre-installed apps are frequently
 * un-removable, and offering one as a "bypass this app" choice wastes the
 * user's time.
 */
data class InstalledAppCandidate(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean = false,
)

/**
 * Pure list logic behind the "bypass protection for selected apps" picker.
 *
 * Separated from `PackageManager` so the ranking rules can be tested. Android
 * 11+ hides most installed apps unless the manifest declares visibility, so the
 * picker is routinely working from a partial list; these functions are written
 * to stay useful when that happens rather than pretending to be exhaustive.
 */
object InstalledAppFilter {

    /**
     * Matches on label first, then package name.
     *
     * A user hunting for "PhonePe" types the brand, not `com.phonepe.app`, but a
     * user who has read a bug report may well paste a package name instead.
     */
    fun search(
        candidates: List<InstalledAppCandidate>,
        query: String,
    ): List<InstalledAppCandidate> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return sortForPicker(candidates)
        return sortForPicker(
            candidates.filter { candidate ->
                candidate.label.lowercase().contains(needle) ||
                    candidate.packageName.lowercase().contains(needle)
            },
        )
    }

    /**
     * Catalog suggestions first (the apps most likely to need a bypass), then
     * alphabetical. Case-insensitive so "Paytm" and "paytm" sort together.
     */
    fun sortForPicker(candidates: List<InstalledAppCandidate>): List<InstalledAppCandidate> {
        val suggested = ExcludedAppsCatalog.SUGGESTIONS.map { it.packageName }.toSet()
        return candidates.sortedWith(
            compareBy(
                { if (it.packageName in suggested) 0 else 1 },
                { it.label.lowercase() },
            ),
        )
    }

    /**
     * Hides pre-installed apps unless the user has filtered for something.
     *
     * A full device list runs to hundreds of system packages, which buries the
     * handful of apps anyone actually wants. Typing a query overrides this,
     * because at that point the user is looking for something specific.
     */
    fun userFacing(
        candidates: List<InstalledAppCandidate>,
        query: String,
    ): List<InstalledAppCandidate> {
        val filtered = if (query.isBlank()) candidates.filter { !it.isSystemApp } else candidates
        return search(filtered, query)
    }
}

/**
 * Decides which requested exemptions are actually applied to the VPN.
 *
 * Kept free of Android types on purpose: `Builder.addDisallowedApplication`
 * throws `NameNotFoundException` for a package that is not installed, and the
 * platform also caps how many exemptions a single VPN may declare. Both failure
 * modes are silent-looking at the call site, so the filtering is done here where
 * it can be unit tested, and the service only ever receives a vetted list.
 */
object AppExclusionFilter {

    /**
     * Android refuses absurdly large exemption lists. The documented ceiling is
     * in the low thousands, but a real user will never approach it; capping
     * well below keeps the builder call predictable and the log honest.
     */
    const val MAX_EXCLUSIONS: Int = 200

    private val PACKAGE_PATTERN = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")

    /** True when [candidate] is shaped like an Android package name. */
    fun isValidPackageName(candidate: String): Boolean =
        candidate.length <= 255 && PACKAGE_PATTERN.matches(candidate)

    /**
     * Outcome of [select], including everything that was thrown away.
     *
     * The rejected lists are not decorative: when a user's bank silently fails
     * to be exempted, the useful question is *why*, and these are the answers.
     */
    data class Selection(
        val applied: List<String>,
        val malformed: List<String> = emptyList(),
        val notInstalled: List<String> = emptyList(),
        val protectedPackages: List<String> = emptyList(),
        val truncated: Boolean = false,
    ) {
        val isEmpty: Boolean get() = applied.isEmpty()
        val rejectedCount: Int get() = malformed.size + notInstalled.size + protectedPackages.size
    }

    /**
     * @param requested raw package names as stored by the user, in any order.
     * @param installed packages actually present on the device.
     * @param mustNeverExclude packages that must survive even if requested,
     *   i.e. Shield itself and the current default DNS/VPN resolver. Excluding
     *   the VPN's own package would tear down the tunnel mid-handshake.
     */
    fun select(
        requested: Collection<String>,
        installed: Set<String>,
        mustNeverExclude: Set<String> = emptySet(),
        limit: Int = MAX_EXCLUSIONS,
    ): Selection {
        require(limit > 0) { "limit must be positive" }

        val malformed = mutableListOf<String>()
        val notInstalled = mutableListOf<String>()
        val protected = mutableListOf<String>()
        // Sorted so the applied set is deterministic: the platform applies
        // exemptions in call order, and a stable order keeps logs diffable.
        val candidates = sortedSetOf<String>()

        for (raw in requested) {
            val pkg = raw.trim()
            when {
                pkg.isEmpty() -> Unit
                !isValidPackageName(pkg) -> malformed += pkg
                pkg in mustNeverExclude -> protected += pkg
                pkg !in installed -> notInstalled += pkg
                else -> candidates += pkg
            }
        }

        val truncated = candidates.size > limit
        return Selection(
            applied = candidates.take(limit),
            malformed = malformed,
            notInstalled = notInstalled,
            protectedPackages = protected,
            truncated = truncated,
        )
    }
}
