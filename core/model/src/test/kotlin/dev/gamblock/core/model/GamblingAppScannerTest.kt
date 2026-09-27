package dev.gamblock.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The tests here are weighted towards false positives, not coverage.
 *
 * A missed gambling app is a gap the user can close by deleting an app
 * themselves. A wrongly flagged calculator is a warning about an app that does
 * nothing wrong, which destroys trust in every other warning the app ever
 * shows. So the interesting cases are the near-misses: "better", "Roubaix",
 * "Poker" as a hobby.
 */
class GamblingAppScannerTest {

    private fun app(pkg: String, label: String, system: Boolean = false) =
        InstalledAppCandidate(packageName = pkg, label = label, isSystemApp = system)

    private fun scan(vararg apps: InstalledAppCandidate, self: String? = null) =
        GamblingAppScanner.scan(apps.toList(), self)

    // --- true positives -----------------------------------------------------

    @Test
    fun `a real sportsbook is flagged very likely`() {
        val report = scan(app("com.draftkings.android", "DraftKings Sportsbook"))
        val verdict = report.verdicts.single()
        assertThat(verdict.risk).isEqualTo(GamblingAppScanner.Risk.VERY_LIKELY)
        assertThat(verdict.packageName).isEqualTo("com.draftkings.android")
    }

    @Test
    fun `a known operator is recognised from the package alone`() {
        val report = scan(app("com.bet365.bet365", "Sports"))
        assertThat(report.verdicts).hasSize(1)
        assertThat(report.verdicts.single().evidence.map { it.detail })
            .contains("bet365")
    }

    @Test
    fun `a casino label with no known package is flagged likely`() {
        val report = scan(app("com.example.app77", "Golden Casino Slots"))
        val verdict = report.verdicts.single()
        assertThat(verdict.risk).isEqualTo(GamblingAppScanner.Risk.LIKELY)
    }

    @Test
    fun `multi word label phrases are matched`() {
        val report = scan(app("com.example.play", "Real Money Poker"))
        assertThat(report.verdicts).hasSize(1)
        assertThat(report.verdicts.single().evidence.map { it.detail })
            .contains("real money")
    }

    @Test
    fun `a package token containing a gambling word counts on its own`() {
        val report = scan(app("com.megacasino.app", "Play"))
        val verdict = report.verdicts.single()
        // A package name is a stronger signal than a label, so this is reported
        // from the package token alone, at the lowest risk level.
        assertThat(verdict.risk).isEqualTo(GamblingAppScanner.Risk.POSSIBLE)
        assertThat(verdict.evidence.map { it.detail }).contains("megacasino")
    }

    // --- false positives: the whole point of the exercise -------------------

    @Test
    fun `a word merely containing bet is not flagged`() {
        // The canonical bug: `contains("bet")` flags all of these.
        listOf(
            "com.betterhealth.tracker" to "BetterHealth",
            "com.example.betray" to "Betray",
            "com.roubaix.transit" to "Roubaix Transit",
            "com.example.spincaster" to "Spincaster",
        ).forEach { (pkg, label) ->
            val report = scan(app(pkg, label))
            assertThat(report.verdicts).isEmpty()
        }
    }

    @Test
    fun `a single ambiguous label phrase stays below the report threshold`() {
        // One label phrase scores 30, under the 40 threshold: not worth a warning.
        val report = scan(app("com.example.pokertool", "Poker Odds Calculator"))
        assertThat(report.verdicts).isEmpty()
    }

    @Test
    fun `a hobby poker tutor is not flagged on one ambiguous word`() {
        // "Poker" in both the label and the package name is the *same* signal
        // twice, not corroboration, so this stays under the report threshold.
        val report = scan(app("com.example.pokertrainer", "Poker Trainer: Learn Poker"))
        assertThat(report.verdicts).isEmpty()
    }

    @Test
    fun `a second signal lifts a hobby app onto the list`() {
        val report = scan(app("com.example.pokertrainer", "Poker Trainer: Poker Betting"))
        assertThat(report.verdicts.single().risk).isEqualTo(GamblingAppScanner.Risk.LIKELY)
    }

    @Test
    fun `ordinary apps are not flagged`() {
        val report = scan(
            app("com.example.calculator", "Calculator"),
            app("com.example.filemanager", "My Files"),
            app("com.android.settings", "Settings", system = true),
            app("com.example.mail", "Mail"),
        )
        assertThat(report.verdicts).isEmpty()
    }

    @Test
    fun `a system app with a gambling word is still reported`() {
        // A pre-installed casino is the most likely way one is missed, so
        // being a system app must not hide it.
        val report = scan(app("com.oem.casino", "Casino", system = true))
        assertThat(report.verdicts).hasSize(1)
    }

    // --- self-protection and invariants -------------------------------------

    @Test
    fun `the scanner never flags Shield itself`() {
        val report = scan(
            app("dev.gamblock.shield", "Gamblock Shield"),
            app("com.draftkings.android", "DraftKings"),
            self = "dev.gamblock.shield",
        )
        assertThat(report.verdicts.map { it.packageName })
            .containsExactly("com.draftkings.android")
        assertThat(report.selfExcluded).isTrue()
    }

    @Test
    fun `self exclusion still counts the scanned total correctly`() {
        val report = scan(
            app("dev.gamblock.shield", "Gamblock Shield"),
            app("com.draftkings.android", "DraftKings"),
            self = "dev.gamblock.shield",
        )
        assertThat(report.scannedCount).isEqualTo(1)
    }

    @Test
    fun `an empty list produces an empty report, not a crash`() {
        val report = scan()
        assertThat(report.verdicts).isEmpty()
        assertThat(report.scannedCount).isEqualTo(0)
    }

    @Test
    fun `coverage is always reported as partial`() {
        // Android 11+ visibility means a clean scan never means a clean device.
        assertThat(scan(app("com.example.calculator", "Calculator")).partialCoverage).isTrue()
    }

    // --- ordering and evidence quality --------------------------------------

    @Test
    fun `the most concerned app is first`() {
        val report = scan(
            app("com.example.math", "Calculator"),
            app("com.example.tracker", "Poker Tracker"),
            app("com.bet365.app", "Bet365 Sportsbook"),
        )
        assertThat(report.verdicts.first().risk)
            .isEqualTo(GamblingAppScanner.Risk.VERY_LIKELY)
    }

    @Test
    fun `every verdict explains itself`() {
        val report = scan(app("com.draftkings.android", "DraftKings Sportsbook"))
        assertThat(report.verdicts.single().evidence).isNotEmpty()
    }

    @Test
    fun `duplicate evidence is collapsed`() {
        val report = scan(app("com.bet365.bet365", "bet365"))
        // "bet365" arrives as both a package token and a label phrase; the user
        // should see it once, not twice.
        val details = report.verdicts.single().evidence.map { it.detail.lowercase() }
        assertThat(details).containsNoDuplicates()
    }

    @Test
    fun `package splitting does not shatter an operator into fragments`() {
        // bet365 must match as one token, not as "bet".
        assertThat(scan(app("com.bet365.bet365", "Sports")).verdicts).hasSize(1)
    }

    @Test
    fun `label matching ignores case`() {
        assertThat(scan(app("com.example.x", "SLOTS CASINO")).verdicts).hasSize(1)
    }
}
