package dev.gamblock.core.accountability

import com.google.common.truth.Truth.assertThat
import dev.gamblock.core.model.InstallIdentity
import org.junit.Test

class DeviceIdentityServiceTest {

    private val service = DeviceIdentityService()

    @Test
    fun `register produces a valid 32-hex id`() {
        val result = service.register(nowEpochMs = 1L, randomId = { "a".repeat(32) })
        val identity = (result as DeviceIdentityService.RegisterResult.Registered).identity
        assertThat(identity.id).isEqualTo("a".repeat(32))
        assertThat(identity.createdAtEpochMs).isEqualTo(1L)
    }

    @Test
    fun `register rejects non-hex ids`() {
        val result = service.register(nowEpochMs = 1L, randomId = { "z".repeat(32) })
        assertThat(result).isInstanceOf(DeviceIdentityService.RegisterResult.Rejected::class.java)
    }

    @Test
    fun `register rejects wrong length`() {
        val result = service.register(nowEpochMs = 1L, randomId = { "a".repeat(31) })
        assertThat(result).isInstanceOf(DeviceIdentityService.RegisterResult.Rejected::class.java)
    }

    @Test
    fun `existing device id is preserved on re-register`() {
        val existing = "ab".repeat(16)
        val result = service.register(nowEpochMs = 1L, existingId = existing)
        assertThat((result as DeviceIdentityService.RegisterResult.Registered).identity.id).isEqualTo(existing)
    }

    @Test
    fun `rotate unlinks the old identity`() {
        val old = InstallIdentity("aa".repeat(16), createdAtEpochMs = 1L)
        val rotated = service.rotate(old, nowEpochMs = 2L, randomId = { "ee".repeat(16) })
        assertThat(rotated.id).isEqualTo("ee".repeat(16))
        assertThat(rotated.id).isNotEqualTo(old.id)
        assertThat(rotated.createdAtEpochMs).isEqualTo(2L)
    }

    @Test
    fun `default random id has sufficient entropy and is hex`() {
        val a = DeviceIdentityService.defaultRandomId()
        val b = DeviceIdentityService.defaultRandomId()
        assertThat(a).hasLength(32)
        assertThat(a).isNotEqualTo(b)
        assertThat(a.all { it.lowercaseChar() in "0123456789abcdef" }).isTrue()
    }
}

class ParentAuthorizationTest {

    private val authz = ParentAuthorization()
    private val parentId = "aa".repeat(16)
    private val childId = "bb".repeat(16)
    private val unrelatedChild = "cc".repeat(16)

    @Test
    fun `parent may access own linked child`() {
        val result = authz.authorize(
            parentAccountId = parentId,
            persistedParentAccountId = parentId,
            childDeviceId = childId,
            persistedChildDeviceId = childId,
        )
        assertThat(result).isInstanceOf(ParentAuthorization.Result.Granted::class.java)
    }

    @Test
    fun `parent account mismatch is denied`() {
        val result = authz.authorize(
            parentAccountId = "ff".repeat(16),
            persistedParentAccountId = parentId,
            childDeviceId = childId,
            persistedChildDeviceId = childId,
        )
        assertThat(result).isInstanceOf(ParentAuthorization.Result.Denied::class.java)
    }

    @Test
    fun `parent accessing unrelated child is denied`() {
        val result = authz.authorize(
            parentAccountId = parentId,
            persistedParentAccountId = parentId,
            childDeviceId = unrelatedChild,
            persistedChildDeviceId = childId,
        )
        assertThat(result).isInstanceOf(ParentAuthorization.Result.Denied::class.java)
    }

    @Test
    fun `locked category enforcement`() {
        val config = dev.gamblock.core.model.ParentModeConfig(
            parentAccountId = parentId,
            displayName = "Parent",
            requireApprovalForConfigChanges = true,
            lockedCategories = setOf(dev.gamblock.core.model.Category.GAMBLING, dev.gamblock.core.model.Category.CASINO),
            createdAtEpochMs = 1L,
        )
        assertThat(authz.isCategoryLocked(config, dev.gamblock.core.model.Category.GAMBLING)).isTrue()
        assertThat(authz.isCategoryLocked(config, dev.gamblock.core.model.Category.POKER)).isFalse()
    }

    @Test
    fun `config changes require approval only for verified linkage`() {
        val config = dev.gamblock.core.model.ParentModeConfig(
            parentAccountId = parentId,
            displayName = "Parent",
            requireApprovalForConfigChanges = true,
            lockedCategories = emptySet(),
            createdAtEpochMs = 1L,
        )
        assertThat(authz.requiresApproval(config, isLinkageVerified = true)).isTrue()
        assertThat(authz.requiresApproval(config, isLinkageVerified = false)).isFalse()
        assertThat(authz.requiresApproval(config.copy(requireApprovalForConfigChanges = false), isLinkageVerified = true)).isFalse()
    }
}