package dev.gamblock.feature.diagnostics

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gamblock.core.designsystem.component.ShieldButton
import dev.gamblock.core.designsystem.component.ShieldCard
import dev.gamblock.core.designsystem.component.ShieldScaffold
import dev.gamblock.core.designsystem.component.ShieldText

@Composable
fun DiagnosticsRoute(
    onBack: () -> Unit,
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

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

            ShieldCard(title = "Battery") {
                ShieldText(
                    text = "Battery-optimization exempt: ${state.isBatteryOptimizationExempt} · Charging: ${state.isCharging}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                ShieldText(
                    text = "You can disable battery optimization for Shield to avoid the system killing the VPN service.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(16.dp))

            state.conflict?.let { conflict ->
                ShieldCard(title = "VPN conflict") {
                    ShieldText(
                        text = "Another VPN active: ${conflict.activeNetworkUsesVpnTransport} · Transports: ${conflict.activeTransportNames}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    ShieldText(
                        text = "Only one VPN can run at a time. Turn off the other VPN app (or use its split-tunnel) before enabling Shield.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            state.report?.let { report ->
                ShieldCard(title = "Health · ${report.overall}") {
                    report.components.forEach { component ->
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            ShieldText("${component.component} · ${component.status}", style = MaterialTheme.typography.bodyMedium)
                            ShieldText(component.message, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            if (state.guidance.isNotEmpty()) {
                state.guidance.forEach { item ->
                    ShieldCard(title = item.title) {
                        ShieldText(item.body, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }

            ShieldButton(text = "Re-run checks", onClick = { viewModel.refresh() })
        }
    })
}