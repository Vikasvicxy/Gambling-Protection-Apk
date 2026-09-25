package dev.gamblock.feature.dashboard

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gamblock.core.designsystem.component.ShieldButton
import dev.gamblock.core.designsystem.component.ShieldCard
import dev.gamblock.core.designsystem.component.ShieldMetricCard
import dev.gamblock.core.designsystem.component.ShieldScaffold
import dev.gamblock.core.designsystem.component.ShieldStatusBeacon
import dev.gamblock.core.designsystem.component.ShieldStatusChip
import dev.gamblock.core.designsystem.component.ShieldText
import dev.gamblock.core.designsystem.theme.ShieldPalette
import dev.gamblock.core.model.BlockAttemptGroup
import dev.gamblock.core.model.HealthStatus

private enum class ProtectionStatus { ACTIVE, DEGRADED, OFF }

private fun protectionStatus(enabled: Boolean, vpnRunning: Boolean, health: HealthStatus?): ProtectionStatus =
    when {
        !enabled -> ProtectionStatus.OFF
        !vpnRunning -> ProtectionStatus.DEGRADED
        health == HealthStatus.CRITICAL || health == HealthStatus.DEGRADED -> ProtectionStatus.DEGRADED
        else -> ProtectionStatus.ACTIVE
    }

private val ProtectionStatus.accent: Color
    get() = when (this) {
        ProtectionStatus.ACTIVE -> ShieldPalette.Green
        ProtectionStatus.DEGRADED -> ShieldPalette.Orange
        ProtectionStatus.OFF -> ShieldPalette.Gray500
    }

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
                ProtectionHeroCard(
                    status = protectionStatus(
                        enabled = state.settings.vpnEnabled,
                        vpnRunning = state.vpn.isRunning,
                        health = state.health?.overall,
                    ),
                    bypasses = state.vpn.exceptionsApplied,
                    enabled = state.settings.vpnEnabled,
                    hapticsEnabled = state.settings.hapticsEnabled,
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
                    ShieldMetricCard(
                        value = state.totalBlockedAttempts,
                        label = "Blocked visits",
                        icon = Icons.Default.Block,
                        accent = ShieldPalette.Red,
                        hapticsEnabled = state.settings.hapticsEnabled,
                        modifier = Modifier.weight(1f),
                        onClick = onOpenReports,
                    )
                    ShieldMetricCard(
                        value = state.stats?.enabledRuleCount ?: 0,
                        label = "Rules active",
                        icon = Icons.Default.Assessment,
                        accent = ShieldPalette.Blue,
                        hapticsEnabled = state.settings.hapticsEnabled,
                        modifier = Modifier.weight(1f),
                        onClick = onOpenDiagnostics,
                    )
                    ShieldMetricCard(
                        value = state.vpn.queriesHandled.toInt(),
                        label = "Queries filtered",
                        icon = Icons.Default.Dns,
                        accent = ShieldPalette.Green,
                        hapticsEnabled = state.settings.hapticsEnabled,
                        modifier = Modifier.weight(1f),
                        onClick = onOpenReports,
                    )
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
private fun ProtectionHeroCard(
    status: ProtectionStatus,
    bypasses: Long,
    enabled: Boolean,
    hapticsEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val accent = status.accent
    val haptics = LocalHapticFeedback.current

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(width = 1.dp, color = accent.copy(alpha = if (status == ProtectionStatus.ACTIVE) 0.35f else 0f), shape = RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        color = accent.copy(alpha = 0.08f),
        tonalElevation = 3.dp,
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShieldStatusBeacon(color = accent, active = status == ProtectionStatus.ACTIVE)
                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        text = when (status) {
                            ProtectionStatus.ACTIVE -> "Protection Active"
                            ProtectionStatus.DEGRADED -> "Protection Needs Attention"
                            ProtectionStatus.OFF -> "Protection Off"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = when (status) {
                            ProtectionStatus.ACTIVE -> "Shield is filtering gambling domains locally."
                            ProtectionStatus.DEGRADED -> "Enable protection or review diagnostics."
                            ProtectionStatus.OFF -> "Turn protection on to block gambling domains."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { next ->
                        if (hapticsEnabled) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        onToggle(next)
                    },
                )
            }

            Spacer(Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (status) {
                    ProtectionStatus.ACTIVE -> {
                        ShieldStatusChip(
                            text = "DNS filtering active",
                            containerColor = ShieldPalette.Green.copy(alpha = 0.14f),
                            contentColor = ShieldPalette.Green,
                            leadingDotColor = ShieldPalette.Green,
                        )
                        ShieldStatusChip(
                            text = "$bypasses bypasses detected",
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ProtectionStatus.DEGRADED -> {
                        ShieldStatusChip(
                            text = "Diagnostics recommended",
                            containerColor = ShieldPalette.Orange.copy(alpha = 0.16f),
                            contentColor = ShieldPalette.Orange,
                            leadingDotColor = ShieldPalette.Orange,
                        )
                    }
                    ProtectionStatus.OFF -> {
                        ShieldStatusChip(
                            text = "All DNS queries pass through",
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
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
            ShieldStatusBeacon(color = color, active = status == HealthStatus.HEALTHY)
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