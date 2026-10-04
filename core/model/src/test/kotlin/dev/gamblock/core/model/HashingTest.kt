package dev.gamblock.core.model.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HashingTest {

    @Test
    fun `stableHash is deterministic`() {
        assertThat(stableHash("shield")).isEqualTo(stableHash("shield"))
    }

    @Test
    fun `stableHash separates different inputs`() {
        assertThat(stableHash("a.example")).isNotEqualTo(stableHash("b.example"))
    }

    @Test
    fun `stableHash is case sensitive`() {
        assertThat(stableHash("Shield")).isNotEqualTo(stableHash("shield"))
    }

    @Test
    fun `stableHash of an empty string is the seed`() {
        assertThat(stableHash("")).isEqualTo(1125899906842597L)
    }

    @Test
    fun `sha256Hex matches the known empty digest`() {
        assertThat(sha256Hex(ByteArray(0)))
            .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    }

    @Test
    fun `sha256Hex matches the known abc digest`() {
        assertThat(sha256Hex("abc".toByteArray()))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    @Test
    fun `sha256Hex is lowercase hex of fixed width`() {
        val digest = sha256Hex("shield".toByteArray())

        assertThat(digest).hasLength(64)
        assertThat(digest).matches("[0-9a-f]{64}")
    }

    @Test
    fun `sha256Hex differs for a single bit flip`() {
        assertThat(sha256Hex(byteArrayOf(0x00))).isNotEqualTo(sha256Hex(byteArrayOf(0x01)))
    }
}

class RandomIdTest {

    @Test
    fun `randomId128 is 32 hex characters`() {
        assertThat(randomId128()).matches("[0-9a-f]{32}")
    }

    @Test
    fun `randomId128 does not repeat`() {
        val ids = (1..500).map { randomId128() }

        assertThat(ids.toSet()).hasSize(500)
    }
}

class FormatDurationMillisTest {

    private fun format(millis: Long) = formatDurationMillis(millis)

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    @Test
    fun `formats sub-minute durations as minutes`() {
        assertThat(format(0)).isEqualTo("0 min")
        assertThat(format(45 * 1_000L)).isEqualTo("0 min")
    }

    @Test
    fun `formats minutes`() {
        assertThat(format(minute)).isEqualTo("1 min")
        assertThat(format(59 * minute)).isEqualTo("59 min")
    }

    @Test
    fun `formats a single hour`() {
        assertThat(format(hour)).isEqualTo("1 hour")
    }

    @Test
    fun `formats hours with minutes`() {
        assertThat(format(2 * hour + 30 * minute)).isEqualTo("2 h 30 m")
    }

    @Test
    fun `formats a single day`() {
        assertThat(format(day)).isEqualTo("1 day")
    }

    @Test
    fun `formats days with hours`() {
        assertThat(format(2 * day + 5 * hour)).isEqualTo("2 d 5 h")
    }

    @Test
    fun `formats whole days without a stray hour`() {
        assertThat(format(3 * day)).isEqualTo("3 days")
    }

    @Test
    fun `formats whole weeks`() {
        assertThat(format(7 * day)).isEqualTo("1 week(s)")
        assertThat(format(14 * day)).isEqualTo("2 week(s)")
    }

    @Test
    fun `formats weeks with remaining days`() {
        assertThat(format(10 * day)).isEqualTo("1 wk 3d")
    }

    @Test
    fun `formats months at thirty days`() {
        assertThat(format(30 * day)).isEqualTo("1 month(s)")
        assertThat(format(90 * day)).isEqualTo("3 month(s)")
    }

    @Test
    fun `formats a negative duration as a dash`() {
        assertThat(format(-1)).isEqualTo("-")
        assertThat(format(-day)).isEqualTo("-")
    }

    @Test
    fun `formats the sentinel maximum as forever`() {
        assertThat(format(Long.MAX_VALUE)).isEqualTo("forever")
    }

    @Test
    fun `never emits an empty string`() {
        listOf(0L, 1L, minute, hour, day, 7 * day, 30 * day, Long.MAX_VALUE, -5L).forEach {
            assertThat(format(it)).isNotEmpty()
        }
    }
}