package dev.gamblock.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gamblock.core.designsystem.component.ShieldButton
import dev.gamblock.core.designsystem.component.ShieldText
import dev.gamblock.core.designsystem.theme.ShieldPalette
import dev.gamblock.core.model.GamblingAppScanner

/**
 * Shows the result of the offline gambling-app scan.
 *
 * The copy is the important part of this screen. Three separate things could
 * make a result look better than it is, and each one is refused here:
 *
 *  - It does not offer to delete or block anything. [GamblingAppScanner] is a
 *    heuristic over labels and package names; acting on it automatically would
 *    mean acting on a false positive, and deleting a child's app because a
 *    label contained a keyword is not a recoverable mistake.
 *  - An empty result never says the device is clear. It reports how many apps
 *    were visible, because that number is a subset of what is installed.
 *  - A hit never says "gambling app". It says the app matched known betting or
 *    casino wording, and shows the wording, so the parent can judge it.
 */
@Composable
fun AppRiskScannerPanel(
    scan: AppRiskScanUiState,
    onScan: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ShieldText(
            text = "App Risk Scan",
            style = MaterialTheme.typography.titleSmall,
        )
        ShieldText(
            text = "Looks through the apps Shield can see for known betting and casino " +
                "wording, entirely on this phone. Nothing is uploaded and nothing is " +
                "deleted; it is a list of things worth a look.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShieldButton(
                text = if (scan.scanning) "Scanning..." else "Scan apps",
                onClick = onScan,
                enabled = !scan.scanning,
            )
            if (scan.verdicts.isNotEmpty() || scan.error != null) {
                ShieldButton(text = "Clear", onClick = onDismiss)
            }
        }

        scan.error?.let { error ->
            ShieldText(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = ShieldPalette.Orange,
            )
        }

        if (!scan.scanning && scan.error == null && scan.scannedCount > 0) {
            ShieldText(
                text = if (scan.verdicts.isEmpty()) {
                    "Nothing matched in the ${scan.scannedCount} apps Shield could see."
                } else {
                    "${scan.verdicts.size} of ${scan.scannedCount} apps worth a look."
                },
                style = MaterialTheme.typography.bodySmall,
                color = ShieldPalette.Green,
            )
        }

        if (scan.partialCoverage && !scan.scanning && scan.scannedCount > 0) {
            ShieldText(
                text = "This is not every app on the phone. Android only reveals the apps " +
                    "Shield asks about, so an app Shield cannot see is not scanned.",
                style = MaterialTheme.typography.bodySmall,
                color = ShieldPalette.Orange,
            )
        }

        scan.verdicts.forEach { verdict ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ShieldText(
                    text = "${verdict.label} - ${verdict.risk.displayName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (verdict.risk) {
                        GamblingAppScanner.Risk.VERY_LIKELY -> ShieldPalette.Orange
                        GamblingAppScanner.Risk.LIKELY -> ShieldPalette.Orange
                        GamblingAppScanner.Risk.POSSIBLE -> MaterialTheme.colorScheme.onSurfaceVariant
                        GamblingAppScanner.Risk.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                ShieldText(
                    text = "Matched: ${verdict.evidence.joinToString { it.detail }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ShieldText(
                    text = verdict.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
