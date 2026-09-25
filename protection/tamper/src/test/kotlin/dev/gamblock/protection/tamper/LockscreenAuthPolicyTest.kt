package dev.gamblock.protection.tamper

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LockscreenAuthPolicyTest {

    @Test
    fun `no auth required always allows`() {
        assertThat(LockscreenAuthPolicy.decide(requireAuth = false, deviceProtected = false))
            .isEqualTo(LockscreenAuthOutcome.ALLOW)
    }

    @Test
    fun `auth required with no device lock refuses`() {
        assertThat(LockscreenAuthPolicy.decide(requireAuth = true, deviceProtected = false))
            .isEqualTo(LockscreenAuthOutcome.DENY_NO_LOCK)
    }

    @Test
    fun `auth required and protected prompts`() {
        assertThat(LockscreenAuthPolicy.decide(requireAuth = true, deviceProtected = true))
            .isEqualTo(LockscreenAuthOutcome.REQUIRE_PROMPT)
    }

    @Test
    fun `granted prompt allows`() {
        assertThat(LockscreenAuthPolicy.onPromptResult(requireAuth = true, deviceProtected = true, granted = true))
            .isEqualTo(LockscreenAuthOutcome.ALLOW)
    }

    @Test
    fun `cancelled prompt stays refused`() {
        assertThat(LockscreenAuthPolicy.onPromptResult(requireAuth = true, deviceProtected = true, granted = false))
            .isEqualTo(LockscreenAuthOutcome.DENY_CANCELLED)
    }
}