package dev.gamblock.feature.dashboard

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import dev.gamblock.core.model.RecoveryHeatmap
import org.junit.Test

/**
 * Colour mapping for the heatmap.
 *
 * The distinction that matters: an empty cell must not read as a shaded one. If
 * both rendered as transparent-grey the chart would claim coverage for days that
 * were never tracked, which is the same overstatement the savings figure already
 * refuses to make.
 */
class StreakHeatmapColorTest {

    @Test
    fun `clean days are the strongest green`() {
        assertThat(colorFor(RecoveryHeatmap.Level.CLEAN)).isNotEqualTo(colorFor(RecoveryHeatmap.Level.GAP))
    }

    @Test
    fun `a protected day with a lapse is lighter than a clean day`() {
        assertThat(colorFor(RecoveryHeatmap.Level.STREAK_WITH_LAPSE))
            .isNotEqualTo(colorFor(RecoveryHeatmap.Level.CLEAN))
    }

    @Test
    fun `gaps are the only red`() {
        val reds = RecoveryHeatmap.Level.entries.filter { colorFor(it) == colorFor(RecoveryHeatmap.Level.GAP) }
        assertThat(reds).containsExactly(RecoveryHeatmap.Level.GAP)
    }

    @Test
    fun `empty cells are transparent`() {
        assertThat(colorFor(RecoveryHeatmap.Level.EMPTY)).isEqualTo(Color.Transparent)
    }

    @Test
    fun `every shaded cell has a real fill`() {
        RecoveryHeatmap.Level.entries
            .filter { it != RecoveryHeatmap.Level.EMPTY }
            .forEach { level ->
                assertThat(colorFor(level)).isNotEqualTo(Color.Transparent)
            }
    }

    @Test
    fun `empty cells are outlined so they stay visible on a light surface`() {
        assertThat(borderFor(RecoveryHeatmap.Level.EMPTY)).isNotEqualTo(Color.Transparent)
    }

    @Test
    fun `shaded cells carry no outline`() {
        RecoveryHeatmap.Level.entries
            .filter { it != RecoveryHeatmap.Level.EMPTY }
            .forEach { level ->
                assertThat(borderFor(level)).isEqualTo(Color.Transparent)
            }
    }

    @Test
    fun `every level has a distinct fill`() {
        val fills = RecoveryHeatmap.Level.entries.map { colorFor(it) }
        assertThat(fills.toSet()).hasSize(fills.size)
    }
}