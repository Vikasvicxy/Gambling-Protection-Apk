package dev.gamblock.protection.vpn

import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gamblock.core.common.logging.ShieldLogger
import dev.gamblock.core.model.AppExclusionFilter
import dev.gamblock.data.preferences.AppExclusionRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Answers "is this package present?" without leaking [PackageManager] types.
 *
 * Same indirection the tamper module uses for its own package probe: it keeps
 * the decision logic unit testable and stops a framework exception from
 * escaping into logic that should never throw.
 */
fun interface InstalledPackageProbe {
    fun isInstalled(packageName: String): Boolean
}

/**
 * Applies per-app DNS exemptions to the VPN builder.
 *
 * Split tunnelling in Android works by declaring the apps that must *skip* the
 * tunnel entirely, via `Builder.addDisallowedApplication`. For a DNS-only
 * tunnel that means an exempt app's lookups never reach Shield at all, which
 * is precisely what a bank or a corporate VPN client needs and precisely what
 * the user must be warned they are giving up.
 *
 * Two platform behaviours drive the design here:
 *  1. `addDisallowedApplication` throws [PackageManager.NameNotFoundException]
 *     for a package that is not installed, so membership is checked first.
 *  2. The platform caps how many exemptions one VPN may declare, so the count
 *     is bounded and the overflow is reported rather than silently dropped.
 *
 * Exemptions are fixed at `establish()` time by the platform, so a change to
 * the list only takes effect on the next tunnel setup. The service watches the
 * repository and re-establishes rather than pretending the change is live.
 */
@Singleton
class AppExclusionApplier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: AppExclusionRepository,
    private val logger: ShieldLogger,
) {
    private val packageManager: PackageManager get() = context.packageManager

    private val probe = InstalledPackageProbe { packageName ->
        runCatching { packageManager.getPackageInfo(packageName, 0) }.isSuccess
    }

    /**
     * Packages that must stay inside the tunnel no matter what is requested.
     *
     * Shield's own package is the important one: excluding the application that
     * owns the [VpnService] tears the tunnel down while it is being configured.
     */
    private fun mustNeverExclude(): Set<String> = setOf(context.packageName)

    fun currentPlan(): AppExclusionFilter.Selection = plan(
        requested = repository.excludedPackages,
        probe = probe,
        mustNeverExclude = mustNeverExclude(),
    )

    /**
     * Adds the vetted exemptions to [builder] and returns how many were applied.
     *
     * The per-package `runCatching` is deliberately redundant with the probe:
     * an app can be uninstalled between the probe and this call, and a throw
     * here would abort the whole tunnel setup.
     */
    fun applyTo(builder: VpnService.Builder, selection: AppExclusionFilter.Selection): Int {
        var applied = 0
        for (packageName in selection.applied) {
            val result = runCatching { builder.addDisallowedApplication(packageName) }
            if (result.isSuccess) {
                applied++
            } else {
                logger.w(TAG, "addDisallowedApplication rejected ${packageName}: ${result.exceptionOrNull()?.message}")
            }
        }
        if (selection.malformed.isNotEmpty()) {
            logger.w(TAG, "dropped ${selection.malformed.size} malformed package name(s)")
        }
        if (selection.notInstalled.isNotEmpty()) {
            logger.i(TAG, "skipped ${selection.notInstalled.size} excluded app(s) no longer installed")
        }
        if (selection.protectedPackages.isNotEmpty()) {
            logger.w(TAG, "refused to exempt protected package(s): ${selection.protectedPackages.joinToString()}")
        }
        if (selection.truncated) {
            logger.w(TAG, "exemption list truncated to ${selection.applied.size}; split tunnelling is not complete")
        }
        if (applied > 0) {
            logger.i(TAG, "split tunnelling active for $applied app(s)")
        }
        return applied
    }

    companion object {
        private const val TAG = "AppExclusionApplier"

        /**
         * Pure planning step: turns a raw requested list into the exact set that
         * is safe to hand to the platform.
         *
         * Lives in the companion because it touches no instance state, which
         * also means it can be tested without constructing a [VpnService] or a
         * Hilt graph.
         */
        fun plan(
            requested: Collection<String>,
            probe: InstalledPackageProbe,
            mustNeverExclude: Set<String>,
            limit: Int = AppExclusionFilter.MAX_EXCLUSIONS,
        ): AppExclusionFilter.Selection {
            // Probe first so `select` receives only packages that really exist.
            // Probing the requested set is far cheaper than enumerating every
            // installed app, which on a real device is thousands of entries.
            val present = requested
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .filter {
                    // A probe that throws must not take the tunnel down with it.
                    runCatching { probe.isInstalled(it) }.getOrDefault(false)
                }
            return AppExclusionFilter.select(
                requested = requested,
                installed = present.toSet(),
                mustNeverExclude = mustNeverExclude,
                limit = limit,
            )
        }
    }
}
