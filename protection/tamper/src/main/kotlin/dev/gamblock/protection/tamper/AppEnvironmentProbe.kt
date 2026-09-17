package dev.gamblock.protection.tamper

import android.content.pm.ApplicationInfo
import android.os.Build
import java.io.File

/** Observable, on-device signals correlated with app-level tampering. */
data class TamperSignals(
    val rootDetected: Boolean = false,
    val xposedDetected: Boolean = false,
    val testKeysBuild: Boolean = false,
    val debuggableApp: Boolean = false,
) {
    val any: Boolean
        get() = rootDetected || xposedDetected || testKeysBuild || debuggableApp

    fun activeCategories(): List<TamperCategory> = buildList {
        if (rootDetected) add(TamperCategory.ROOT_DETECTED)
        if (xposedDetected) add(TamperCategory.XPOSE_DETECTED)
        if (testKeysBuild) add(TamperCategory.TEST_KEYS)
        if (debuggableApp) add(TamperCategory.DEBUGGABLE_APK)
    }
}

/** Source of current tamper-relevant signals. */
fun interface TamperSignalProbe {
    fun current(): TamperSignals
}

/** Answers "is this package installed?", decoupling the probe from PackageManager. */
fun interface PackageLookup {
    fun isInstalled(packageName: String): Boolean
}

/**
 * Best-effort heuristic environment probe. Detection here is *indirect*: root
 * and Xposed presences are inferred from well-known markers. This is a weak but
 * harmless signal source (evidence only, never a hard block) - see the report.
 * Everything is injectable for deterministic tests; production wiring feeds it
 * with the real PackageManager and ApplicationInfo flags.
 */
class AppEnvironmentProbe(
    private val packageLookup: PackageLookup?,
    private val debuggableApp: Boolean,
    private val buildTags: String? = Build.TAGS,
    private val fileExists: (String) -> Boolean = { File(it).exists() },
) : TamperSignalProbe {

    override fun current(): TamperSignals {
        val testKeys = buildTags?.contains("test-keys") == true
        val suPaths = SU_PATHS.any { fileExists(it) }
        val xposed = packageLookup?.let { lookup ->
            XPOSED_PACKAGES.any { lookup.isInstalled(it) }
        } ?: false
        return TamperSignals(
            rootDetected = suPaths || testKeys,
            xposedDetected = xposed,
            testKeysBuild = testKeys,
            debuggableApp = debuggableApp,
        )
    }

    companion object {
        /** Interprets debug status from real ApplicationInfo flags. */
        fun isDebuggable(applicationInfo: ApplicationInfo): Boolean =
            (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        private val SU_PATHS = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/app/Superuser.apk",
            "/system/app/Magisk.apk",
            "/data/adb/magisk/busybox",
            "/data/adb/ksu",
        )

        private val XPOSED_PACKAGES = listOf(
            "de.robv.android.xposed.installer",
            "org.lsposed.manager",
        )
    }
}