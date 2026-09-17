package dev.gamblock.feature.parent

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gamblock.core.designsystem.component.ShieldCard
import dev.gamblock.core.designsystem.component.ShieldScaffold
import dev.gamblock.core.designsystem.component.ShieldText
import dev.gamblock.core.model.ApprovalRequest
import dev.gamblock.core.model.HealthStatus

/**
 * Parent / Guardian dashboard. Shows a genuine parent-verified view of a supervised
 * child device with privacy-minimized aggregate data only.
 */
@Composable
fun ParentRoute(
    onBack: () -> Unit,
    viewModel: ParentViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.lastError) {
        state.lastError?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    ShieldScaffold(title = "Parent / Guardian", navigationIcon = {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
    }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            ShieldCard(title = "Parent mode") {
                ShieldText(
                    "Parent mode is genuine parental control for a supervised child device. " +
                        "You will see minimal aggregate protection stats - never a full browser history.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(16.dp))

            if (state.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
            }

            ShieldCard(title = "Pair with a child device") {
                Button(
                    onClick = { viewModel.createChildInvitation() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Create child pairing invite")
                }
                if (state.activeInvitation != null) {
                    Spacer(Modifier.height(12.dp))
                    val invitation = state.activeInvitation
                    if (invitation != null) {
                        Text(
                            invitation.fullToken,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    ShieldText(
                        "Share this code with the child device. It expires quickly and is single-use.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Spacer(Modifier.height(12.dp))
                var token by rememberSaveable { mutableStateOf("") }
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Accept a child device invitation") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { if (token.isNotBlank()) viewModel.acceptChildInvitation(token) },
                    enabled = token.isNotBlank(),
                ) { Text("Accept") }
            }

            Spacer(Modifier.height(16.dp))

            ShieldCard(title = "Protected children") {
                if (state.relationships.isEmpty()) {
                    ShieldText(
                        "No child devices linked yet.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    state.relationships.forEach { rel ->
                        ChildDeviceRow(rel)
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            if (state.pendingApprovalCount > 0) {
                Spacer(Modifier.height(16.dp))
                ShieldCard(title = "Pending approvals ($state.pendingApprovalCount)") {
                    ShieldText(
                        "A child requested a protected configuration change.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChildDeviceRow(relationship: dev.gamblock.core.model.PartnerRelationship) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Child device", style = MaterialTheme.typography.titleSmall)
            ShieldText(
                "Protection status: ${if (relationship.active) "ACTIVE" else relationship.status.name}",
                style = MaterialTheme.typography.bodySmall,
            )
            ShieldText(
                "Health: ${HealthStatus.UNKNOWN.name} · Database: unknown · Last check-in: pending",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}