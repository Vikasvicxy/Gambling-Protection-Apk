package dev.gamblock.feature.accountability

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import dev.gamblock.core.model.PartnerRole

/**
 * Accountability home: invite a trusted partner, accept an invite, and view active
 * relationships. Privacy prompts explain exactly what partners can and cannot see.
 */
@Composable
fun AccountabilityRoute(
    onBack: () -> Unit,
    viewModel: AccountabilityViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.lastError) {
        state.lastError?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    ShieldScaffold(title = "Accountability", navigationIcon = {
        androidx.compose.material3.IconButton(onClick = onBack) {
            androidx.compose.material3.Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
            )
        }
    }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            ShieldCard(title = "How Accountability works") {
                ShieldText(
                    "A trusted partner can see minimal protection events (e.g. blocked attempts, " +
                        "protection active, heartbeat lost). Partners never see your browsing history, " +
                        "messages, contacts, passwords or location by default.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(16.dp))

            if (state.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
            }

            ShieldCard(title = "Invite a trusted partner") {
                Button(
                    onClick = { viewModel.createPartnerInvitation() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Create invitation")
                }
                if (state.activeInvitation != null) {
                    Spacer(Modifier.height(12.dp))
                    ShieldText(
                        "Share this invitation token with your partner:",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val invitation = state.activeInvitation
                    if (invitation != null) {
                        InvitationTokenCopy(invitation.fullToken)
                    }
                    ShieldText(
                        "The token expires in 10 minutes and can be used only once.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            ShieldCard(title = "Accept an invitation") {
                var token by rememberSaveable { mutableStateOf("") }
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Invitation token") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        if (token.isNotBlank()) viewModel.acceptInvitation(token)
                    },
                    enabled = token.isNotBlank(),
                ) {
                    Text("Accept")
                }
            }

            Spacer(Modifier.height(16.dp))

            ShieldCard(title = "Your partners") {
                if (state.relationships.isEmpty()) {
                    ShieldText(
                        "No partners yet. Invite one to start accountability.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    state.relationships.forEach { rel ->
                        RelationshipRow(rel)
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun InvitationTokenCopy(token: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = token,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = {}) {
            Text("Copy")
        }
    }
}

@Composable
private fun RelationshipRow(relationship: dev.gamblock.core.model.PartnerRelationship) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            val roleLabel = when (relationship.role) {
                PartnerRole.TRUSTED_PARTNER -> "Trusted Partner"
                PartnerRole.PARENT_OR_GUARDIAN -> "Parent / Guardian"
            }
            Text(roleLabel, style = MaterialTheme.typography.titleSmall)
            ShieldText(
                "Status: ${relationship.status.name} · Capabilities: ${relationship.capabilities.size}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}