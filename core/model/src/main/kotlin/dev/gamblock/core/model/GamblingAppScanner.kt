package dev.gamblock.core.model

/**
 * Offline heuristic scanner that flags installed apps as *possibly* gambling
 * related.
 *
 * Read this header before changing the scoring. There are three things this
 * scanner deliberately refuses to do, and each one was a real design decision
 * rather than an omission.
 *
 * 1. It never takes automatic action. It does not block, uninstall, or exempt
 *    anything. A false positive here would either remove a working app from a
 *    child's device or interrupt a legitimate app, and no label heuristic is
 *    accurate enough to justify that. The output is advice for a human, which
 *    is why [GamblingScanVerdict] carries its [GamblingEvidence] rather than a
 *    bare boolean.
 *
 * 2. It never uses substring matching on words. "better", "betray" and
 *    "Roubaix" contain "bet" and "rou"; a naive `contains` check flags
 *    calculators, file managers and French place names. Package names are
 *    matched per token (see [packageTokens]) and labels are matched on word
 *    boundaries (see [labelPatternFor]).
 *
 * 3. It never looks anything up. No network, no telemetry, no reputation
 *    service. Every judgement below is a static table shipped in the APK, so a
 *    scan cannot leak the list of installed apps and cannot be wrong because a
 *    server was down.
 *
 * The unavoidable consequence is that it is both incomplete and imperfect: real
 * gambling apps using an unremarkable package name are missed, and a genuine
 * "Poker" strategy tutor is flagged. It is reported as a risk level with
 * evidence, never as a fact. This is the same honesty discipline the bypass
 * matrix uses.
 */
object GamblingAppScanner {

    /** Weight for a package token that names a real, widely-known operator. */
    private const val SCORE_KNOWN_OPERATOR = 70

    /**
     * Weight for a package token containing an unambiguous gambling word.
     *
     * Above [REPORT_THRESHOLD] on its own, because a package name is a stronger
     * signal than a display label: users rename labels, not package names.
     */
    private const val SCORE_PACKAGE_WORD = 45

    /** Weight per gambling phrase found in the visible app label. */
    private const val SCORE_LABEL_PHRASE = 30

    /**
     * Below this, an app is not worth showing the user.
     *
     * A single label phrase scores 30 and so does *not* reach it: one ambiguous
     * word in a display name is not enough to interrupt someone with a warning.
     * Two phrases, or one phrase plus a package signal, do.
     */
    private const val REPORT_THRESHOLD = 40

    /**
     * Package tokens that identify a real gambling operator. Matched as whole
     * tokens, so this catches `com.bet365.bet365` and `com.draftkings.android`
     * without matching `com.betterhealth.tracker`.
     *
     * Names get retired, forked and regionalised, so this table is a starting
     * point that is expected to go stale. A miss is survivable here because the
     * scanner only advises.
     */
    private val KNOWN_OPERATOR_TOKENS: Set<String> = setOf(
        // Sportsbook / betting exchange.
        "draftkings", "fanduel", "bet365", "betfair", "betway", "bwin",
        "williamhill", "unibet", "betvictor", "bovada", "sbobet", "pinnacle",
        "smarkets", "cloudbet", "betwinner", "casumo", "pokerstars", "partypoker",
        "888casino", "casinojoy", "leovegas", "mrgreen", "betsafe",
        "betfred", "tote", "ladbrokes", "coral", "skybet", "matchbook",
        "1xbet", "parimatch", "bovada.lv", "intercasino",
        // Prediction markets and social-casino style play.
        "polymarket", "chumba", "globalpoker",
        // Aggregator front-ends for the above.
        "oddschecker", "oddsportal", "betexplorer", "sportsbettingdime",
    )

    /**
     * Gambling words that are strong enough to mean something on their own.
     *
     * Words that were considered and deliberately left out:
     *  - "lottery": a state lottery, a charity draw and a random-number app all
     *    match, and in several markets the word is a regulated product Shield
     *    must not mislabel.
     *  - "spin", "coin", "win", "roll", "wheel", "odds": all appear in
     *    ordinary app labels far more often than in gambling apps.
     *  - "poker" and "roulette" are kept but scored as a label phrase only, so
     *    a strategy tutor lands at MEDIUM with visible evidence rather than
     *    HIGH.
     */
    private val LABEL_PHRASES: List<String> = listOf(
        "casino", "sportsbook", "betting", "bookmaker", "bet365", "fanduel",
        "draftkings", "poker", "roulette", "blackjack", "jackpot", "slots",
        "real money", "real-money", "free bet", "free bets", "odds boost",
        "matched betting", "parimatch", "1xbet", "betfair", "bet now",
    )

    enum class Risk(val displayName: String) {
        NONE("No signal"),
        POSSIBLE("Possible"),
        LIKELY("Likely"),
        VERY_LIKELY("Very likely"),
    }

    /** Why an app was flagged. Always shown to the user, never hidden. */
    sealed interface Evidence {
        val detail: String

        /** A package token that names a real operator. */
        data class KnownOperator(override val detail: String) : Evidence

