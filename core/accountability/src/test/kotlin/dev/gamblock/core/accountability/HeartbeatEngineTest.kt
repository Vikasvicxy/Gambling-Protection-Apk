package dev.gamblock.core.accountability

import com.google.common.truth.Truth.assertThat
import dev.gamblock.core.model.HealthStatus
import dev.gamblock.core.model.HeartbeatConfig
import dev.gamblock.core.model.HeartbeatState
import dev.gamblock.core.model.HeartbeatStatus
import dev.gamblock.core.model.ProtectionState
import org.junit.Test

class HeartbeatEngineTest {

    private var now = 10_000_000L
    private val engine = HeartbeatEngine({ now }, HeartbeatConfig(intervalMillis = 1000L, lostThresholdMillis = 5000L))

    private fun state(device: String = "aa".repeat(16), checkIn: Long, seq: Long = 1L) = HeartbeatState(
        deviceId = device,
        protectionState = ProtectionState.ACTIVE,
        health = HealthStatus.HEALTHY,
        lastCheckInEpochMs = checkIn,
        appVersion = "0.1.0",
        databaseVersion = 1,
        heartbeatSequence = seq,
    )

    @Test
    fun `no heartbeat at all reports NEVER`() {
        val evaluation = engine.evaluate(null)
        assertThat(evaluation.status).isEqualTo(HeartbeatStatus.NEVER)
        assertThat(evaluation.message).contains("No protection check-in")
    }

    @Test
    fun `recent heartbeat within interval reports RECENT`() {
        val evaluation = engine.evaluate(state(checkIn = now - 500L))
        assertThat(evaluation.status).isEqualTo(HeartbeatStatus.RECENT)
    }

    @Test
    fun `heartbeat older than interval but inside threshold reports UNKNOWN_WITHIN_THRESHOLD`() {
        val evaluation = engine.evaluate(state(checkIn = now - 3000L))
        assertThat(evaluation.status).isEqualTo(HeartbeatStatus.UNKNOWN_WITHIN_THRESHOLD)
        assertThat(evaluation.message).contains("not yet confirmed")
    }

    @Test
    fun `heartbeat beyond lost threshold reports LOST`() {
        val evaluation = engine.evaluate(state(checkIn = now - 6000L))
        assertThat(evaluation.status).isEqualTo(HeartbeatStatus.LOST)
        assertThat(evaluation.message).isEqualTo(
            "Protection status could not be confirmed.",
        )
    }

    @Test
    fun `lost threshold is configurable`() {
        val tight = HeartbeatEngine({ now }, HeartbeatConfig(intervalMillis = 1000L, lostThresholdMillis = 2000L))
        val evaluation = tight.evaluate(state(checkIn = now - 2500L))
        assertThat(evaluation.status).isEqualTo(HeartbeatStatus.LOST)
    }

    @Test
    fun `shouldNotifyLost only on transition into LOST`() {
        assertThat(engine.shouldNotifyLost(HeartbeatStatus.RECENT, HeartbeatStatus.LOST)).isTrue()
        assertThat(engine.shouldNotifyLost(HeartbeatStatus.LOST, HeartbeatStatus.LOST)).isFalse()
        assertThat(engine.shouldNotifyLost(HeartbeatStatus.RECENT, HeartbeatStatus.RECENT)).isFalse()
        assertThat(engine.shouldNotifyLost(HeartbeatStatus.UNKNOWN_WITHIN_THRESHOLD, HeartbeatStatus.LOST)).isTrue()
    }

    @Test
    fun `recovery from lost is detected`() {
        val fresh = state(checkIn = now)
        val lostEval = HeartbeatStatus.LOST
        assertThat(engine.isRecoveryFromLost(fresh, lostEval)).isTrue()
        assertThat(engine.isRecoveryFromLost(fresh, HeartbeatStatus.RECENT)).isFalse()
    }

    @Test
    fun `replay heartbeat with older sequence is rejected`() {
        val prev = state(checkIn = now - 10L, seq = 5L)
        val replay = state(checkIn = now, seq = 5L)
        assertThat(engine.isReplay(prev, replay)).isTrue()
        assertThat(engine.isReplay(prev, state(checkIn = now, seq = 6L))).isFalse()
        assertThat(engine.isReplay(null, replay)).isFalse()
    }

    @Test
    fun `invalid config is rejected`() {
        try {
            HeartbeatConfig(intervalMillis = 0L)
            assertThat(false).isTrue()
        } catch (expected: IllegalArgumentException) {
            assertThat(expected.message).contains("interval")
        }
        try {
            HeartbeatConfig(intervalMillis = 1000L, lostThresholdMillis = 500L)
            assertThat(false).isTrue()
        } catch (expected: IllegalArgumentException) {
            assertThat(expected.message).contains("lost threshold")
        }
    }
}