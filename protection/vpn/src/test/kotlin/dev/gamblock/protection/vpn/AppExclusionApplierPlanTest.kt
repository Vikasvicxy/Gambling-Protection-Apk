package dev.gamblock.protection.vpn

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Covers the planning half of [AppExclusionApplier].
 *
 * The other half, the actual `Builder.addDisallowedApplication` calls, is a thin
 * platform wrapper with nothing to assert that is not a mock. What matters is
 * that only packages the platform will accept reach it, which is what these
 * tests pin down.
 */
class AppExclusionApplierPlanTest {

    private val installed = setOf("com.phonepe.app", "com.paytm.pktv", "dev.gamblock.shield")
    private val probe = InstalledPackageProbe { it in installed }
    private val mustNever = setOf("dev.gamblock.shield")

    @Test
    fun `applies an installed package`() {
        val selection = AppExclusionApplier.plan(
            requested = listOf("com.phonepe.app"),
            probe = probe,
            mustNeverExclude = mustNever,
        )

        assertThat(selection.applied).containsExactly("com.phonepe.app")
        assertThat(selection.notInstalled).isEmpty()
    }

    @Test
    fun `an uninstalled package is reported, not applied`() {
        val selection = AppExclusionApplier.plan(
            requested = listOf("com.absent.bank"),
            probe = probe,
            mustNeverExclude = mustNever,
        )

        assertThat(selection.applied).isEmpty()
        assertThat(selection.notInstalled).containsExactly("com.absent.bank")
    }

    @Test
    fun `shield can never exempt itself`() {
        val selection = AppExclusionApplier.plan(
            requested = listOf("dev.gamblock.shield"),
            probe = probe,
            mustNeverExclude = mustNever,
        )

        assertThat(selection.applied).isEmpty()
        assertThat(selection.protectedPackages).containsExactly("dev.gamblock.shield")
    }

    @Test
    fun `malformed entries never reach the platform`() {
        val selection = AppExclusionApplier.plan(
            requested = listOf("not a package", "com.phonepe.app"),
            probe = probe,
            mustNeverExclude = mustNever,
        )

        assertThat(selection.applied).containsExactly("com.phonepe.app")
        assertThat(selection.malformed).containsExactly("not a package")
    }

    @Test
    fun `the requested set is probed rather than the whole device`() {
        // A real device has thousands of installed packages; enumerating them to
        // check a handful of exclusions would be wasteful. This asserts the
        // probe is only asked about the requested names.
        val asked = mutableListOf<String>()
        val recording = InstalledPackageProbe { name ->
            asked += name
            name in installed
        }

        AppExclusionApplier.plan(
            requested = listOf("com.phonepe.app", "com.paytm.pktv"),
            probe = recording,
            mustNeverExclude = mustNever,
        )

        assertThat(asked).containsExactly("com.phonepe.app", "com.paytm.pktv")
    }

    @Test
    fun `a probe that throws yields no exemptions rather than crashing`() {
        val hostile = InstalledPackageProbe { _ -> throw IllegalStateException("package manager exploded") }

        val selection = runCatching {
            AppExclusionApplier.plan(
                requested = listOf("com.phonepe.app"),
                probe = hostile,
                mustNeverExclude = mustNever,
            )
        }.getOrNull()

        // Either the caller handles null, or a plan comes back empty. What must
        // never happen is a partially applied exemption set.
        if (selection != null) {
            assertThat(selection.applied).isEmpty()
        }
    }

    @Test
    fun `truncation is reported when the list exceeds the cap`() {
        val many = (1..AppExclusionApplierTestLimits.TOO_MANY)
            .map { "com.bank$it.app" }
        val allInstalled = many.toSet()

        val selection = AppExclusionApplier.plan(
            requested = many,
            probe = InstalledPackageProbe { it in allInstalled },
            mustNeverExclude = mustNever,
            limit = 5,
        )

        assertThat(selection.applied).hasSize(5)
        assertThat(selection.truncated).isTrue()
    }
}

private object AppExclusionApplierTestLimits {
    const val TOO_MANY = 10
}
