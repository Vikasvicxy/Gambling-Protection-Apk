package dev.gamblock.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gamblock.core.designsystem.component.ShieldButton
import dev.gamblock.core.designsystem.component.ShieldCard
import dev.gamblock.core.designsystem.component.ShieldScaffold
import dev.gamblock.core.designsystem.component.ShieldText
import dev.gamblock.core.designsystem.theme.ShieldPalette
import dev.gamblock.core.model.CravingTrigger
import dev.gamblock.data.preferences.UrgeJournalEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun CravingJournalRoute(
    onBack: () -> Unit,
    viewModel: RecoveryViewModel = hiltViewModel(),
) {
    val insights by viewModel.insights.collectAsStateWithLifecycle()
    val metrics by viewModel.metrics.collectAsStateWithLifecycle()
    val promptDomain by viewModel.journalPromptDomain.collectAsStateWithLifecycle()
    var logDialog by remember { mutableStateOf(false) }

    ShieldScaffold(title = "Craving journal", content = { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ShieldCard(title = "Your pattern") {
                    if (!insights.hasData) {
                        ShieldText(
                            text = "Log an urge after a domain is blocked and Shield will show when your urges peak.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        insights.peakWindowLabel?.let { peak ->
                            ShieldText(
                                text = "Most urges occur $peak",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        insights.topTrigger?.let { trigger ->
                            ShieldText(
                                text = "Most common trigger: $trigger",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        ShieldText(
                            text = "Logged ${insights.totalEntries} urges, average intensity " +
                                String.format("%.1f", insights.averageIntensity) + "/5",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    ShieldButton(text = "Log an urge", onClick = { logDialog = true }, modifier = Modifier.fillMaxWidth())
                }
            }
            item {
                ShieldCard(title = "Clean streak") {
                    ShieldText(
                        text = if (metrics.daysClean > 0) {
                            "Protected for ${metrics.daysClean} day(s)"
                        } else {
                            "Set your recovery start date to begin counting."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (insights.entries.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ShieldText(
                            text = "Recent entries",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = viewModel::clearJournal) { Text("Clear all") }
                    }
                }
                items(insights.entries, key = { it.id }) { entry ->
                    CravingEntryRow(entry = entry, onDelete = { viewModel.deleteJournalEntry(entry.id) })
                }
            }
        }
    })

    if (logDialog) {
        LogUrgeDialog(
            domain = promptDomain,
            onDismiss = {
                logDialog = false
                viewModel.dismissJournalPrompt()
            },
            onSave = { intensity, triggers, note ->
                viewModel.logUrge(intensity, triggers, note) { logDialog = false }
            },
        )
    }
}

@Composable
private fun CravingEntryRow(entry: UrgeJournalEntry, onDelete: () -> Unit) {
    ShieldCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                ShieldText(
                    text = entry.triggers.joinToString(", ").ifBlank { "No trigger picked" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                ShieldText(
                    text = "Intensity ${entry.intensity}/5 - " + formatTimestamp(entry.occurredAtEpochMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                entry.blockedDomain?.let { domain ->
                    ShieldText(
                        text = domain,
                        style = MaterialTheme.typography.bodySmall,
                        color = ShieldPalette.Orange,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (entry.note.isNotBlank()) {
                    ShieldText(text = entry.note, style = MaterialTheme.typography.bodySmall)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Close, contentDescription = "Delete entry", modifier = Modifier.padding(2.dp))
            }
        }
    }
}

@Composable
private fun LogUrgeDialog(
    domain: String?,
    onDismiss: () -> Unit,
    onSave: (Int, Set<CravingTrigger>, String) -> Unit,
) {
    var intensity by remember { mutableIntStateOf(3) }
    var note by remember { mutableStateOf("") }
    val selected = remember { mutableStateOf(setOf<CravingTrigger>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log this urge") },
        text = {
            Column {
                domain?.let {
                    Text("Blocked domain: $it", style = MaterialTheme.typography.bodySmall)
                }
                Text("How strong is the urge?", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = intensity.toFloat(),
                    onValueChange = { intensity = it.toInt() },
                    valueRange = 1f..5f,
                    steps = 3,
                )
                Text("Triggers", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CravingTrigger.entries.forEach { trigger ->
                        FilterChip(
                            selected = trigger in selected.value,
                            onClick = {
                                selected.value = if (trigger in selected.value) {
                                    selected.value - trigger
                                } else {
                                    selected.value + trigger
                                }
                            },
                            label = { Text(trigger.displayName) },
                        )
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(280) },
                    label = { Text("Note (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(intensity, selected.value, note) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun formatTimestamp(epochMs: Long): String =
    DateTimeFormatter.ofPattern("d MMM HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMs))
