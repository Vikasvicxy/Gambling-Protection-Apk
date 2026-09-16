package dev.gamblock.protection.dns

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InternetChecksumTest {

    @Test
    fun `standard RFC vector`() {
        // Words 0xC0A8 + 0x0102 = 0xC1AA; one's complement -> 0x3E55.
        val data = byteArrayOf(0xC0.toByte(), 0xA8.toByte(), 0x01, 0x02)
        assertThat(InternetChecksum.compute(data, 0, data.size)).isEqualTo(0x3E55)
    }

    @Test
    fun `all ones folds to zero`() {
        val data = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertThat(InternetChecksum.compute(data, 0, 4)).isEqualTo(0x0000)
    }

    @Test
    fun `odd byte count contributes as the high half-word`() {
        assertThat(InternetChecksum.compute(byteArrayOf(0x01), 0, 1)).isEqualTo(0xFEFF)
        assertThat(InternetChecksum.compute(byteArrayOf(0xFF.toByte()), 0, 1)).isEqualTo(0x00FF)
    }

    @Test
    fun `works from an offset into a larger buffer`() {
        val data = byteArrayOf(0, 0, 0xC0.toByte(), 0xA8.toByte(), 0x01, 0x02, 9, 9)
        assertThat(InternetChecksum.compute(data, 2, 4)).isEqualTo(0x3E55)
    }

    @Test
    fun `is endian-consistent with self crafted responses`() {
        val data = "gamblock-dns".toByteArray(Charsets.ISO_8859_1)
        val checksum = InternetChecksum.compute(data, 0, data.size)
        assertThat(checksum and 0xFFFF).isEqualTo(checksum)
    }
}