package dev.gamblock.protection.vpn

import com.google.common.truth.Truth.assertThat
import dev.gamblock.core.model.VpnRuntimeState
import org.junit.Test

class VpnStateStoreTest {

    @Test
    fun `initial state is idle`() {
        val store = VpnStateStore()
        val s = store.state.value
        assertThat(s.isRunning).isFalse()
        assertThat(s.startedAtEpochMs).isNull()
        assertThat(s.failure).isNull()
        assertThat(s.userStopped).isFalse()
        assertThat(s.queriesHandled).isEqualTo(0L)
        assertThat(s.queriesBlocked).isEqualTo(0L)
        assertThat(s.queriesAllowed).isEqualTo(0L)
    }

    @Test
    fun `markConnecting resets runtime fields`() {
        val store = VpnStateStore()
        store.markConnected(9_000L)
        store.recordQuery(allowed = true)
        store.markConnecting()

        val s = store.state.value
        assertThat(s.isRunning).isFalse()
        assertThat(s.startedAtEpochMs).isNull()
        assertThat(s.failure).isNull()
        assertThat(s.userStopped).isFalse()
    }

    @Test
    fun `markConnected sets running and started time and clears failure`() {
        val store = VpnStateStore()
        store.markStopped(reason = VpnRuntimeState.VpnFailure.ESTABLISH_FAILED)
        store.markConnected(1_700_000_000_000L)

        val s = store.state.value
        assertThat(s.isRunning).isTrue()
        assertThat(s.startedAtEpochMs).isEqualTo(1_700_000_000_000L)
        assertThat(s.failure).isNull()
        assertThat(s.userStopped).isFalse()
    }

    @Test
    fun `markStopped with no reason marks a plain stop`() {
        val store = VpnStateStore()
        store.markConnected(5L)
        store.markStopped()

        val s = store.state.value
        assertThat(s.isRunning).isFalse()
        assertThat(s.startedAtEpochMs).isNull()
        assertThat(s.failure).isNull()
        assertThat(s.userStopped).isFalse()
    }

    @Test
    fun `markStopped records a failure reason`() {
        val store = VpnStateStore()
        store.markConnected(5L)
        store.markStopped(reason = VpnRuntimeState.VpnFailure.REVOKED)

        assertThat(store.state.value.failure).isEqualTo(VpnRuntimeState.VpnFailure.REVOKED)
    }

    @Test
    fun `markStopped records a user-initiated stop`() {
        val store = VpnStateStore()
        store.markConnected(5L)
        store.markStopped(userStopped = true)

        val s = store.state.value
        assertThat(s.isRunning).isFalse()
        assertThat(s.userStopped).isTrue()
        assertThat(s.failure).isNull()
    }

    @Test
    fun `recordQuery tracks answered queries`() {
        val store = VpnStateStore()
        store.recordQuery(allowed = true)
        store.recordQuery(allowed = true)
        store.recordQuery(allowed = false)
        store.recordQuery(allowed = false)

        val s = store.state.value
        assertThat(s.queriesHandled).isEqualTo(4L)
        assertThat(s.queriesAllowed).isEqualTo(2L)
        assertThat(s.queriesBlocked).isEqualTo(2L)
    }

    @Test
    fun `resetCounters zeroes counts and preserves runtime fields`() {
        val store = VpnStateStore()
        store.markConnected(5L)
        store.recordQuery(allowed = false)
        store.resetCounters()

        val s = store.state.value
        assertThat(s.queriesHandled).isEqualTo(0L)
        assertThat(s.queriesBlocked).isEqualTo(0L)
        assertThat(s.queriesAllowed).isEqualTo(0L)
        assertThat(s.isRunning).isTrue()
        assertThat(s.startedAtEpochMs).isEqualTo(5L)
    }
}