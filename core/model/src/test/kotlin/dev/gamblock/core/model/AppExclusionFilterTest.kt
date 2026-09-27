package dev.gamblock.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppExclusionFilterTest {

    @Test
    fun `valid package names are recognised`() {
        listOf(
            "com.phonepe.app",
            "in.org.npci.upiapp",
            "com.Slack",
            "com.kotak.KotakMobileBanking",
            "a.b",
        ).forEach {
            assertThat(AppExclusionFilter.isValidPackageName(it)).isTrue()
        }
    }

    @Test
    fun `malformed package names are rejected`() {
        listOf(
            "",
            "   ",
            "phonepe",              // no dot: not a package
            "com.phonepe.",          // trailing dot leaves an empty segment
            ".com.phonepe",         // leading dot
            "com..phonepe",         // empty segment
            "com.1phonepe",         // segment starts with a digit
            "com.phone-pe",         // hyphen is not legal
            "com.phonepe.app ",     // trailing space
            "com.phonepe app",      // embedded space
            "com..",                // dangling empty segment
            "1com.phonepe",         // leading digit
        ).forEach {
            assertThat(AppExclusionFilter.isValidPackageName(it)).isFalse()
        }
    }

    @Test
    fun `over-long package names are rejected`() {
        val long = "com." + "a".repeat(300)
        assertThat(AppExclusionFilter.isValidPackageName(long)).isFalse()
    }

    @Test
    fun `select keeps only requested packages that are installed`() {
        val selection = AppExclusionFilter.select(
            requested = listOf("com.phonepe.app", "com.absent.app"),
            installed = setOf("com.phonepe.app"),
        )

        assertThat(selection.applied).containsExactly("com.phonepe.app")
        assertThat(selection.notInstalled).containsExactly("com.absent.app")
        assertThat(selection.isEmpty).isFalse()
    }

    @Test
    fun `select refuses to exempt a protected package even when installed`() {
        val selection = AppExclusionFilter.select(
            requested = listOf("dev.gamblock.shield", "com.phonepe.app"),
            installed = setOf("dev.gamblock.shield", "com.phonepe.app"),
            mustNeverExclude = setOf("dev.gamblock.shield"),
        )

        assertThat(selection.applied).containsExactly("com.phonepe.app")
        assertThat(selection.protectedPackages).containsExactly("dev.gamblock.shield")
    }

    @Test
    fun `select reports malformed entries instead of silently dropping them`() {
        val selection = AppExclusionFilter.select(
            requested = listOf("not-a-package", "com.phonepe.app"),
            installed = setOf("com.phonepe.app"),
        )

        assertThat(selection.applied).containsExactly("com.phonepe.app")
        assertThat(selection.malformed).containsExactly("not-a-package")
        assertThat(selection.rejectedCount).isEqualTo(1)
    }

    @Test
    fun `select trims whitespace before validating`() {
        val selection = AppExclusionFilter.select(
            requested = listOf("  com.phonepe.app  "),
            installed = setOf("com.phonepe.app"),
        )

        assertThat(selection.applied).containsExactly("com.phonepe.app")
    }

    @Test
    fun `select deduplicates and returns a stable order`() {
        val selection = AppExclusionFilter.select(
            requested = listOf(
                "com.zzz.bank",
                "com.aaa.bank",
                "com.zzz.bank",
                "com.aaa.bank",
            ),
            installed = setOf("com.zzz.bank", "com.aaa.bank"),
        )

        assertThat(selection.applied).containsExactly("com.aaa.bank", "com.zzz.bank").inOrder()
    }

    @Test
    fun `select truncates past the limit and says so`() {
        val requested = (1..10).map { "com.bank$it.app" }
        val installed = requested.toSet()

        val selection = AppExclusionFilter.select(requested, installed, limit = 4)

        assertThat(selection.applied).hasSize(4)
        assertThat(selection.truncated).isTrue()
    }

    @Test
    fun `select does not truncate exactly at the limit`() {
        val requested = (1..3).map { "com.bank$it.app" }
        val selection = AppExclusionFilter.select(requested, requested.toSet(), limit = 3)

        assertThat(selection.applied).hasSize(3)
        assertThat(selection.truncated).isFalse()
    }

    @Test
    fun `select on an empty request yields an empty selection`() {
        val selection = AppExclusionFilter.select(emptyList(), emptySet())

        assertThat(selection.applied).isEmpty()
        assertThat(selection.isEmpty).isTrue()
        assertThat(selection.rejectedCount).isEqualTo(0)
        assertThat(selection.truncated).isFalse()
    }
}

