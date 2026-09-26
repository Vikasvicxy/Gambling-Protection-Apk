package dev.gamblock.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gamblock.core.designsystem.component.ShieldButton
import dev.gamblock.core.designsystem.component.ShieldCard
import dev.gamblock.core.designsystem.component.ShieldScaffold
import dev.gamblock.core.designsystem.component.ShieldText
import dev.gamblock.core.designsystem.theme.ShieldPalette
import dev.gamblock.core.model.RecoveryCalculator
import dev.gamblock.core.model.RecoveryCurrency
import dev.gamblock.core.model.UrgeTimerState
import dev.gamblock.data.preferences.CravingInsightsSnapshot
import java.util.Calendar

@Composable
fun RecoveryDashboardRoute(
    onBack: () -> Unit,
    onOpenUrgeSurfer: () -> Unit,
    onOpenJournal: () -> Unit,
    onOpenSupport: () -> Unit,
    onOpenIronShieldSettings: () -> Unit = {},
    viewModel: RecoveryViewModel = hiltViewModel(),
) {
    val metrics by viewModel.metrics.collectAsStateWithLifecycle()
    val insights by viewModel.insights.collectAsStateWithLifecycle()
    val privateDns by viewModel.privateDns.collectAsStateWithLifecycle()
    val promptDomain by viewModel.journalPromptDomain.collectAsStateWithLifecycle()

    var spendInput by remember { mutableLongStateOf(-1L) }
    var currency by remember { mutableStateOf<RecoveryCurrency?>(null) }
    var showJournalPrompt by remember { mutableStateOf(false) }

    ShieldScaffold(title = "Recovery dashboard", content = { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ShieldCard(title = "Clean streak") {
                    ShieldText(
                        text = if (metrics.daysClean > 0) "Protected for ${metrics.daysClean} day(s)" else "No streak yet",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    ShieldText(
                        text = "Estimated money saved: " +
                            RecoveryCalculator.formatMoney(metrics.moneySavedMinor, metrics.currency),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ShieldPalette.Green,
                    )
                    metrics.reachedMilestones.forEach { milestone ->
                        ShieldText(
                            text = "Reached: ${milestone.title}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    metrics.nextMilestone?.let { milestone ->
                        ShieldText(
                            text = "Next: ${milestone.title} in ${metrics.daysUntilNextMilestone} day(s)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    ShieldButton(
                        text = if (metrics.hasStartDate) "Restart streak today" else "Start streak today",
                        onClick = viewModel::startRecoveryNow,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item {
                ShieldCard(title = "Recovery financial profile") {
                    ShieldText(
                        text = "Stored only on this device. Used to estimate what your streak has saved.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    val activeCurrency = currency ?: metrics.currency
                    CurrencySelector(selected = activeCurrency, onSelect = { currency = it })
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = if (spendInput < 0L) "" else (spendInput / 100.0).toString(),
                        onValueChange = { raw ->
                            dev.gamblock.core.model.FinancialProfile.parseSpendInput(raw)?.let { parsed ->
                                spendInput = parsed
                                viewModel.setWeeklySpendMinor(parsed)
                            }
                        },
                        label = { Text("Weekly spend (${activeCurrency.symbol})") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    ShieldButton(
                        text = "Use ${activeCurrency.symbol} for estimates",
                        onClick = { viewModel.setCurrency(activeCurrency.symbol, activeCurrency.code) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (privateDns.active) {
                item {
                    ShieldCard(title = "Private DNS warning") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = "Warning",
                                tint = ShieldPalette.Orange,
                                modifier = Modifier.size(20.dp),
                            )
                            ShieldText(
                                text = privateDns.warningMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        ShieldButton(
                            text = "Open Android settings",
                            onClick = {
                                viewModel.openWirelessSettings()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            item {
                ShieldCard(title = "Craving insights") {
                    if (!insights.hasData) {
                    ShieldText(
                        text = "No urges logged yet.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    insights.peakWindowLabel?.let {
                        ShieldText(text = "Most urges occur $it", style = MaterialTheme.typography.bodyMedium)
                    }
                        insights.topTrigger?.let {
                            ShieldText(text = "Top trigger: $it", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    ShieldButton(
                        text = "Open craving journal",
                        onClick = onOpenJournal,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item {
                ShieldCard(title = "Tools") {
                    ShieldButton(text = "Urge Surfer (breathing)", onClick = onOpenUrgeSurfer, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    ShieldButton(text = "Get support", onClick = onOpenSupport, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    })

    if (showJournalPrompt || promptDomain != null) {
        CravingPromptDialog(
            domain = promptDomain,
            onLog = { viewModel.promptJournal(promptDomain); onOpenJournal() },
            onDismiss = viewModel::dismissJournalPrompt,
        )
    }
}

@Composable
fun CravingPromptDialog(domain: String?, onLog: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log this urge?") },
        text = {
            Text(
                text = domain?.let { "$it was blocked. Recording the moment helps you spot your pattern." }
                    ?: "Recording the moment helps you spot your pattern.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = { TextButton(onClick = onLog) { Text("Log urge") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

@Composable
private fun CurrencySelector(selected: RecoveryCurrency, onSelect: (RecoveryCurrency) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(RecoveryCurrency.entries.toList()) { currency ->
            androidx.compose.material3.FilterChip(
                selected = currency == selected,
                onClick = { onSelect(currency) },
                label = { Text("${currency.symbol} ${currency.code}") },
            )
        }
    }
}
