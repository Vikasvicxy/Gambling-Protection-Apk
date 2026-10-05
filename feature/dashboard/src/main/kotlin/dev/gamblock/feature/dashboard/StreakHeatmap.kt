package dev.gamblock.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.gamblock.core.designsystem.component.ShieldCard
import dev.gamblock.core.designsystem.component.ShieldText
import dev.gamblock.core.designsystem.theme.ShieldPalette
import dev.gamblock.core.model.RecoveryHeatmap

/**
 * A 90-day recovery heatmap, one cell per day, laid out as whole weeks.
 *
 * The colours are not decorative. [RecoveryHeatmap.Level.GAP] is a day inside the
 * streak where Shield was not observed running, and it is rendered red on purpose:
 * a recovery chart that shades every day green would read as perfect adherence no
 * matter what actually happened, which is exactly the kind of flattering fiction
 * this app exists to stop telling users.
 *
 * No Compose preview is provided because the grid's correctness is in its calendar
 * arithmetic, which `RecoveryHeatmapTest` covers directly.
 */
@Composable
fun StreakHeatmap(
    weeks: List<List<RecoveryHeatmap.Level>>,
    modifier: Modifier = Modifier,
) {
    ShieldCard(
        title = "Last 90 days",
        modifier = modifier,
    ) {
        ShieldText(
            text = "Each square is one day. Red means Shield was not running, which is " +
                "counted against coverage rather than quietly ignored.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(12.dp))
        Row(modifier = Modifier.semantics { contentDescription = summaryDescription(weeks) }) {
            weeks.forEach { week ->
                Column(
                    modifier = Modifier.width(CELL_SIZE + CELL_GAP),
                    verticalArrangement = Arrangement.spacedBy(CELL_GAP),
                ) {
                    week.forEach { level ->
                        Box(
                            modifier = Modifier
                                .size(CELL_SIZE)
                                .background(colorFor(level), RoundedCornerShape(2.dp))
                                // A hairline border keeps an empty cell visible against
                                // a light surface, where it would otherwise disappear.
                                .border(1.dp, borderFor(level), RoundedCornerShape(2.dp))
                                .semantics { contentDescription = labelFor(level) },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.size(12.dp))
        Legend()
    }
}

@Composable
private fun Legend() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LegendSwatch(RecoveryHeatmap.Level.EMPTY, "Before you started")
        LegendSwatch(RecoveryHeatmap.Level.GAP, "Not protected")
        LegendSwatch(RecoveryHeatmap.Level.STREAK_WITH_LAPSE, "Urge logged")
        LegendSwatch(RecoveryHeatmap.Level.CLEAN, "Clean")
    }
}

@Composable
private fun LegendSwatch(level: RecoveryHeatmap.Level, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(LEGEND_SWATCH)
                .background(colorFor(level), RoundedCornerShape(2.dp))
                .border(1.dp, borderFor(level), RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(4.dp))
        ShieldText(text = label, style = MaterialTheme.typography.bodySmall)
    }
}

/** The fill for a cell. Kept as a plain function so it can be asserted in tests. */
fun colorFor(level: RecoveryHeatmap.Level): Color = when (level) {
    RecoveryHeatmap.Level.EMPTY -> Color.Transparent
    RecoveryHeatmap.Level.GAP -> ShieldPalette.RedLight
    RecoveryHeatmap.Level.STREAK_WITH_LAPSE -> ShieldPalette.GreenLight
    RecoveryHeatmap.Level.CLEAN -> ShieldPalette.Green
}

/** The outline, so empty cells stay visible without being mistaken for shaded ones. */
fun borderFor(level: RecoveryHeatmap.Level): Color = when (level) {
    RecoveryHeatmap.Level.EMPTY -> ShieldPalette.Gray300
    else -> Color.Transparent
}

private fun labelFor(level: RecoveryHeatmap.Level): String = when (level) {
    RecoveryHeatmap.Level.EMPTY -> "No data"
    RecoveryHeatmap.Level.GAP -> "Not protected"
    RecoveryHeatmap.Level.STREAK_WITH_LAPSE -> "Protected, urge logged"
    RecoveryHeatmap.Level.CLEAN -> "Clean"
}

/**
 * A spoken summary, since a 630-cell grid is meaningless to a screen reader on its
 * own. Counts are given rather than a per-cell reading.
 */
private fun summaryDescription(weeks: List<List<RecoveryHeatmap.Level>>): String {
    val counts = RecoveryHeatmap.summarise(weeks.flatten())
    val clean = counts[RecoveryHeatmap.Level.CLEAN] ?: 0
    val lapse = counts[RecoveryHeatmap.Level.STREAK_WITH_LAPSE] ?: 0
    val gap = counts[RecoveryHeatmap.Level.GAP] ?: 0
    return "90 day recovery chart. $clean clean days, $lapse days with a logged urge, " +
        "$gap days not protected."
}

private val CELL_SIZE = 14.dp
private val CELL_GAP = 3.dp
private val LEGEND_SWATCH = 12.dp