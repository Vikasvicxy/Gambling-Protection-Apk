package dev.gamblock.core.release

/**
 * Upgrade/downgrade/rollback policy.
 *
 * A manifest is permitted when ALL of the following are true:
 * - [SignedReleaseManifest.minimumAppVersion] <= current app versionCode.
 * - The version is strictly greater than the installed version, OR the release is a signed
 *   emergency rollback ([SignedReleaseManifest.rollback] == true).
 * - For rollbacks the candidate version must be STRICTLY LESS than the installed version
 *   so a rollback signed manifest cannot also be forwarded as a regular upgrade.
 * - No manifest may ever target a version less than what has ever been signed
 *   ([maxObservedVersion]) UNLESS the release is a rollback.
 */
object VersionPolicy {

    data class Result(val allowed: Boolean, val reason: String)

    fun evaluateUpgrade(
        candidate: SignedReleaseManifest,
        installedVersion: Int,
        currentAppVersionCode: Int,
        maxObservedVersion: Int,
        previousReleaseId: String? = null,
    ): Result {
        if (candidate.minimumAppVersion > currentAppVersionCode) {
            return Result(false, "requires minimum app version ${candidate.minimumAppVersion} (installed $currentAppVersionCode)")
        }

        return if (candidate.rollback) {
            evaluateRollback(candidate, installedVersion, maxObservedVersion)
        } else {
            evaluateForward(candidate, installedVersion, maxObservedVersion)
        }
    }

    private fun evaluateForward(
        candidate: SignedReleaseManifest,
        installedVersion: Int,
        maxObservedVersion: Int,
    ): Result {
        if (candidate.version <= installedVersion) {
            return Result(false, "candidate version ${candidate.version} <= installed $installedVersion (downgrade/replay rejected)")
        }
        if (candidate.version <= maxObservedVersion) {
            return Result(false, "candidate version ${candidate.version} <= maxObserved $maxObservedVersion (replay rejected)")
        }
        return Result(true, "forward upgrade from $installedVersion to ${candidate.version}")
    }

    private fun evaluateRollback(
        candidate: SignedReleaseManifest,
        installedVersion: Int,
        maxObservedVersion: Int,
    ): Result {
        if (candidate.version >= installedVersion) {
            return Result(false, "rollback candidate version ${candidate.version} >= installed $installedVersion (rollback must be strictly less)")
        }
        if (candidate.version >= maxObservedVersion) {
            return Result(false, "rollback candidate version ${candidate.version} >= maxObserved $maxObservedVersion (impossible)")
        }
        return Result(true, "emergency rollback from $installedVersion to ${candidate.version}")
    }
}