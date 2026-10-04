package dev.gamblock.protection.oem

import com.google.common.truth.Truth.assertThat
import dev.gamblock.core.model.OemKind
import org.junit.Test

class OemAutostartResolverTest {

    private fun neverProbed(): (OemAutostartTarget) -> Boolean = { false }

    @Test
    fun `returns null when already exempt without probing any candidate`() {
        var probed = false
        val result = OemAutostartResolver.select(OemKind.XIAOMI, isExempt = true) { probed = true; true }

        assertThat(result).isNull()
        assertThat(probed).isFalse()
    }

    @Test
    fun `returns null for Pixel which has no vendor screen`() {
        assertThat(OemAutostartResolver.select(OemKind.PIXEL, isExempt = false, probe = { true })).isNull()
    }

    @Test
    fun `returns null for Fairphone which has no vendor screen`() {
        assertThat(OemAutostartResolver.select(OemKind.FAIRPHONE, isExempt = false, probe = { true })).isNull()
    }

    @Test
    fun `returns null for emulator which has no vendor screen`() {
        assertThat(OemAutostartResolver.select(OemKind.EMULATOR, isExempt = false, probe = { true })).isNull()
    }

    @Test
    fun `returns null when no candidate resolves`() {
        assertThat(OemAutostartResolver.select(OemKind.VIVO, isExempt = false, probe = neverProbed())).isNull()
    }

    @Test
    fun `picks Xiaomi autostart activity`() {
        val target = OemAutostartResolver.select(OemKind.XIAOMI, isExempt = false, probe = { true })

        assertThat(target?.packageName).isEqualTo("com.miui.securitycenter")
        assertThat(target?.className)
            .isEqualTo("com.miui.permcenter.autostart.AutoStartManagementActivity")
    }

    @Test
    fun `Redmi shares the Xiaomi autostart activity`() {
        val target = OemAutostartResolver.select(OemKind.REDMI, isExempt = false, probe = { true })

        assertThat(target?.packageName).isEqualTo("com.miui.securitycenter")
    }

    @Test
    fun `Poco shares the Xiaomi autostart activity`() {
        val target = OemAutostartResolver.select(OemKind.POCO, isExempt = false, probe = { true })

        assertThat(target?.packageName).isEqualTo("com.miui.securitycenter")
    }

    @Test
    fun `falls through to the second candidate when the first does not resolve`() {
        val vivo = OemAutostartLinks.candidates(OemKind.VIVO)
        val result = OemAutostartResolver.select(OemKind.VIVO, isExempt = false) { it == vivo[1] }

        assertThat(result).isEqualTo(vivo[1])
    }

    @Test
    fun `prefers the first candidate when both resolve`() {
        val oppo = OemAutostartLinks.candidates(OemKind.OPPO)
        val result = OemAutostartResolver.select(OemKind.OPPO, isExempt = false, probe = { true })

        assertThat(result).isEqualTo(oppo.first())
    }

    @Test
    fun `oppo battery intent precedes the coloros variant`() {
        val oppo = OemAutostartLinks.candidates(OemKind.OPPO)

        assertThat(oppo.first().packageName).isEqualTo("com.oplus.battery")
        assertThat(oppo.map { it.packageName })
            .containsExactly("com.oplus.battery", "com.coloros.oppoguardelf").inOrder()
    }

    @Test
    fun `oneplus reuses the oppo candidates`() {
        assertThat(OemAutostartLinks.candidates(OemKind.ONEPLUS))
            .isEqualTo(OemAutostartLinks.candidates(OemKind.OPPO))
    }

    @Test
    fun `realme reuses the oppo candidates`() {
        assertThat(OemAutostartLinks.candidates(OemKind.REALME))
            .isEqualTo(OemAutostartLinks.candidates(OemKind.OPPO))
    }

    @Test
    fun `huawei offers app launch management before protected apps`() {
        val huawei = OemAutostartLinks.candidates(OemKind.HUAWEI)

        assertThat(huawei).hasSize(2)
        assertThat(huawei[0].className).contains("StartupNormalAppListActivity")
        assertThat(huawei[1].className).contains("ProtectActivity")
    }

    @Test
    fun `honor reuses the huawei candidates`() {
        assertThat(OemAutostartLinks.candidates(OemKind.HONOR))
            .isEqualTo(OemAutostartLinks.candidates(OemKind.HUAWEI))
    }

    @Test
    fun `samsung exposes a single battery usage target`() {
        assertThat(OemAutostartLinks.candidates(OemKind.SAMSUNG)).hasSize(1)
    }

    @Test
    fun `motorola targets its own launcher settings`() {
        val target = OemAutostartResolver.select(OemKind.MOTOROLA, isExempt = false, probe = { true })

        assertThat(target?.packageName).isEqualTo("com.motorola.launcher3")
    }

    @Test
    fun `nothing targets the sysui battery settings`() {
        val target = OemAutostartResolver.select(OemKind.NOTHING, isExempt = false, probe = { true })

        assertThat(target?.packageName).isEqualTo("com.nothing.sysui")
    }

    @Test
    fun `unrecognised oem has no candidates`() {
        assertThat(OemAutostartLinks.candidates(OemKind.OTHER)).isEmpty()
    }

    @Test
    fun `every candidate is fully qualified`() {
        val all = OemKind.entries.flatMap { OemAutostartLinks.candidates(it) }

        assertThat(all).isNotEmpty()
        all.forEach { target ->
            assertThat(target.packageName).contains(".")
            // Vendor autostart activities often live in a sub-package of the vendor
            // manager package (MIUI permcenter inside securitycenter), so require a
            // fully qualified name rather than a shared prefix with the package.
            assertThat(target.className).contains(".")
            assertThat(target.className).isNotEqualTo(target.packageName)
            assertThat(target.label).isNotEmpty()
        }
    }

    @Test
    fun `candidates are unique per oem`() {
        OemKind.entries.forEach { kind ->
            val targets = OemAutostartLinks.candidates(kind)
            assertThat(targets).containsNoDuplicates()
        }
    }

    @Test
    fun `every aggressive oem exposes at least one candidate`() {
        val aggressive = listOf(
            OemKind.XIAOMI, OemKind.REDMI, OemKind.POCO,
            OemKind.OPPO, OemKind.REALME, OemKind.ONEPLUS,
            OemKind.VIVO, OemKind.HUAWEI, OemKind.HONOR,
        )

        aggressive.forEach { kind ->
            assertThat(OemAutostartLinks.candidates(kind)).isNotEmpty()
        }
    }
}