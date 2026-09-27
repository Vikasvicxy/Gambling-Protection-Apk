package dev.gamblock.core.model

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.serialization.Serializable

@Serializable
enum class RecoveryCurrency(val symbol: String, val code: String, val label: String) {
    USD("$", "USD", "US Dollar"),
    INR("₹", "INR", "Indian Rupee"),
    GBP("£", "GBP", "British Pound"),
    EUR("€", "EUR", "Euro"),
    JPY("¥", "JPY", "Japanese Yen"),
    KRW("₩", "KRW", "South Korean Won"),
    AUD("A$", "AUD", "Australian Dollar"),
    CAD("C$", "CAD", "Canadian Dollar"),
    SGD("S$", "SGD", "Singapore Dollar"),
    BRL("R$", "BRL", "Brazilian Real"),
    MXN("MX$", "MXN", "Mexican Peso"),
    ZAR("R", "ZAR", "South African Rand"),
    NGN("₦", "NGN", "Nigerian Naira"),
    KES("KSh", "KES", "Kenyan Shilling"),
    PKR("₨", "PKR", "Pakistani Rupee"),
    BDT("৳", "BDT", "Bangladeshi Taka"),
    LKR("Rs", "LKR", "Sri Lankan Rupee"),
    PHP("₱", "PHP", "Philippine Peso"),
    IDR("Rp", "IDR", "Indonesian Rupiah"),
    MYR("RM", "MYR", "Malaysian Ringgit"),
    AED("AED", "AED", "UAE Dirham"),
    SAR("SAR", "SAR", "Saudi Riyal"),
    EGP("E£", "EGP", "Egyptian Pound"),
    TRY("₺", "TRY", "Turkish Lira"),
    RUB("₽", "RUB", "Russian Ruble"),
    VND("₫", "VND", "Vietnamese Dong"),
    THB("฿", "THB", "Thai Baht"),
    ;

    companion object {
        val default: RecoveryCurrency = USD

        fun fromCodeOrSymbol(code: String?, symbol: String?): RecoveryCurrency {
            val normalizedCode = code?.trim()?.uppercase().orEmpty()
            if (normalizedCode.isNotEmpty()) {
                entries.firstOrNull { it.code == normalizedCode }?.let { return it }
            }
            val normalizedSymbol = symbol?.trim().orEmpty()
            if (normalizedSymbol.isNotEmpty()) {
                entries.firstOrNull { it.symbol == normalizedSymbol }?.let { return it }
            }
            return default
        }
    }
}

@Serializable
data class FinancialProfile(
    val weeklySpendMinor: Long = 0L,
    val currencyCode: String = RecoveryCurrency.default.code,
    val recoveryStartEpochMs: Long? = null,
) {
    val currency: RecoveryCurrency
        get() = RecoveryCurrency.fromCodeOrSymbol(currencyCode, null)

    val hasProfile: Boolean
        get() = weeklySpendMinor > 0L || recoveryStartEpochMs != null

    val weeklySpendMajor: Double
        get() = weeklySpendMinor / 100.0

    companion object {
        const val MAX_WEEKLY_SPEND_MINOR: Long = 100_000_000_00L
        const val MIN_WEEKLY_SPEND_MINOR: Long = 0L

        fun sanitize(weeklySpendMinor: Long): Long =
            weeklySpendMinor.coerceIn(MIN_WEEKLY_SPEND_MINOR, MAX_WEEKLY_SPEND_MINOR)

        fun parseSpendInput(raw: String): Long? {
            val cleaned = raw.trim().replace(",", "").replace(" ", "")
            if (cleaned.isEmpty()) return 0L
            val value = cleaned.toDoubleOrNull() ?: return null
            if (value.isNaN() || value.isInfinite() || value < 0.0) return null
            return sanitize(Math.round(value * 100.0))
        }
    }
}

@Serializable
enum class RecoveryMilestone(val days: Int, val title: String) {
    SEVEN_DAYS(7, "One week strong"),
    THIRTY_DAYS(30, "One month strong"),
    NINETY_DAYS(90, "Three months strong"),
    ONE_YEAR(365, "One year strong"),
    ;

    companion object {
        val ordered: List<RecoveryMilestone> = entries.sortedBy { it.days }

        fun forDays(days: Int): RecoveryMilestone? = ordered.lastOrNull { it.days <= days }
    }
}

