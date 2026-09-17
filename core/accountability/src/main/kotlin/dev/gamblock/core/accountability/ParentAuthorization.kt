package dev.gamblock.core.accountability

import dev.gamblock.core.model.Category
import dev.gamblock.core.model.ParentModeConfig
import dev.gamblock.core.model.SupervisedChildDevice

/**
 * Genuine Parent / Guardian authorization.
 *
 * A parent may only ever see/anoint the specific child devices linked to their parent
 * account. Enforcement is server-side against stored linkage - a child device may not
 * impersonate a parent, and a parent account may not view an unrelated child.
 */
class ParentAuthorization {

    sealed interface Result {
        data class Granted(val supervisedDevice: SupervisedChildDevice) : Result
        data class Denied(val reason: String) : Result
    }

    /**
     * Whether [parentAccountId] may access [childDeviceId]. Called with the persisted
     * mapping (verified linkage), never a client-provided claim alone.
     */
    fun authorize(
        parentAccountId: String,
        persistedParentAccountId: String,
        childDeviceId: String,
        persistedChildDeviceId: String,
    ): Result {
        if (parentAccountId != persistedParentAccountId) {
            return Result.Denied("parent account mismatch")
        }
        if (childDeviceId != persistedChildDeviceId) {
            return Result.Denied("attempt to access unrelated child device")
        }
        return Result.Granted(
            SupervisedChildDevice(
                childDeviceId = childDeviceId,
                displayName = "",
                protectionActive = false,
                health = dev.gamblock.core.model.HealthStatus.UNKNOWN,
                databaseCurrent = false,
                lastCheckInEpochMs = null,
                todayBlockedCount = 0,
                lockedCategories = emptySet(),
                relationshipStatus = dev.gamblock.core.model.RelationshipStatus.ACTIVE,
            ),
        )
    }

    /** Whether a category is locked by a parent config and thus protected in child mode. */
    fun isCategoryLocked(config: ParentModeConfig, category: Category): Boolean =
        category in config.lockedCategories

    /** Whether a child-initiated config change requires parent approval. */
    fun requiresApproval(config: ParentModeConfig, isLinkageVerified: Boolean): Boolean =
        isLinkageVerified && config.requireApprovalForConfigChanges
}