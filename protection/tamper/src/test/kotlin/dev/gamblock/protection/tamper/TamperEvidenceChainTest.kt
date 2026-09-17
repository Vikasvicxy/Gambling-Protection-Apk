package dev.gamblock.protection.tamper

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TamperEvidenceChainTest {

    private val hmac = StaticEvidenceHmac("test-key-material".toByteArray())
    private val otherHmac = StaticEvidenceHmac("a-different-key".toByteArray())

    @Test
    fun `empty chain verifies with zero records`() {
        val result = TamperEvidenceChain.verify(hmac, emptyList())
        assertThat(result.valid).isTrue()
        assertThat(result.verifiedCount).isEqualTo(0)
    }

    @Test
    fun `head record links to root and verifies`() {
        val r1 = TamperEvidenceChain.append(hmac, tail = null, category = TamperCategory.ROOT_DETECTED, timestampEpochMs = 10L)
        assertThat(r1.sequence).isEqualTo(1L)
        assertThat(r1.prevHash).isEqualTo(TamperEvidenceChain.ROOT_HASH)
        assertThat(TamperEvidenceChain.verify(hmac, listOf(r1)).valid).isTrue()
    }

    @Test
    fun `appended record links to previous hmac`() {
        val r1 = TamperEvidenceChain.append(hmac, null, TamperCategory.ROOT_DETECTED, 10L)
        val r2 = TamperEvidenceChain.append(hmac, r1, TamperCategory.XPOSE_DETECTED, 20L)
        assertThat(r2.sequence).isEqualTo(2L)
        assertThat(r2.prevHash).isEqualTo(r1.hmac)
        assertThat(TamperEvidenceChain.verify(hmac, listOf(r1, r2)).valid).isTrue()
    }

    @Test
    fun `tampered detail breaks content hash`() {
        val r1 = TamperEvidenceChain.append(hmac, null, TamperCategory.ROOT_DETECTED, 10L, mapOf("path" to "/system/bin/su"))
        val tampered = r1.copy(detail = mapOf("path" to "/system/bin/su", "extra" to "inserted"))
        val result = TamperEvidenceChain.verify(hmac, listOf(tampered))
        assertThat(result.valid).isFalse()
        assertThat(result.reason).contains("content hash mismatch")
    }

    @Test
    fun `tampered hmac breaks verification`() {
        val r1 = TamperEvidenceChain.append(hmac, null, TamperCategory.ROOT_DETECTED, 10L)
        val tampered = r1.copy(hmac = TamperEvidenceChain.ROOT_HASH)
        val result = TamperEvidenceChain.verify(hmac, listOf(tampered))
        assertThat(result.valid).isFalse()
        assertThat(result.reason).contains("hmac mismatch")
    }

    @Test
    fun `reordered records break chain linkage`() {
        val r1 = TamperEvidenceChain.append(hmac, null, TamperCategory.ROOT_DETECTED, 10L)
        val r2 = TamperEvidenceChain.append(hmac, r1, TamperCategory.XPOSE_DETECTED, 20L)
        val result = TamperEvidenceChain.verify(hmac, listOf(r2, r1))
        assertThat(result.valid).isFalse()
        assertThat(result.reason).contains("link mismatch")
    }

    @Test
    fun `deleted middle record breaks chain linkage`() {
        val r1 = TamperEvidenceChain.append(hmac, null, TamperCategory.ROOT_DETECTED, 10L)
        val r2 = TamperEvidenceChain.append(hmac, r1, TamperCategory.XPOSE_DETECTED, 20L)
        val r3 = TamperEvidenceChain.append(hmac, r2, TamperCategory.TEST_KEYS, 30L)
        // Attacker drops r2 (the middle): r3 still points at r2.hmac, so the
        // linkage from r1 no longer matches.
        val result = TamperEvidenceChain.verify(hmac, listOf(r1, r3))
        assertThat(result.valid).isFalse()
        assertThat(result.reason).contains("link mismatch")
    }

    @Test
    fun `signature and content hash are deterministic for identical input`() {
        val a = TamperEvidenceChain.append(hmac, null, TamperCategory.ROOT_DETECTED, 10L, mapOf("k" to "v"))
        val b = TamperEvidenceChain.append(hmac, null, TamperCategory.ROOT_DETECTED, 10L, mapOf("k" to "v"))
        assertThat(a.hmac).isEqualTo(b.hmac)
        assertThat(a.contentHash).isEqualTo(b.contentHash)
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `wrong hmac key fails verification`() {
        val r1 = TamperEvidenceChain.append(hmac, null, TamperCategory.ROOT_DETECTED, 10L)
        assertThat(TamperEvidenceChain.verify(hmac, listOf(r1)).valid).isTrue()
        assertThat(TamperEvidenceChain.verify(otherHmac, listOf(r1)).valid).isFalse()
    }
}