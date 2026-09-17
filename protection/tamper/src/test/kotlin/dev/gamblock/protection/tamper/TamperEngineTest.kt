package dev.gamblock.protection.tamper

import com.google.common.truth.Truth.assertThat
import dev.gamblock.core.testing.NoOpLogger
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TamperEngineTest {

    private val hmac = StaticEvidenceHmac("test-key-material".toByteArray())
    private val store = InMemoryTamperEvidenceStore()
    private var signals = TamperSignals()

    private fun engine() = TamperEngine(
        probe = TamperSignalProbe { signals },
        recorder = TamperRecorder(store, hmac, clock = { 1_000L }),
        logger = NoOpLogger,
    )

    @Test
    fun `clean check records nothing`() = runTest {
        signals = TamperSignals()
        val overview = engine().performCheck("test")
        assertThat(overview.totalEvidence).isEqualTo(0)
        assertThat(overview.chainValid).isTrue()
    }

    @Test
    fun `suspicious check records evidence`() = runTest {
        signals = TamperSignals(rootDetected = true)
        val overview = engine().performCheck("test")
        assertThat(overview.totalEvidence).isEqualTo(1)
        assertThat(overview.lastCategory).isEqualTo(TamperCategory.ROOT_DETECTED)
    }

    @Test
    fun `edited evidence is reported by chain verification`() = runTest {
        signals = TamperSignals(rootDetected = true)
        val engine = engine()
        engine.performCheck("test")
        val stored = store.load()
        val edited = stored.first().copy(detail = mapOf("injected" to "true"))
        store.save(listOf(edited))

        val result = engine.verifyEvidenceChain()
        assertThat(result.valid).isFalse()
        assertThat(result.reason).contains("content hash mismatch")
    }

    @Test
    fun `engine survives probe exceptions`() = runTest {
        val engine = TamperEngine(
            probe = TamperSignalProbe { throw IllegalStateException("probe boom") },
            recorder = TamperRecorder(store, hmac, clock = { 1_000L }),
            logger = NoOpLogger,
        )
        val overview = engine.performCheck("test")
        assertThat(overview.chainValid).isFalse()
    }
}