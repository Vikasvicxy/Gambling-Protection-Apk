package dev.gamblock.core.model

import kotlinx.serialization.Serializable

/**
 * Canonical meta-table keys for the signed update pipeline. Shared by `data:update`
 * (writer), `data:repository` (reader) and `protection:health` (reporter) so all three
 * speak the same persisted vocabulary. Values in [UpdateMeta] are plain text; booleans
 * are "true"/"false".
 */
object UpdateMeta {
    const val KEY_MAX_OBSERVED_VERSION = "update_max_observed_version"
    const val KEY_LAST_CHECK_EPOCH_MS = "update_last_check_epoch_ms"
    const val KEY_LAST_SUCCESS_EPOCH_MS = "update_last_success_epoch_ms"
    const val KEY_LAST_FAILURE_REASON = "update_last_failure_reason"
    const val KEY_ACTIVE_RELEASE_ID = "update_active_release_id"
    const val KEY_ACTIVE_SIGNING_KEY_ID = "update_active_signing_key_id"
    const val KEY_DELTA_LAST_APPLIED = "update_delta_last_applied"
    const val KEY_ROLLBACK_EVER_APPLIED = "update_rollback_ever_applied"
    const val KEY_PREVIOUS_GOOD_VERSION = "update_previous_good_version"
    const val KEY_PREVIOUS_GOOD_RELEASE_ID = "update_previous_good_release_id"
    const val KEY_PREVIOUS_GOOD_DIGEST = "update_previous_good_digest"
    const val KEY_PREVIOUS_GOOD_APPLIED_EPOCH_MS = "update_previous_good_applied_epoch_ms"
}

/** Durable, health-relevant facts about the installed database and update pipeline. */
@Serializable
data class UpdateHealthSnapshot(
    val installedVersion: Int,
    val maxObservedVersion: Int,
    val activeReleaseId: String? = null,
    val activeSigningKeyId: String? = null,
    val lastSuccessEpochMs: Long = 0L,
    val lastFailureReason: String? = null,
    val deltaLastApplied: Boolean = false,
    val rollbackEverApplied: Boolean = false,
    val previousGoodVersion: Int? = null,
    val previousGoodAppliedEpochMs: Long = 0L,
) {
    val hasTrustedSignature: Boolean get() = activeSigningKeyId != null
}