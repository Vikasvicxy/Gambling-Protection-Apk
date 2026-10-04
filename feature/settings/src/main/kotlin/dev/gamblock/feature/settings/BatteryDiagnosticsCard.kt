package dev.gamblock.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Everything the diagnostics card needs to render, expressed as plain data.
 *
 * Keeping this separate from the composable is what lets the wording and the branching be tested
 * without a device, and it means the screen never touches PowerManager or the OEM tables itself.
 */
data class BatteryDiagnosticsUi(
    val vendorLabel: String,
    val isExempt: Boolean,
    val needsManualAutostartStep: Boolean,
    /** False when no OEM screen resolves, in which case the button hides rather than lying. */
    val canOpenBatterySettings: Boolean,
    /** True when there is nothing wrong, so the reassuring state can be shown. */
    val isAllClear: Boolean,
)

/**
 * Derives the card's state from the two facts that are actually observable.
 *
 * Separated out so the branching can be tested without a device or an OEM skin, and so the
 * definition of "everything is fine" lives in one place instead of being re-derived per screen.
 *
 * [canOpenBatterySettings] being false is not treated as all-clear: if no settings screen resolves
 * and the app is not exempt, the user still has something to do, and a hidden button must not read
 * as "nothing to see here".
 */
fun batteryDiagnosticsState(
    vendorLabel: String,
    isExempt: Boolean,
    needsManualAutostartStep: Boolean,
    canOpenBatterySettings: Boolean,
): BatteryDiagnosticsUi = BatteryDiagnosticsUi(
    vendorLabel = vendorLabel,
    isExempt = isExempt,
    needsManualAutostartStep = needsManualAutostartStep,
    canOpenBatterySettings = canOpenBatterySettings,
    isAllClear = isExempt && !needsManualAutostartStep,
)

/**
 * Battery-optimisation status, with a route to fix it when there is something to fix.
 *
 * This exists because the failure it reports is invisible otherwise. When an OEM skin kills the
 * tunnel service in the background, nothing on the dashboard changes: the app looks fine and has
 * simply stopped protecting anyone. Surfacing the exemption state turns that silent failure into
 * something the user can act on, which is the only honest option available - the alternative is
 * claiming protection is active when the OS may already have killed it.
 *
 * The copy deliberately does not claim the app *is* protected on stock devices just because an
 * intent resolves. It reports one verifiable fact, the exemption, and tells the user what to do
 * when it is missing.
 */
@Composable
fun BatteryDiagnosticsCard(
    state: BatteryDiagnosticsUi,
    onOpenBatterySettings: () -> Unit,
    onOpenGenericList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().testTag("batteryDiagnosticsCard"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (state.isAllClear) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Background reliability",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Device: ${state.vendorLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = when {
                    state.isExempt ->
                        "Shield is exempt from battery optimisation. Android may restrict it in the " +
                            "background, and ${state.vendorLabel} devices can additionally block " +
                            "auto-start."
                    else ->
                        "Shield is NOT exempt from battery optimisation, so ${state.vendorLabel} may " +
                            "stop protection when the app is in the background."
                },
                style = MaterialTheme.typography.bodyMedium,
            )

            if (state.needsManualAutostartStep) {
                Text(
                    text = "${state.vendorLabel} also has a separate auto-start list with no public " +
                        "API. If protection stops after you switch away from Shield, allow " +
                        "auto-start for Shield in that list.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.canOpenBatterySettings) {
                    OutlinedButton(
                        onClick = onOpenBatterySettings,
                        modifier = Modifier.testTag("openBatterySettings"),
                    ) {
                        Text("Open battery settings")
                    }
                }
                TextButton(
                    onClick = onOpenGenericList,
                    modifier = Modifier.testTag("openBatteryList"),
                ) {
                    Text("Exemption list")
                }
            }
        }
    }
}