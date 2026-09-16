package dev.gamblock.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gamblock.core.designsystem.component.ShieldButton
import dev.gamblock.core.designsystem.component.ShieldCard
import dev.gamblock.core.designsystem.component.ShieldScaffold
import dev.gamblock.core.designsystem.component.ShieldText
import dev.gamblock.core.designsystem.theme.ShieldPalette
import dev.gamblock.core.model.BlockAttemptGroup
import dev.gamblock.core.model.HealthStatus

@Composable
fun DashboardRoute(
    onEnableProtection: () -> Unit,
    onDisableProtection: () -> Unit,
    onOpenReports: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSupport: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    ShieldScaffold(
        title = "Shield",
        actions = {
            IconButton(onClick = { viewModel.refreshHealth() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh health", tint = MaterialTheme.colorScheme.onPrimary)
            }
        },
        content = { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                ProtectionBanner(
                    enabled = state.settings.vpnEnabled,
                    onToggle = {
                        viewModel.setProtectionEnabled(it)
                        if (it) onEnableProtection() else onDisableProtection()
                    },
                )

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatBox(value = state.totalBlockedAttempts.toString(), label = "Blocked visits", modifier = Modifier.weight(1f))
                    StatBox(value = state.stats?.enabledRuleCount?.toString() ?: "-", label = "Rules", modifier = Modifier.weight(1f))
                    StatBox(value = state.vpn.queriesHandled.toString(), label = "Queries", modifier = Modifier.weight(1f))
                }

                Spacer(Modifier.height(16.dp))

                HealthCard(
                    status = state.health?.overall,
                    summary = state.health?.components?.joinToString(separator = "\n") {
                        "${it.component.name}: ${it.status}"
                    } ?: "No measurements yet",
                )

                Spacer(Modifier.height(16.dp))

                val commitment = state.commitment
                if (commitment != null) {
                    ShieldCard(title = "Commitment") {
                        ShieldText(
                            text = "Mode: ${commitment.mode} - ${commitment.level}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        ShieldText(
                            text = "Accumulated ${commitment.accumulatedElapsedMillis / 3600_000}h of ${commitment.intendedDurationMs / 3600_000}h (extend-only until the end is genuinely reached).",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }

                ShieldCard(title = "Recent blocked domains") {
                    if (state.recentBlocked.isEmpty()) {
                        ShieldText(
                            text = "Nothing recorded yet. With protection on, blocked attempts appear here.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        state.recentBlocked.forEach { group ->
                            BlockedRow(group)
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                ShieldButton(text = "Reports & history", onClick = onOpenReports, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShieldButton(text = "Diagnostics", onClick = onOpenDiagnostics, modifier = Modifier.weight(1f))
                    ShieldButton(text = "Settings", onClick = onOpenSettings, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                ShieldButton(text = "Support & guidance", onClick = onOpenSupport, modifier = Modifier.fillMaxWidth())
            }
        },
    )
}

@Composable
private fun ProtectionBanner(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    ShieldCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Protection ${if (enabled) "active" else "off"}", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = if (enabled) {
                        "All DNS lookups filtered locally."
                    } else {
                        "Turn protection on to block gambling domains."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun StatBox(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ShieldText(text = value, style = MaterialTheme.typography.headlineMedium, color = ShieldPalette.Blue)
        ShieldText(text = label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun HealthCard(
    status: HealthStatus?,
    summary: String,
) {
    val color = when (status) {
        HealthStatus.HEALTHY -> ShieldPalette.Green
        HealthStatus.DEGRADED -> ShieldPalette.Orange
        HealthStatus.CRITICAL -> ShieldPalette.Red
        HealthStatus.UNKNOWN, null -> ShieldPalette.Gray500
    }
    ShieldCard(title = "Health · ${status ?: "unknown"}") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "\u25CF", color = color, style = MaterialTheme.typography.headlineMedium)
            ShieldText(text = summary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun BlockedRow(group: BlockAttemptGroup) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(text = "\uD83D\uDEAB", style = MaterialTheme.typography.bodyLarge)
        ShieldText(
            text = group.normalizedDomain,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        ShieldText(text = "${group.count} ×", style = MaterialTheme.typography.bodySmall, color = ShieldPalette.Gray500)
    }
}