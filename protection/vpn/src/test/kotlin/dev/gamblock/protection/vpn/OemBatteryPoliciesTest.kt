package dev.gamblock.protection.vpn

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The OEM mapping is pure data that cannot be exercised on a test device, so it is pinned here.
 *
 * Most of these assertions exist to catch a specific real-world failure: a manufacturer string
 * that stops matching and silently downgrades the user to a generic settings screen, or a
 * component list that ends up empty so the "open battery settings" button has nothing to open.
 */
class OemBatteryPoliciesTest {

    // ---------------------------------------------------------- vendor detection

    @Test
    fun `xiaomi family is recognised across its sub-brands`() {
        for (m in listOf("Xiaomi", "Redmi", "POCO", "xiaomi", "  Redmi  ")) {
            assertThat(OemBatteryPolicies.vendorFor(m)).isEqualTo(OemBatteryPolicies.Vendor.XIAOMI)
        }
    }

    @Test
    fun `huawei and honor are recognised`() {
        assertThat(OemBatteryPolicies.vendorFor("HUAWEI")).isEqualTo(OemBatteryPolicies.Vendor.HUAWEI)
        assertThat(OemBatteryPolicies.vendorFor("HONOR")).isEqualTo(OemBatteryPolicies.Vendor.HUAWEI)
    }

    @Test
    fun `oppo and realme share coloros`() {
        assertThat(OemBatteryPolicies.vendorFor("OPPO")).isEqualTo(OemBatteryPolicies.Vendor.OPPO)
        assertThat(OemBatteryPolicies.vendorFor("realme")).isEqualTo(OemBatteryPolicies.Vendor.OPPO)
    }

    @Test
    fun `vivo and iqoo are recognised`() {
        assertThat(OemBatteryPolicies.vendorFor("vivo")).isEqualTo(OemBatteryPolicies.Vendor.VIVO)
        assertThat(OemBatteryPolicies.vendorFor("iQOO")).isEqualTo(OemBatteryPolicies.Vendor.VIVO)
    }

    @Test
    fun `samsung one ui is recognised`() {
        assertThat(OemBatteryPolicies.vendorFor("samsung")).isEqualTo(OemBatteryPolicies.Vendor.SAMSUNG)
    }

    @Test
    fun `asus variants are recognised`() {
        for (m in listOf("asus", "ASUS", "ROG", "Zenfone")) {
            assertThat(OemBatteryPolicies.vendorFor(m)).isEqualTo(OemBatteryPolicies.Vendor.ASUS)
        }
    }

    @Test
    fun `lenovo and motorola are recognised`() {
        assertThat(OemBatteryPolicies.vendorFor("LENOVO")).isEqualTo(OemBatteryPolicies.Vendor.LENOVO)
        assertThat(OemBatteryPolicies.vendorFor("motorola")).isEqualTo(OemBatteryPolicies.Vendor.LENOVO)
    }

    @Test
    fun `stock android is recognised`() {
        for (m in listOf("google", "AOSP", "unknown", "Unknown")) {
            assertThat(OemBatteryPolicies.vendorFor(m)).isEqualTo(OemBatteryPolicies.Vendor.STOCK)
        }
    }

    @Test
    fun `a null manufacturer does not throw`() {
        assertThat(OemBatteryPolicies.vendorFor(null)).isEqualTo(OemBatteryPolicies.Vendor.UNKNOWN)
    }

    @Test
    fun `a blank manufacturer does not throw`() {
        assertThat(OemBatteryPolicies.vendorFor("")).isEqualTo(OemBatteryPolicies.Vendor.UNKNOWN)
        assertThat(OemBatteryPolicies.vendorFor("   ")).isEqualTo(OemBatteryPolicies.Vendor.UNKNOWN)
    }

    @Test
    fun `an unrecognised skin is not assumed to be stock`() {
        // A no-name skin is far likelier to be aggressive than to be plain AOSP, so it must get
        // the manual guidance rather than a reassuring "nothing to do here".
        assertThat(OemBatteryPolicies.vendorFor("SomeVendor")).isEqualTo(OemBatteryPolicies.Vendor.UNKNOWN)
    }

    @Test
    fun `manufacturer matching ignores case and surrounding whitespace`() {
        assertThat(OemBatteryPolicies.vendorFor("  XIAOMI  ")).isEqualTo(OemBatteryPolicies.Vendor.XIAOMI)
        assertThat(OemBatteryPolicies.vendorFor("\tSAMSUNG\n")).isEqualTo(OemBatteryPolicies.Vendor.SAMSUNG)
    }