class ExcludedAppsCatalogTest {

    @Test
    fun `catalog is populated`() {
        assertThat(ExcludedAppsCatalog.SUGGESTIONS).isNotEmpty()
    }

    @Test
    fun `every catalog entry is a valid package name`() {
        ExcludedAppsCatalog.SUGGESTIONS.forEach {
            assertThat(AppExclusionFilter.isValidPackageName(it.packageName)).isTrue()
        }
    }

    @Test
    fun `catalog has no duplicate package names`() {
        val packages = ExcludedAppsCatalog.SUGGESTIONS.map { it.packageName }
        assertThat(packages).containsNoDuplicates()
    }

    @Test
    fun `catalog covers the expected payment and banking apps`() {
        val packages = ExcludedAppsCatalog.SUGGESTIONS.map { it.packageName }
        assertThat(packages).containsAtLeast(
            "com.google.android.apps.nbu.paisa.user",
            "com.phonepe.app",
            "in.org.npci.upiapp",
        )
    }

    @Test
    fun `suggestionsFor filters by category`() {
        val upi = ExcludedAppsCatalog.suggestionsFor(ExcludedAppCategory.PAYMENTS)
        assertThat(upi).isNotEmpty()
        assertThat(upi.map { it.packageName }).contains("com.phonepe.app")
        assertThat(upi.none { it.category != ExcludedAppCategory.PAYMENTS }).isTrue()
    }

    @Test
    fun `manifest queries list and catalog agree`() {
        // The manifest can only see what it declares. If these two drift, the
        // picker silently stops resolving the missing packages.
        val declared = setOf(
            "com.google.android.apps.nbu.paisa.user",
            "com.phonepe.app",
            "in.org.npci.upiapp",
            "com.paytm.pktv",
            "com.microsoft.teams",
            "com.Slack",
        )
        val catalog = ExcludedAppsCatalog.SUGGESTIONS.map { it.packageName }.toSet()
        assertThat(catalog).containsAtLeastElementsIn(declared)
    }
}

class InstalledAppFilterTest {

    private val phonepe = InstalledAppCandidate("com.phonepe.app", "PhonePe")
    private val paytm = InstalledAppCandidate("com.paytm.pktv", "Paytm")
    private val teams = InstalledAppCandidate("com.microsoft.teams", "Microsoft Teams")
    private val systemThing = InstalledAppCandidate("com.android.settings", "Settings", isSystemApp = true)

    @Test
    fun `search matches on label regardless of case`() {
        val result = InstalledAppFilter.search(listOf(phonepe, paytm, teams), "paytm")
        assertThat(result).containsExactly(paytm)
    }

    @Test
    fun `search matches on package name`() {
        val result = InstalledAppFilter.search(listOf(phonepe, paytm), "phonepe")
        assertThat(result).containsExactly(phonepe)
    }

    @Test
    fun `search with a blank query returns everything sorted`() {
        val result = InstalledAppFilter.search(listOf(teams, phonepe, paytm), "   ")
        assertThat(result).hasSize(3)
    }

    @Test
    fun `search that matches nothing returns empty`() {
        val result = InstalledAppFilter.search(listOf(phonepe, paytm), "zzz-no-such-app")
        assertThat(result).isEmpty()
    }

    @Test
    fun `catalog apps sort ahead of unknown apps`() {
        val unknown = InstalledAppCandidate("com.random.thing", "Aardvark")
        val result = InstalledAppFilter.sortForPicker(listOf(unknown, phonepe))
        assertThat(result.first()).isEqualTo(phonepe)
    }

    @Test
    fun `userFacing hides system apps when not searching`() {
        val result = InstalledAppFilter.userFacing(
            listOf(phonepe, systemThing),
            query = "",
        )
        assertThat(result).containsExactly(phonepe)
    }

    @Test
    fun `userFacing reveals a system app once the user searches for it`() {
        val result = InstalledAppFilter.userFacing(
            listOf(phonepe, systemThing),
            query = "settings",
        )
        assertThat(result).containsExactly(systemThing)
    }
}
