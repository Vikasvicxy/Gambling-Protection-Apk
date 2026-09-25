package dev.gamblock.data.preferences

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VpnDisclosurePolicyTest {

    @Test
    fun `only the current disclosure version is accepted`() {
        assertThat(VpnDisclosurePolicy.accepted(null)).isFalse()
        assertThat(VpnDisclosurePolicy.accepted(0)).isFalse()
        assertThat(VpnDisclosurePolicy.accepted(VpnDisclosurePolicy.CURRENT_VERSION)).isTrue()
        assertThat(VpnDisclosurePolicy.accepted(VpnDisclosurePolicy.CURRENT_VERSION + 1)).isFalse()
    }

    @Test
    fun `action follows acceptance state`() {
        assertThat(VpnDisclosurePolicy.action(null)).isEqualTo(VpnDisclosureAction.SHOW)
        assertThat(VpnDisclosurePolicy.action(VpnDisclosurePolicy.CURRENT_VERSION - 1))
            .isEqualTo(VpnDisclosureAction.SHOW)
        assertThat(VpnDisclosurePolicy.action(VpnDisclosurePolicy.CURRENT_VERSION))
            .isEqualTo(VpnDisclosureAction.ALLOW)
    }

    @Test
    fun `state always exposes the current version`() {
        assertThat(VpnDisclosurePolicy.state(null)).isEqualTo(
            VpnDisclosureState(version = VpnDisclosurePolicy.CURRENT_VERSION, accepted = false),
        )
        assertThat(VpnDisclosurePolicy.state(VpnDisclosurePolicy.CURRENT_VERSION)).isEqualTo(
            VpnDisclosureState(version = VpnDisclosurePolicy.CURRENT_VERSION, accepted = true),
        )
    }

    @Test
    fun `reviewer host policy is case and trailing-dot insensitive`() {
        assertThat(ReviewerModePolicy.isBlockedHost("Reviewer-Blocked.Test.")).isTrue()
        assertThat(ReviewerModePolicy.isBlockedHost("safe-example.test")).isFalse()
        assertThat(ReviewerModePolicy.isBlockedHost("example.test")).isFalse()
    }
}