    // ------------------------------------------------------------ component tables

    @Test
    fun `every aggressive vendor has at least one deep link candidate`() {
        val needing = listOf(
            OemBatteryPolicies.Vendor.XIAOMI,
            OemBatteryPolicies.Vendor.HUAWEI,
            OemBatteryPolicies.Vendor.OPPO,
            OemBatteryPolicies.Vendor.VIVO,
            OemBatteryPolicies.Vendor.ONEPLUS,
            OemBatteryPolicies.Vendor.SAMSUNG,
        )
        for (vendor in needing) {
            assertThat(OemBatteryPolicies.dedicatedComponentsFor(vendor)).isNotEmpty()
        }
    }

    @Test
    fun `stock android has no deep link candidates`() {
        // Stock resolves the generic action, so offering OEM components here would only be noise.
        assertThat(OemBatteryPolicies.dedicatedComponentsFor(OemBatteryPolicies.Vendor.STOCK)).isEmpty()
    }

    @Test
    fun `an unknown vendor has no deep link candidates`() {
        assertThat(OemBatteryPolicies.dedicatedComponentsFor(OemBatteryPolicies.Vendor.UNKNOWN)).isEmpty()
    }

    @Test
    fun `every component is a fully qualified class name`() {
        val all = OemBatteryPolicies.Vendor.entries
            .flatMap { OemBatteryPolicies.dedicatedComponentsFor(it) }
        for (component in all) {
            assertThat(component).contains(".")
            assertThat(component.substringAfterLast(".")).isNotEmpty()
            assertThat(component).doesNotContain(" ")
        }
    }

    @Test
    fun `no vendor lists the same component twice`() {
        for (vendor in OemBatteryPolicies.Vendor.entries) {
            val components = OemBatteryPolicies.dedicatedComponentsFor(vendor)
            assertThat(components).containsNoDuplicates()
        }
    }

    @Test
    fun `xiaomi lists the autostart screen first because that is the one that matters`() {
        val first = OemBatteryPolicies.dedicatedComponentsFor(OemBatteryPolicies.Vendor.XIAOMI).first()
        assertThat(first).contains("AutoStart")
    }

    // ------------------------------------------------------------- manual guidance

    @Test
    fun `vendors that need a manual auto-start step are flagged`() {
        val needing = OemBatteryPolicies.Vendor.entries
            .filter { it.needsManualAutostartStep }
            .map { it.name }
        assertThat(needing).containsAtLeast(
            "XIAOMI",
            "HUAWEI",
            "OPPO",
            "VIVO",
            "ONEPLUS",
            "SAMSUNG",
        )
    }

    @Test
    fun `stock android needs no manual step`() {
        assertThat(OemBatteryPolicies.Vendor.STOCK.needsManualAutostartStep).isFalse()
    }

    @Test
    fun `every flagged vendor has a human readable label`() {
        for (vendor in OemBatteryPolicies.Vendor.entries) {
            assertThat(OemBatteryPolicies.displayLabel(vendor)).isNotEmpty()
        }
    }

    @Test
    fun `labels are distinct enough to tell the user where they are`() {
        val labels = OemBatteryPolicies.Vendor.entries.map { OemBatteryPolicies.displayLabel(it) }
        assertThat(labels).containsNoDuplicates()
    }

    // ----------------------------------------------------------------- constants

    @Test
    fun `the direct request action is the documented platform string`() {
        assertThat(OemBatteryPolicies.ACTION_REQUEST_IGNORE_OPTIMIZATIONS)
            .isEqualTo("android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")
    }

    @Test
    fun `the fallback list action is the documented platform string`() {
        assertThat(OemBatteryPolicies.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .isEqualTo("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS")
    }

    @Test
    fun `the exemption helper reports the value it is given`() {
        assertThat(OemBatteryPolicies.isExempt(true)).isTrue()
        assertThat(OemBatteryPolicies.isExempt(false)).isFalse()
    }

    @Test
    fun `unknown vendor label does not promise anything`() {
        // "this device" rather than "Android": we do not actually know what it is.
        assertThat(OemBatteryPolicies.displayLabel(OemBatteryPolicies.Vendor.UNKNOWN)).isEqualTo("this device")
    }
}