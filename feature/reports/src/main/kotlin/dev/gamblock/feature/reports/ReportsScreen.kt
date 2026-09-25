package dev.gamblock.feature.reports

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ReportGmailerrorred
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gamblock.core.designsystem.component.ShieldCard
import dev.gamblock.core.designsystem.component.ShieldEmptyState
import dev.gamblock.core.designsystem.component.ShieldScaffold
import dev.gamblock.core.designsystem.component.ShieldText
import dev.gamblock.core.designsystem.theme.ShieldPalette
import dev.gamblock.core.model.ActivityEvent
import dev.gamblock.core.model.BlockAttemptGroup
import dev.gamblock.core.model.FalsePositiveReport
import dev.gamblock.protection.tamper.LockscreenAuthOutcome
import dev.gamblock.protection.tamper.LockscreenAuthPolicy

@Composable
fun ReportsRoute(
    onBack: () -> Unit,
    viewModel: ReportsViewModel = hiltViewModel(),
) {
    val recentBlocked by viewModel.recentBlocked.collectAsStateWithLifecycle()
    val totalAttempts by viewModel.totalAttempts.collectAsStateWithLifecycle()
    val activity by viewModel.activity.collectAsStateWithLifecycle(initialValue = emptyList())
    val submitted by viewModel.submittedReports.collectAsStateWithLifecycle(initialValue = emptyList())
    val gateMessage by viewModel.gateMessage.collectAsStateWithLifecycle()

    var pendingClear by remember { mutableStateOf(false) }
    val authLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (pendingClear) {
            pendingClear = false
            val outcome = LockscreenAuthPolicy.onPromptResult(
                requireAuth = true,
                deviceProtected = viewModel.lockscreenGate.isDeviceProtected(),
                granted = result.resultCode == Activity.RESULT_OK,
            )
            if (outcome == LockscreenAuthOutcome.ALLOW) viewModel.clearHistory()
        }
    }

    fun handleClearHistory() {
        if (viewModel.requireAuthBeforeClearHistory.value) {
            val gate = viewModel.lockscreenGate
            if (gate.isDeviceProtected()) {
                pendingClear = true
                val intent = gate.confirmIntent(
                    "Clear blocked history",
                    "Enter your device PIN, pattern, or biometric to permanently clear history.",
                )
                if (intent != null) authLauncher.launch(intent)
            } else {
                viewModel.clearGateMessage()
                viewModel.showGateMessage(
                    "History lock is on but no device lock is set. Set a device lock in system Settings first.",
                )
            }
        } else {
            viewModel.clearGateMessage()
            viewModel.clearHistory()
        }
    }

    ShieldScaffold(title = "Reports & history", content = { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = ::handleClearHistory) {
                    ShieldText("Clear history", style = MaterialTheme.typography.labelLarge)
                }
            }
            gateMessage?.let { message ->
                ShieldText(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = ShieldPalette.Orange,
                )
            }
            Spacer(Modifier.height(8.dp))

            ShieldCard(title = "Blocked domains (${totalAttempts} total visits)") {
                if (recentBlocked.isEmpty()) {
                    ShieldEmptyState(
                        icon = Icons.Default.Block,
                        title = "Nothing blocked yet",
                        body = "Allowed connections are never recorded — only blocked gambling attempts show up here.",
                    )
                } else {
                    recentBlocked.forEach { BlockedRow(it) }
                }
            }

            Spacer(Modifier.height(16.dp))

            ShieldCard(title = "Activity") {
                if (activity.isEmpty()) {
                    ShieldEmptyState(
                        icon = Icons.Outlined.Schedule,
                        title = "No recent activity",
                        body = "Protection events like start, stop and schedule changes will be logged here.",
                    )
                } else {
                    activity.forEach { ActivityRow(it) }
                }
            }

            Spacer(Modifier.height(16.dp))

            ShieldCard(title = "Reports you submitted") {
                if (submitted.isEmpty()) {
                    ShieldEmptyState(
                        icon = Icons.Default.ReportGmailerrorred,
                        title = "No reports yet",
                        body = "Spotted a wrongly blocked site? Submitting a false-positive report queues it here.",
                    )
                } else {
                    submitted.forEach { SubmittedRow(it) }
                }
            }
        }
    })
}

@Composable
private fun BlockedRow(group: BlockAttemptGroup) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        ShieldText(group.normalizedDomain, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
        ShieldText(
            "${group.count} × · ${group.category.displayName} · last ${group.lastSeenEpochMs}",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = ShieldPalette.Gray500,
        )
    }
}

@Composable
private fun ActivityRow(event: ActivityEvent) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        ShieldText(event.message, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
        ShieldText(
            "${event.type} · ${event.occurredAtEpochMs}",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = ShieldPalette.Gray500,
        )
    }
}

@Composable
private fun SubmittedRow(report: FalsePositiveReport) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        ShieldText(report.normalizedDomain, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
        ShieldText(
            "${report.status} · v${report.blocklistVersion}",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = ShieldPalette.Gray500,
        )
    }
}