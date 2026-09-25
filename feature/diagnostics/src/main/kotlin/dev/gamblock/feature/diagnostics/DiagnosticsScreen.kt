package dev.gamblock.feature.diagnostics

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
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
import dev.gamblock.core.designsystem.component.ShieldStatusChip
import dev.gamblock.core.designsystem.theme.ShieldPalette
import dev.gamblock.core.model.ComponentHealth
import dev.gamblock.core.model.HealthComponent
import dev.gamblock.core.model.HealthStatus

@Composable
fun DiagnosticsRoute(
    onBack: () -> Unit,
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val reviewerModeEnabled by viewModel.reviewerModeEnabled.collectAsStateWithLifecycle()

    ShieldScaffold(title = "Diagnostics", content = { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            state.oem?.let { oem ->
                ShieldCard(title = "Device") {
                    ShieldText("${oem.manufacturer} ${oem.model} (${oem.kind})", style = MaterialTheme.typography.bodyMedium)
                    ShieldText("Android ${oem.androidRelease} (API ${oem.androidSdk})", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(16.dp))
            }

            if (BuildConfig.REVIEWER_MODE_ENABLED) {
                ShieldCard(title = "Reviewer demo mode") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        ShieldText(
                            "Simulate blocking with reserved .test domains",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = reviewerModeEnabled,
                            onCheckedChange = viewModel::setReviewerModeEnabled,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    ShieldText(
                        "When enabled, reviewer-blocked.test, demo-casino.test, and demo-sportsbook.test are blocked. reviewer-allowed.test remains available for comparison. This mode is disabled in release builds.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            state.conflict?.let { conflict ->
                ShieldCard(title = "VPN conflict") {
                    when {
                        conflict.activeNetworkVpnIsShield -> {
                            ShieldText(
                                text = "Active VPN transport: Shield (own tunnel) - healthy",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            ShieldText(
                                text = "Shield's local DNS-only VPN is the active network. This is expected, not a conflict.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        conflict.activeNetworkUsesVpnTransport -> {
                            ShieldText(
                                text = "Another VPN active: true · Transports: ${conflict.activeTransportNames}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            ShieldText(
                                text = "Only one VPN can run at a time. Turn off the other VPN app (or use its split-tunnel) before enabling Shield.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        else -> {
                            ShieldText(
                                text = "Another VPN active: false · Transports: ${conflict.activeTransportNames}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            HealthCenter(
                report = state.report,
                canFix = viewModel::canFix,
                applyFix = viewModel::applyFix,
            )

            if (state.guidance.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                state.guidance.forEach { item ->
                    ShieldCard(title = item.title) {
                        ShieldText(item.body, style = MaterialTheme.typography.bodySmall)
                        item.steps.forEachIndexed { index, step ->
                            ShieldText(
                                text = "${index + 1}. $step",
                                modifier = Modifier.padding(top = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }

            if (state.guidance.isEmpty()) {
                Spacer(Modifier.height(16.dp))
                ShieldCard(title = "Device guidance") {
                    ShieldText(
                        text = "No OEM-specific action needed for ${state.oem?.kind ?: "this device"}. Battery state: exempt=${state.isBatteryOptimizationExempt}, charging=${state.isCharging}.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            ShieldButton(text = "Re-run checks", onClick = { viewModel.refresh() })
        }
    })
}

/** Grouped diagnostic indicators (Network Engine / System Compliance / Data Integrity). */
@Composable
private fun HealthCenter(
    report: dev.gamblock.core.model.HealthReport?,
    canFix: (HealthComponent) -> Boolean,
    applyFix: (HealthComponent) -> Unit,
) {
    val statusColor: (HealthStatus) -> Color = { status ->
        when (status) {
            HealthStatus.HEALTHY -> ShieldPalette.Green
            HealthStatus.DEGRADED -> ShieldPalette.Orange
            HealthStatus.CRITICAL -> ShieldPalette.Red
            HealthStatus.UNKNOWN -> ShieldPalette.Gray500
        }
    }

    Column {
        DiagnosticsGroup.entries.forEach { group ->
            Spacer(Modifier.height(if (group == DiagnosticsGroup.entries.first()) 0.dp else 16.dp))
            ShieldCard(title = group.displayName) {
                val components = DiagnosticsViewModel.componentsOf(group)
                components.forEach { component ->
                    val health = report?.component(component)
                    val status = health?.status ?: HealthStatus.UNKNOWN
                    val color = statusColor(status)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                    ) {
                        Icon(
                            imageVector = when {
                                status == HealthStatus.UNKNOWN -> Icons.Filled.HelpOutline
                                DiagnosticsViewModel.isDegraded(status) -> Icons.Filled.ErrorOutline
                                else -> Icons.Filled.CheckCircle
                            },
                            contentDescription = status.name,
                            tint = color,
                            modifier = Modifier.size(18.dp),
                        )
                        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                            ShieldText(
                                text = DiagnosticsViewModel.displayName(component),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (health != null) {
                                ShieldText(
                                    text = health.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (DiagnosticsViewModel.isDegraded(status) && canFix(component)) {
                            TextButton(onClick = { applyFix(component) }) {
                                ShieldText("Fix", style = MaterialTheme.typography.labelLarge, color = ShieldPalette.Orange)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        report?.let { r ->
            ShieldStatusChip(
                text = "Overall health: ${r.overall.name}",
                containerColor = statusColor(r.overall).copy(alpha = 0.14f),
                contentColor = statusColor(r.overall),
                leadingDotColor = statusColor(r.overall),
            )
        }
    }
}