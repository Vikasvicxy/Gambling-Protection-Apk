package dev.gamblock.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.gamblock.core.designsystem.component.ShieldButton
import dev.gamblock.core.designsystem.component.ShieldText
import dev.gamblock.core.designsystem.theme.ShieldPalette
import dev.gamblock.core.model.FinancialProfile
import dev.gamblock.core.model.FortressWindow
import dev.gamblock.core.model.RecoveryCalculator
import dev.gamblock.core.model.RecoveryCurrency
import dev.gamblock.core.model.WeekDay

data class FeatureToggleDefinition(
    val key: String,
    val title: String,
    val whatItDoes: String,
    val whyItHelps: String,
    val enabled: Boolean,
    val onToggle: (Boolean) -> Unit,
    val locked: Boolean = false,
    val lockNote: String? = null,
)

@Composable
fun FeatureToggleCard(
    definition: FeatureToggleDefinition,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { onToggleExpanded() },
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(definition.title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = definition.whatItDoes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = definition.enabled,
                    onCheckedChange = definition.onToggle,
                    enabled = !definition.locked,
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    ShieldText(
                        text = "Why it helps: ${definition.whyItHelps}",
                        style = MaterialTheme.typography.bodySmall,
                        color = ShieldPalette.Blue,
                    )
                    if (definition.locked && definition.lockNote != null) {
                        Spacer(Modifier.height(6.dp))
                        ShieldText(
                            text = definition.lockNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = ShieldPalette.Orange,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsCategoryCard(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { onToggleExpanded() },
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
fun FinancialProfileEditor(
    weeklySpendMinor: Long,
    currency: RecoveryCurrency,
    onSpendChanged: (Long) -> Unit,
    onCurrencyChanged: (RecoveryCurrency) -> Unit,
    onStartStreak: () -> Unit,
    streakStarted: Boolean,
) {
    Column {
        ShieldText(
            text = "Weekly gambling spend, used only to estimate what your clean streak has saved.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RecoveryCurrency.entries.forEach { option ->
                FilterChip(
                    selected = option == currency,
                    onClick = { onCurrencyChanged(option) },
                    label = { Text("${option.symbol} ${option.code}") },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        var spendText by rememberSaveable(weeklySpendMinor, currency) {
            mutableStateOf(if (weeklySpendMinor <= 0L) "" else (weeklySpendMinor / 100.0).toString())
        }
        OutlinedTextField(
            value = spendText,
            onValueChange = { raw ->
                spendText = raw
                FinancialProfile.parseSpendInput(raw)?.let(onSpendChanged)
            },
            label = { Text("Weekly spend (${currency.symbol})") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        ShieldButton(
            text = if (streakStarted) "Restart streak today" else "Start clean streak today",
            onClick = onStartStreak,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun FortressWindowsEditor(
    windows: List<FortressWindow>,
    fortressLocked: Boolean,
    onAddNightly: () -> Unit,
    onAddWeekend: () -> Unit,
    onRemove: (String) -> Unit,
) {
    Column {
        ShieldText(
            text = "During a Fortress window protection stays on and cannot be switched off.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (fortressLocked) {
            Spacer(Modifier.height(6.dp))
            ShieldText(
                text = "A Fortress window is active right now.",
                style = MaterialTheme.typography.bodySmall,
                color = ShieldPalette.Orange,
            )
        }
        Spacer(Modifier.height(8.dp))
        windows.forEach { window ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = window.label.ifBlank { "Fortress window" },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "${window.daysLabel} - ${window.timeRangeLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onRemove(window.id) }) { Text("Remove") }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShieldButton(text = "Nightly 11 PM-5 AM", onClick = onAddNightly, modifier = Modifier.weight(1f))
            ShieldButton(text = "Weekend Fri-Mon", onClick = onAddWeekend, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun GuardianPinEditor(
    configured: Boolean,
    enabled: Boolean,
    onEnabledChanged: (Boolean) -> Unit,
    onSetPin: (String) -> Unit,
    onClearPin: () -> Unit,
) {
    var dialogVisible by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column {
        ShieldText(
            text = "A 4-digit PIN required to disable protection, change settings, whitelist domains, clear history, or change the recovery date. It is separate from your phone lock and is stored only as a salted hash.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Guardian PIN", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = if (configured) "PIN is set" else "No PIN set",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChanged)
        }
        Spacer(Modifier.height(8.dp))
        if (configured) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShieldButton(text = "Change PIN", onClick = { dialogVisible = true }, modifier = Modifier.weight(1f))
                ShieldButton(text = "Remove PIN", onClick = onClearPin, modifier = Modifier.weight(1f))
            }
        } else {
            ShieldButton(text = "Set guardian PIN", onClick = { dialogVisible = true }, modifier = Modifier.fillMaxWidth())
        }
    }

    if (dialogVisible) {
        AlertDialog(
            onDismissRequest = { dialogVisible = false },
            title = { Text("Set guardian PIN") },
            text = {
                Column {
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it.filter(Char::isDigit).take(4) },
                        label = { Text("4-digit PIN") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it.filter(Char::isDigit).take(4) },
                        label = { Text("Confirm PIN") },
                        singleLine = true,
                    )
                    error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = ShieldPalette.Red, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        when {
                            pin.length != 4 -> error = "PIN must be exactly 4 digits"
                            pin != confirm -> error = "PINs do not match"
                            else -> {
                                onSetPin(pin)
                                dialogVisible = false
                                pin = ""
                                confirm = ""
                                error = null
                            }
                        }
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { dialogVisible = false }) { Text("Cancel") } },
        )
    }
}

/**
 * Encrypted, offline backup of the user's recovery data.
 *
 * The copy here is deliberately plain about what the file is and is not: people
 * are about to hand their private journal to a file on a memory card, and vague
 * reassurance is worse than none.
 */
@Composable
fun BackupRestoreEditor(
    busy: Boolean,
    onExportRequested: () -> Unit,
    onRestoreFileChosen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        ShieldText(
            text = "Backup & Restore",
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(4.dp))
        ShieldText(
            text = "Creates an encrypted file with your clean streak, journal entries and custom " +
                "settings. Only you have the key.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        ShieldText(
            text = "The file is scrambled with your passphrase before it ever touches storage, so " +
                "nobody can read it without it, and Shield has no copy of it anywhere. " +
                "There is no account and no cloud sync: the file only goes where you send it. " +
                "Your Guardian PIN is never written into a backup, so keep the passphrase safe " +
                "yourself. If you lose it, the file cannot be opened by anyone, including us.",
            style = MaterialTheme.typography.bodySmall,
            color = ShieldPalette.Blue,
        )
        Spacer(Modifier.height(10.dp))
        ShieldButton(
            text = "Backup Recovery Data",
            onClick = onExportRequested,
            enabled = !busy,
            loading = busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        ShieldButton(
            text = "Restore Recovery Data",
            onClick = onRestoreFileChosen,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Asks for the backup passphrase. Used for both directions: for an export the
 * user confirms it, for a restore they only repeat it.
 *
 * [confirmable] shows the second field. The value is handed to the caller as a
 * CharArray so it can be zeroed as soon as the write finishes.
 */
@Composable
fun BackupPassphraseDialog(
    title: String,
    explainer: String,
    confirmable: Boolean,
    onConfirm: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                ShieldText(text = explainer, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it.take(MAX_PASSPHRASE) },
                    label = { Text("Passphrase") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                if (confirmable) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it.take(MAX_PASSPHRASE) },
                        label = { Text("Confirm passphrase") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = ShieldPalette.Red, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when {
                        passphrase.length < MIN_PASSPHRASE ->
                            error = "Use at least $MIN_PASSPHRASE characters. " +
                                "A short or all-numbers key can be guessed."
                        confirmable && passphrase != confirmation ->
                            error = "The two passphrases do not match"
                        else -> {
                            onConfirm(passphrase.toCharArray())
                            passphrase = ""
                            confirmation = ""
                            error = null
                        }
                    }
                },
            ) { Text(if (confirmable) "Save" else "Open backup") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Shown after a backup has been opened and validated but before anything is
 * replaced, so the user can see exactly what they are about to get back.
 */
@Composable
fun RestoreConfirmationDialog(
    preview: BackupRestorePreview,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Replace your recovery data?") },
        text = {
            Column {
                ShieldText(
                    text = "This backup contains:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(6.dp))
                previewLine("Clean streak", "${preview.daysClean} days")
                previewLine("Journal entries", "${preview.journalEntries}")
                previewLine("Custom exceptions", "${preview.customExceptions}")
                previewLine("Fortress windows", "${preview.fortressWindows}")
                previewLine(
                    "Weekly amount",
                    RecoveryCalculator.formatMoney(preview.weeklySpendMinor, preview.currency),
                )
                if (preview.milestonesReached > 0) {
                    previewLine("Milestones reached", "${preview.milestonesReached}")
                }
                Spacer(Modifier.height(10.dp))
                ShieldText(
                    text = "Restoring replaces the recovery data on this phone. Your Guardian PIN " +
                        "is not in the file, so you will need to set a new one here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ShieldPalette.Orange,
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Restore") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun previewLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, color = ShieldPalette.Green)
    }
}

private const val MIN_PASSPHRASE = 8
private const val MAX_PASSPHRASE = 128
