package dev.gamblock.protection.tamper

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppEnvironmentProbeTest {

    private fun probe(
        debuggable: Boolean = false,
        buildTags: String = "release-keys",
        exists: (String) -> Boolean = { false },
        xposedPackages: List<String> = emptyList(),
    ): AppEnvironmentProbe {
        val lookup = PackageLookup { packageName -> packageName in xposedPackages }
        return AppEnvironmentProbe(
            packageLookup = lookup,
            debuggableApp = debuggable,
            buildTags = buildTags,
            fileExists = exists,
        )
    }

    @Test
    fun `clean environment reports no signals`() {
        val signals = probe().current()
        assertThat(signals.any).isFalse()
    }

    @Test
    fun `su binary presence is detected as root`() {
        val signals = probe(exists = { path -> path == "/system/bin/su" }).current()
        assertThat(signals.rootDetected).isTrue()
        assertThat(signals.any).isTrue()
        assertThat(signals.activeCategories()).contains(TamperCategory.ROOT_DETECTED)
    }

    @Test
    fun `magisk paths are detected as root`() {
        val signals = probe(exists = { path -> path == "/data/adb/magisk/busybox" }).current()
        assertThat(signals.rootDetected).isTrue()
    }

    @Test
    fun `test keys build is detected`() {
        val signals = probe(buildTags = "test-keys").current()
        assertThat(signals.testKeysBuild).isTrue()
        assertThat(signals.rootDetected).isTrue()
        assertThat(signals.activeCategories()).contains(TamperCategory.TEST_KEYS)
    }

    @Test
    fun `xposed package presence is detected`() {
        val signals = probe(xposedPackages = listOf("de.robv.android.xposed.installer")).current()
        assertThat(signals.xposedDetected).isTrue()
        assertThat(signals.activeCategories()).contains(TamperCategory.XPOSE_DETECTED)
    }

    @Test
    fun `debuggable app flag flows through`() {
        val signals = probe(debuggable = true).current()
        assertThat(signals.debuggableApp).isTrue()
        assertThat(signals.activeCategories()).contains(TamperCategory.DEBUGGABLE_APK)
    }
}