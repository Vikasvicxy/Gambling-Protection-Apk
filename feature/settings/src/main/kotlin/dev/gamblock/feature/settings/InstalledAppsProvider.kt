package dev.gamblock.feature.settings

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import dev.gamblock.core.model.InstalledAppCandidate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Lists the apps the platform is willing to show Shield.
 *
 * The honesty problem here is visibility, not code. Android 11+ filters package
 * queries, so this returns a *partial* list: the catalog declared in the
 * manifest `<queries>` element, plus whatever the system volunteers. Callers
 * must not present the result as "every app on your phone".
 *
 * `QUERY_ALL_PACKAGES` would fix that, but it is a restricted-use permission
 * that Play only grants to a narrow set of app types and that requires a
 * written declaration. It is deliberately not requested; see the manifest
 * comment. The manual package-name field in the picker is the escape hatch for
 * the apps this cannot see.
 */
@Singleton
class InstalledAppsProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val packageManager: PackageManager get() = context.packageManager

    /**
     * @param includeSystem when false, pre-installed packages are dropped. The
     *   picker sets this true while the user is searching so a system app can
     *   still be found by name.
     */
    suspend fun candidates(
        dispatcher: CoroutineDispatcher,
        includeSystem: Boolean = false,
    ): List<InstalledAppCandidate> = withContext(dispatcher) {
        val self = context.packageName
        val result = mutableListOf<InstalledAppCandidate>()
        runCatching {
            packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        }.onSuccess { installed ->
            for (info in installed) {
                if (info.packageName == self) continue
                val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                if (isSystem && !includeSystem) continue
                val label = runCatching { packageManager.getApplicationLabel(info).toString() }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: info.packageName
                result += InstalledAppCandidate(
                    packageName = info.packageName,
                    label = label,
                    isSystemApp = isSystem,
                )
            }
        }
        result
    }

    /** Resolves a single app the user typed by hand, if the platform allows it. */
    suspend fun resolve(
        dispatcher: CoroutineDispatcher,
        packageName: String,
    ): InstalledAppCandidate? = withContext(dispatcher) {
        runCatching { packageManager.getApplicationInfo(packageName, 0) }.getOrNull()?.let { info ->
            InstalledAppCandidate(
                packageName = info.packageName,
                label = runCatching { packageManager.getApplicationLabel(info).toString() }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: info.packageName,
                isSystemApp = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            )
        }
    }
}