        /** A package token containing a gambling word. */
        data class PackageWord(override val detail: String) : Evidence

        /** A gambling phrase in the visible label. */
        data class LabelPhrase(override val detail: String) : Evidence
    }

    data class Verdict(
        val packageName: String,
        val label: String,
        val risk: Risk,
        val evidence: List<Evidence>,
    )

    data class Report(
        val scannedCount: Int,
        val verdicts: List<Verdict>,
        /** True when Shield was excluded from its own results. */
        val selfExcluded: Boolean,
        /**
         * Always true on Android 11+. The manifest declares `<queries>` rather
         * than `QUERY_ALL_PACKAGES`, so [scannedCount] is a subset of what is
         * installed and a clean report is not a clean device.
         */
        val partialCoverage: Boolean = true,
    )

    /**
     * Scans [candidates] and returns only the apps worth showing.
     *
     * [selfPackage] is never returned, so Shield cannot flag itself. Results are
     * sorted most-concerned first so the user sees the apps that matter at the
     * top of a list rather than buried alphabetically.
     */
    fun scan(
        candidates: List<InstalledAppCandidate>,
        selfPackage: String? = null,
    ): Report {
        val self = selfPackage?.trim()
        val eligible = candidates.filter { it.packageName != self }

        val verdicts = eligible.mapNotNull { app ->
            val evidence = mutableListOf<Evidence>()
            var score = 0

            for (token in packageTokens(app.packageName)) {
                if (token in KNOWN_OPERATOR_TOKENS) {
                    evidence += Evidence.KnownOperator(token)
                    score += SCORE_KNOWN_OPERATOR
                    break
                }
            }
            if (evidence.none { it is Evidence.KnownOperator }) {
                packageWord(app.packageName)?.let { word ->
                    evidence += Evidence.PackageWord(word)
                    score += SCORE_PACKAGE_WORD
                }
            }

            val phrases = LABEL_PHRASES.filter { phrase ->
                labelPatternFor(phrase).containsMatchIn(app.label)
            }
            for (phrase in phrases) {
                evidence += Evidence.LabelPhrase(phrase)
                score += SCORE_LABEL_PHRASE
            }

            if (score < REPORT_THRESHOLD) return@mapNotNull null
            Verdict(
                packageName = app.packageName,
                label = app.label,
                risk = riskFor(score),
                evidence = evidence.distinctBy { it.detail.lowercase() },
            )
        }

        return Report(
            scannedCount = eligible.size,
            verdicts = verdicts.sortedWith(
                compareByDescending<Verdict> { it.risk.ordinal }
                    .thenByDescending { it.evidence.size }
                    .thenBy { it.label.lowercase() },
            ),
            selfExcluded = self != null && candidates.any { it.packageName == self },
        )
    }

    private fun riskFor(score: Int): Risk = when {
        score >= SCORE_KNOWN_OPERATOR + SCORE_LABEL_PHRASE -> Risk.VERY_LIKELY
        score >= SCORE_KNOWN_OPERATOR -> Risk.LIKELY
        score >= SCORE_LABEL_PHRASE + SCORE_LABEL_PHRASE -> Risk.LIKELY
        else -> Risk.POSSIBLE
    }

    /**
     * Splits a package name into comparable tokens.
     *
     * Splits on `.`, `_` and `-` only. It deliberately does *not* split
     * camelCase or digits, because `bet365` and `draftkings` are single tokens
     * that must match as a whole, and splitting them apart would reduce every
     * operator to a meaningless "bet" fragment.
     */
    private fun packageTokens(packageName: String): List<String> =
        packageName.lowercase()
            .split('.', '_', '-')
            .filter { it.isNotBlank() }

    /**
     * A gambling word found in a package name, matched per token.
     *
     * Only unambiguous words are used, and each is long enough that it cannot
     * plausibly be a substring of ordinary English. "poker" and "roulette" are
     * deliberately absent: they are common in the *labels* of hobby apps
     * ("Poker Trainer", "Roulette Odds"), and counting them here turned a
     * strategy tutor into a LIKELY result, because the package token and the
     * label were the same underlying word counted twice. See [scan].
     */
    private fun packageWord(packageName: String): String? =
        packageTokens(packageName).firstOrNull { token ->
            token.length >= 5 && PACKAGE_WORDS.any { token.contains(it) }
        }

    private val PACKAGE_WORDS = listOf("casino", "jackpot", "betting", "sportsbook")

    /**
     * Builds a whole-word matcher for a label phrase.
     *
     * `\b` is what stops "better" matching "bet". A phrase containing a space
     * cannot use `\b` reliably, so those are matched as plain substrings
     * instead - the phrases with spaces ("real money", "free bet") are
     * distinctive enough that this is safe.
     */
    private fun labelPatternFor(phrase: String): Regex =
        if (phrase.contains(' ')) {
            Regex(Regex.escape(phrase), RegexOption.IGNORE_CASE)
        } else {
            Regex("\\b" + Regex.escape(phrase) + "\\b", RegexOption.IGNORE_CASE)
        }
}
