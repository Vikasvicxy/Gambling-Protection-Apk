package dev.gamblock.feature.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gamblock.core.designsystem.component.ShieldButton
import dev.gamblock.core.designsystem.component.ShieldScaffold
import dev.gamblock.core.designsystem.component.ShieldText
import dev.gamblock.core.designsystem.theme.ShieldPalette
import dev.gamblock.core.model.RecoveryCalculator

@Composable
fun IronShieldSettingsRoute(
    onBack: () -> Unit,
    onOpenSupport: () -> Unit,
    viewModel: RecoverySettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var urgeOpen by remember { mutableStateOf(false) }
    var fortressOpen by remember { mutableStateOf(false) }
    var defenseOpen by remember { mutableStateOf(false) }
    var wellnessOpen by remember { mutableStateOf(false) }
    var crisisOpen by remember { mutableStateOf(false) }
    var askingExportPassphrase by remember { mutableStateOf(false) }
    var askingRestorePassphrase by remember { mutableStateOf(false) }

    // The Storage Access Framework owns the destination. Shield never picks a path
    // and never keeps a handle to a file it did not write on request, so revoking
    // access in system settings is enough to cut it off.
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BACKUP_FILE_MIME_TYPE),
    ) { uri -> if (uri != null) viewModel.exportTo(uri) }
    val restorePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) { viewModel.stageRestoreUri(uri); askingRestorePassphrase = true } }

    ShieldScaffold(title = "Iron Shield settings", content = { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.statusMessage?.let { message ->
                ShieldText(text = message, style = MaterialTheme.typography.bodySmall, color = ShieldPalette.Green)
            }

            SettingsCategoryCard(
                title = "Urge & Relapse Prevention",
                summary = "Friction and locks that protect you in the moment you want to give up.",
                expanded = urgeOpen,
                onToggleExpanded = { urgeOpen = !urgeOpen },
            ) {
                FeatureToggleCard(
                    definition = FeatureToggleDefinition(
                        key = "urge_timer",
                        title = "15-Minute Cooling-Off Timer",
                        whatItDoes = "Turning protection off waits 15 minutes first, then asks you to type a short pledge.",
                        whyItHelps = "Most urges fade within minutes. Delaying the decision breaks the automatic bet-placing path.",
                        enabled = state.settings.urgeTimerEnabled,
                        onToggle = viewModel::setUrgeTimerEnabled,
                    ),
                    expanded = urgeOpen,
                    onToggleExpanded = {},
                )
                FeatureToggleCard(
                    definition = FeatureToggleDefinition(
                        key = "fortress",
                        title = "Scheduled Fortress Mode",
                        whatItDoes = "During the windows you choose, protection cannot be switched off at all.",
                        whyItHelps = "High-risk times (late nights, match days) are exactly when willpower is lowest. This removes the choice.",
                        enabled = state.fortressEnabled,
                        onToggle = viewModel::setFortressModeEnabled,
                        locked = state.fortress.lockedDown && !state.fortressEnabled,
                        lockNote = state.activeFortressWindowLabel?.let { "Active now: $it" },
                    ),
                    expanded = fortressOpen,
                    onToggleExpanded = {},
                )
                FortressWindowsEditor(
                    windows = state.fortressWindows,
                    fortressLocked = state.fortress.lockedDown,
                    onAddNightly = viewModel::addNightlyFortressWindow,
                    onAddWeekend = viewModel::addWeekendFortressWindow,
                    onRemove = viewModel::removeFortressWindow,
                )
                FeatureToggleCard(
                    definition = FeatureToggleDefinition(
                        key = "guardian_pin",
                        title = "Guardian Accountability PIN",
                        whatItDoes = "A 4-digit PIN you set is needed to disable protection, open these settings, whitelist domains, clear history, or change the recovery date.",
                        whyItHelps = "An impulsive decision is easy; a deliberate 4-digit entry is friction that works even when motivation is gone.",
                        enabled = state.guardianPinEnabled,
                        onToggle = viewModel::setGuardianPinEnabled,
                        locked = !state.guardianPinConfigured,
                        lockNote = if (state.guardianPinConfigured) null else "Set a PIN below to enable this",
                    ),
                    expanded = urgeOpen,
                    onToggleExpanded = {},
                )
                GuardianPinEditor(
                    configured = state.guardianPinConfigured,
                    enabled = state.settings.guardianPinEnabled,
                    onEnabledChanged = viewModel::setGuardianPinEnabled,
                    onSetPin = viewModel::setGuardianPin,
                    onClearPin = viewModel::clearGuardianPin,
                )
            }

            SettingsCategoryCard(
                title = "Network Defense & Anti-Bypass",
                summary = "Closes the known ways encrypted browsers dodge a DNS filter.",
                expanded = defenseOpen,
                onToggleExpanded = { defenseOpen = !defenseOpen },
            ) {
                FeatureToggleCard(
                    definition = FeatureToggleDefinition(
                        key = "quic",
                        title = "Block Encrypted Browser Bypasses (QUIC/DoH)",
                        whatItDoes = "Silently drops QUIC/HTTP-3 packets on UDP 443 and blocks well-known DNS-over-HTTPS hostnames, so browsers fall back to normal DNS that Shield can see.",
                        whyItHelps = "Encrypted DNS is the main way a gambling site slips past a domain filter. Closing it keeps every lookup in Shield's hands.",
                        enabled = state.settings.blockEncryptedBrowsers,
                        onToggle = viewModel::setBlockEncryptedBrowsers,
                    ),
                    expanded = defenseOpen,
                    onToggleExpanded = {},
                )
                FeatureToggleCard(
                    definition = FeatureToggleDefinition(
                        key = "private_dns",
                        title = "Private DNS Detection Alert",
                        whatItDoes = "Watches Android's Private DNS (DoT) setting and warns you on the dashboard when it is on, with a button to open the setting.",
                        whyItHelps = "Private DNS sends your lookups straight to your provider, skipping Shield entirely. You need to know when that happens.",
                        enabled = state.settings.privateDnsAlertEnabled,
                        onToggle = viewModel::setPrivateDnsAlertEnabled,
                    ),
                    expanded = defenseOpen,
                    onToggleExpanded = {},
                )
                ShieldText(
                    text = "Encrypted-bypass packets stopped: ${state.quicDrops}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.privateDns.active.let {
                    ShieldText(
                        text = state.privateDns.warningMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = ShieldPalette.Orange,
                    )
                }
            }

            SettingsCategoryCard(
                title = "Wellness & Progress",
                summary = "See what your streak is worth and share proof of your progress.",
                expanded = wellnessOpen,
                onToggleExpanded = { wellnessOpen = !wellnessOpen },
            ) {
                ShieldText(
                    text = "Recovery Financial Profile",
                    style = MaterialTheme.typography.titleSmall,
                )
                FinancialProfileEditor(
                    weeklySpendMinor = state.profile.weeklySpendMinor,
                    currency = state.profile.currency,
                    onSpendChanged = viewModel::setWeeklySpendMinor,
                    onCurrencyChanged = viewModel::setCurrency,
                    onStartStreak = viewModel::startStreakNow,
                    streakStarted = state.metrics.hasStartDate,
                )
                ShieldText(
                    text = "Days clean: ${state.metrics.daysClean}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                ShieldText(
                    text = "Estimated saved: " + RecoveryCalculator.formatMoney(
                        state.metrics.moneySavedMinor,
                        state.metrics.currency,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ShieldPalette.Green,
                )
                Spacer(Modifier.height(4.dp))
                ShieldButton(
                    text = "Generate Sobriety PDF Report",
                    onClick = viewModel::generateReport,
                    loading = state.reportBusy,
                    modifier = Modifier.fillMaxWidth(),
                )
                state.reportFile?.let { file ->
                    ShieldText(
                        text = "Report ready: ${file.name}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    ShieldButton(
                        text = "Share report",
                        onClick = {
                            runCatching {
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file,
                                )
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/pdf"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(
                                    Intent.createChooser(intent, "Share recovery report"),
                                )
                            }.onFailure {
                                viewModel.clearStatus()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(16.dp))
                BackupRestoreEditor(
                    busy = state.backupBusy,
                    onExportRequested = { askingExportPassphrase = true },
                    onRestoreFileChosen = { restorePicker.launch(BACKUP_MIME_TYPES) },
                )
            }

            SettingsCategoryCard(
                title = "Crisis & Emergency Directory",
                summary = "One-tap access to verified helplines. Numbers are stored on-device.",
                expanded = crisisOpen,
                onToggleExpanded = { crisisOpen = !crisisOpen },
            ) {
                ShieldText(
                    text = "Open the full offline directory with India, US/Canada, UK, and international support.",
                    style = MaterialTheme.typography.bodySmall,
                )
                ShieldButton(
                    text = "National Crisis & Helpline Directory",
                    onClick = onOpenSupport,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    })

    if (askingExportPassphrase) {
        BackupPassphraseDialog(
            title = "Choose a backup passphrase",
            explainer = "This passphrase is the only way to open the file later. Shield cannot " +
                "recover it for you, so pick something you will remember, and do not reuse " +
                "your Guardian PIN.",
            confirmable = true,
            onConfirm = { passphrase ->
                askingExportPassphrase = false
                viewModel.stageExportPassphrase(passphrase)
                exportPicker.launch(BACKUP_FILE_NAME)
            },
            onDismiss = { askingExportPassphrase = false },
        )
    }

    if (askingRestorePassphrase) {
        BackupPassphraseDialog(
            title = "Open your backup",
            explainer = "Enter the passphrase you used when you made this backup.",
            confirmable = false,
            onConfirm = { passphrase ->
                askingRestorePassphrase = false
                viewModel.openRestore(passphrase)
            },
            onDismiss = {
                askingRestorePassphrase = false
                viewModel.dismissRestorePreview()
            },
        )
    }

    state.pendingRestore?.let { preview ->
        RestoreConfirmationDialog(
            preview = preview,
            onConfirm = viewModel::confirmRestore,
            onDismiss = viewModel::dismissRestorePreview,
        )
    }
}

/** Encrypted backups are opaque bytes, so the generic type is the honest one. */
private const val BACKUP_FILE_MIME_TYPE = "application/octet-stream"

private const val BACKUP_FILE_NAME = "shield-recovery-backup.shld"

/**
 * Offered on restore. The file itself carries the `SHLDBAK1` magic and a format
 * version, so the filter is a convenience for the user rather than a check: a file
 * that slips through is still rejected as a bad format.
 */
private val BACKUP_MIME_TYPES = arrayOf(BACKUP_FILE_MIME_TYPE, "*/*")