@Serializable
data class RecoveryMetrics(
    val daysClean: Int = 0,
    val moneySavedMinor: Long = 0L,
    val currencyCode: String = RecoveryCurrency.default.code,
    val nextMilestone: RecoveryMilestone? = null,
    val reachedMilestones: List<RecoveryMilestone> = emptyList(),
    val hasProfile: Boolean = false,
    val hasStartDate: Boolean = false,
    /**
     * Days on which Shield was observed running, within the tracked window.
     *
     * Not the same as [daysClean], and never larger than it. See
     * [RecoveryCoverage] for why savings are computed from this rather than
     * from elapsed time.
     */
    val protectedDays: Int = 0,
    /**
     * True once at least one protected day has been observed.
     *
     * When false, [moneySavedMinor] is zero because there is nothing to
     * estimate from, which is a different statement from "you saved nothing".
     * The UI shows a "starts after the first day" message instead of a figure.
     */
    val coverageKnown: Boolean = false,
    /** [RecoveryCoverage.coveragePercent] of [protectedDays] over [daysClean]. */
    val coveragePercent: Int = 0,
) {
    val currency: RecoveryCurrency
        get() = RecoveryCurrency.fromCodeOrSymbol(currencyCode, null)

    val moneySavedMajor: Double
        get() = moneySavedMinor / 100.0

    val daysUntilNextMilestone: Int
        get() = nextMilestone?.let { (it.days - daysClean).coerceAtLeast(0) } ?: 0

    /** Days elapsed without observed protection, for honest gap reporting. */
    val unprotectedDays: Int
        get() = if (coverageKnown) (daysClean - protectedDays).coerceAtLeast(0) else 0
}

object RecoveryCalculator {

    const val MAX_TRACKED_DAYS: Int = 100 * 365

    /**
     * @param protectedEpochDays days on which Shield was observed running, as
     *   epoch-day numbers. Empty means "not observed yet", which yields a zero
     *   savings figure with [RecoveryMetrics.coverageKnown] false, rather than
     *   the old behaviour of extrapolating across every calendar day since the
     *   start date.
     */
    fun metrics(
        profile: FinancialProfile,
        nowEpochMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        protectedEpochDays: Set<Long> = emptySet(),
    ): RecoveryMetrics {
        val currencyCode = profile.currencyCode
        val spend = FinancialProfile.sanitize(profile.weeklySpendMinor)
        val daysClean = daysProtected(
            startEpochMs = profile.recoveryStartEpochMs,
            nowEpochMs = nowEpochMs,
            zoneId = zoneId,
        )
        val protectedDays = RecoveryCoverage.protectedDays(
            protectedEpochDays = protectedEpochDays,
            startEpochMs = profile.recoveryStartEpochMs,
            nowEpochMs = nowEpochMs,
            zoneId = zoneId,
        )
        val coverageKnown = RecoveryCoverage.isKnown(protectedEpochDays)
        return RecoveryMetrics(
            daysClean = daysClean,
            // Saved money follows observed protection, not elapsed time. A user
            // who was unprotected for three weeks is not owed three weeks of
            // savings, and quoting it would be the kind of overstatement this
            // figure gets shared to disprove.
            moneySavedMinor = if (coverageKnown) moneySavedMinor(spend, protectedDays) else 0L,
            currencyCode = currencyCode,
            nextMilestone = nextMilestone(daysClean),
            reachedMilestones = reachedMilestones(daysClean),
            hasProfile = profile.hasProfile,
            hasStartDate = profile.recoveryStartEpochMs != null && daysClean > 0,
            protectedDays = protectedDays,
            coverageKnown = coverageKnown,
            coveragePercent = if (coverageKnown) {
                RecoveryCoverage.coveragePercent(protectedDays, daysClean)
            } else {
                0
            },
        )
    }

    fun daysProtected(
        startEpochMs: Long?,
        nowEpochMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Int {
        if (startEpochMs == null) return 0
        val startDay = Instant.ofEpochMilli(startEpochMs).atZone(zoneId).toLocalDate()
        val nowDay = Instant.ofEpochMilli(nowEpochMs).atZone(zoneId).toLocalDate()
        if (nowDay.isBefore(startDay)) return 0
        val days = ChronoUnit.DAYS.between(startDay, nowDay).toInt()
        return (days + 1).coerceIn(0, MAX_TRACKED_DAYS)
    }

    fun moneySavedMinor(weeklySpendMinor: Long, daysClean: Int): Long {
        if (weeklySpendMinor <= 0L || daysClean <= 0) return 0L
        val spend = FinancialProfile.sanitize(weeklySpendMinor)
        val days = daysClean.coerceIn(0, MAX_TRACKED_DAYS)
        val exact = spend.toDouble() * days.toDouble() / DAYS_PER_WEEK
        if (exact >= MAX_SAVED_MINOR) return MAX_SAVED_MINOR
        return exact.toLong()
    }

    fun nextMilestone(daysClean: Int): RecoveryMilestone? =
        RecoveryMilestone.ordered.firstOrNull { it.days > daysClean }

    fun reachedMilestones(daysClean: Int): List<RecoveryMilestone> =
        RecoveryMilestone.ordered.filter { it.days <= daysClean }

    fun formatMoney(minor: Long, currency: RecoveryCurrency): String {
        // Pinned to a fixed locale so the number never picks up the device's
        // digit grouping or decimal separator.
        val formatter = java.text.NumberFormat.getNumberInstance(java.util.Locale.US).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }
        val amount = java.math.BigDecimal(minor).movePointLeft(2)
        return "${currency.symbol}${formatter.format(amount)}"
    }

    private const val DAYS_PER_WEEK = 7.0
    private const val MAX_SAVED_MINOR = Long.MAX_VALUE / 4
}
