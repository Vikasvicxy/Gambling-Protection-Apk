package dev.gamblock.protection.tamper

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TamperRecorderTest {

    private val hmac = StaticEvidenceHmac("test-key-material".toByteArray())
    private val store = InMemoryTamperEvidenceStore()

    private var now = 1_000L

    private fun recorder() = TamperRecorder(store, hmac, clock = { now })

    @Test
    fun `record appends a verifiable signed entry`() = runTest {
        val recorder = recorder()
        val entry = recorder.record(TamperCategory.ROOT_DETECTED, mapOf("path" to "/system/bin/su"))
        assertThat(entry.sequence).isEqualTo(1L)
        assertThat(recorder.verifyChain().valid).isTrue()
        assertThat(recorder.records()).hasSize(1)
    }

    @Test
    fun `records chain sequentially`() = runTest {
        val recorder = recorder()
        now = 1_000L
        recorder.record(TamperCategory.ROOT_DETECTED)
        now = 2_000L
        val second = recorder.record(TamperCategory.UPDATE_SIGNATURE_FAILURE)
        assertThat(second.sequence).isEqualTo(2L)
        assertThat(recorder.verifyChain().valid).isTrue()
    }

    @Test
    fun `signal probe records every active category`() = runTest {
        val recorder = recorder()
        val signals = TamperSignals(rootDetected = true, xposedDetected = true)
        val probe = TamperSignalProbe { signals }
        recorder.recordSignalProbe(probe)
        val recorded = recorder.records()
        assertThat(recorded).hasSize(2)
        assertThat(recorded.map { it.category })
            .containsExactly(TamperCategory.ROOT_DETECTED, TamperCategory.XPOSE_DETECTED)
        assertThat(recorder.verifyChain().valid).isTrue()
    }

    @Test
    fun `clean probe records nothing`() = runTest {
        val recorder = recorder()
        val probe = TamperSignalProbe { TamperSignals() }
        recorder.recordSignalProbe(probe)
        assertThat(recorder.records()).isEmpty()
        assertThat(recorder.overview().totalEvidence).isEqualTo(0)
        assertThat(recorder.overview().chainValid).isTrue()
    }

    @Test
    fun `overview summarizes counts and tail`() = runTest {
        val recorder = recorder()
        recorder.record(TamperCategory.ROOT_DETECTED)
        recorder.record(TamperCategory.ROOT_DETECTED)
        recorder.record(TamperCategory.TEST_KEYS)
        val overview = recorder.overview()
        assertThat(overview.totalEvidence).isEqualTo(3)
        assertThat(overview.categoryCounts)
            .containsExactlyEntriesIn(mapOf("ROOT_DETECTED" to 2, "TEST_KEYS" to 1))
        assertThat(overview.lastCategory).isEqualTo(TamperCategory.TEST_KEYS)
    }

    @Test
    fun `tail returns the newest record`() = runTest {
        val recorder = recorder()
        recorder.record(TamperCategory.ROOT_DETECTED)
        val last = recorder.record(TamperCategory.TEST_KEYS)
        assertThat(recorder.tail()).isEqualTo(last)
        assertThat(recorder.tail()?.sequence).isEqualTo(2L)
        assertThat(recorder.records()).hasSize(2)
    }

    @Test
    fun `edited stored record fails verification`() = runTest {
        val recorder = recorder()
        val entry = recorder.record(TamperCategory.ROOT_DETECTED, mapOf("path" to "/system/bin/su"))
        val edited = entry.copy(detail = mapOf("path" to "/system/xbin/su"))
        store.save(listOf(edited))
        val result = recorder.verifyChain()
        assertThat(result.valid).isFalse()
        assertThat(result.reason).contains("content hash mismatch")
    }
}