package dev.gamblock.protection.vpn

import dev.gamblock.core.model.VpnRuntimeState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-wide singleton reflecting the VPN runtime. Both UI and health read it. */
@Singleton
class VpnStateStore @Inject constructor() {

    private val _state = MutableStateFlow(VpnRuntimeState())
    val state: StateFlow<VpnRuntimeState> = _state.asStateFlow()

    fun markConnecting() {
        _state.value = VpnRuntimeState(
            isRunning = false,
            startedAtEpochMs = null,
            failure = null,
            userStopped = false,
        )
    }

    fun markConnected(startedAtEpochMs: Long) {
        _state.value = VpnRuntimeState(
            isRunning = true,
            startedAtEpochMs = startedAtEpochMs,
            failure = null,
            userStopped = false,
        )
    }

    fun markStopped(reason: VpnRuntimeState.VpnFailure? = null, userStopped: Boolean = false) {
        val prev = _state.value
        _state.value = prev.copy(
            isRunning = false,
            startedAtEpochMs = null,
            failure = reason,
            userStopped = userStopped,
        )
    }

    fun recordQuery(allowed: Boolean) {
        val prev = _state.value
        _state.value = prev.copy(
            queriesHandled = prev.queriesHandled + 1,
            queriesAllowed = prev.queriesAllowed + if (allowed) 1 else 0,
            queriesBlocked = prev.queriesBlocked + if (allowed) 0 else 1,
        )
    }

    fun recordExceptionApplied() {
        val prev = _state.value
        _state.value = prev.copy(exceptionsApplied = prev.exceptionsApplied + 1)
    }

    fun resetCounters() {
        _state.value = _state.value.copy(
            queriesHandled = 0L,
            queriesBlocked = 0L,
            queriesAllowed = 0L,
            exceptionsApplied = 0L,
        )
    }
}