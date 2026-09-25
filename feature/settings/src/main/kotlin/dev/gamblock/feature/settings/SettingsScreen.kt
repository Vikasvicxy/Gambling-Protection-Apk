package dev.gamblock.feature.settings

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gamblock.core.designsystem.component.ShieldCard
import dev.gamblock.core.designsystem.component.ShieldScaffold
import dev.gamblock.core.designsystem.component.ShieldText

@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onOpenAccountability: () -> Unit = {},
    onOpenParent: () -> Unit = {},
    onOpenPrivacyPolicy: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val exceptions by viewModel.exceptions.collectAsStateWithLifecycle()
    val exceptionMessage by viewModel.exceptionMessage.collectAsStateWithLifecycle()
    var selectedDurationHours by rememberSaveable { mutableStateOf<Long?>(null) }

    ShieldScaffold(title = "Settings", content = { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            ShieldCard(title = "Personalisation") {
                var name by rememberSaveable(state.greetPersonalizationName) {
                    mutableStateOf(state.greetPersonalizationName)
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        viewModel.setGreetName(it)
                    },
                    label = { Text("Greeting name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(16.dp))

            ShieldCard(title = "Experience") {
                ToggleRow(
                    label = "Haptics",
                    checked = state.hapticsEnabled,
                    onToggle = { viewModel.setHapticsEnabled(it) },
                )
                ToggleRow(
                    label = "Status notifications",
                    checked = state.notificationsEnabled,
                    onToggle = { viewModel.setNotificationsEnabled(it) },
                )
            }

            Spacer(Modifier.height(16.dp))

            ShieldCard(title = "Schedule") {
                ShieldText(
                    text = if (state.schedule.enabled) {
                        "Enforced ${state.schedule.startHour}:${state.schedule.startMinute} - ${state.schedule.endHour}:${state.schedule.endMinute}"
                    } else {
                        "Always on (no window)"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                ShieldText(
                    "Change the window in Protection setup.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(16.dp))

            ShieldCard(title = "Bypass exceptions") {
                ShieldText(
                    "Allow a site through Shield, e.g. a legitimately blocked service. " +
                        "Expiring exceptions self-remove and never reach the internet unless active.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                var domainInput by rememberSaveable { mutableStateOf("") }
                OutlinedTextField(
                    value = domainInput,
                    onValueChange = { domainInput = it },
                    label = { Text("Domain to allow") },
                    placeholder = { Text("example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                DurationSelector(
                    selectedHours = selectedDurationHours,
                    onSelect = { selectedDurationHours = it },
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        viewModel.addException(domainInput, selectedDurationHours)
                        domainInput = ""
                        selectedDurationHours = null
                        viewModel.clearExceptionMessage()
                    },
                    enabled = domainInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Add exception")
                }
                exceptionMessage?.let { message ->
                    Spacer(Modifier.height(8.dp))
                    ShieldText(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (exceptions.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    exceptions.forEach { exception ->
                        ExceptionRow(
                            exception = exception,
                            onRemove = { viewModel.removeException(exception.id) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            ShieldCard(title = "Accountability") {
                ShieldText(
                    "Invite a trusted partner to receive minimal protection events, or accept an invitation.",
                    style = MaterialTheme.typography.bodySmall,
                )
                androidx.compose.material3.Button(
                    onClick = onOpenAccountability,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open Accountability")
                }
            }

            Spacer(Modifier.height(16.dp))

            ShieldCard(title = "Parent / Guardian") {
                ShieldText(
                    "Genuine parental control for a supervised child device with minimal aggregate stats.",
                    style = MaterialTheme.typography.bodySmall,
                )
                androidx.compose.material3.Button(
                    onClick = onOpenParent,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open Parent Mode")
                }
            }

            Spacer(Modifier.height(16.dp))

            ShieldCard(title = "Protection lock") {
                ShieldText(
                    "Protect sensitive actions with your device PIN, pattern, or biometric. " +
                        "Only visible once a device lock is configured.",
                    style = MaterialTheme.typography.bodySmall,
                )
                ToggleRow(
                    label = "Lock turning off protection",
                    checked = state.requireAuthBeforeDisable,
                    onToggle = { viewModel.setRequireAuthBeforeDisable(it) },
                )
                ToggleRow(
                    label = "Lock clearing history",
                    checked = state.requireAuthBeforeClearHistory,
                    onToggle = { viewModel.setRequireAuthBeforeClearHistory(it) },
                )
            }

            Spacer(Modifier.height(16.dp))

            ShieldCard(title = "Privacy") {
                ShieldText(
                    "Core DNS filtering and local settings stay on this device. Shield has no advertising SDK, analytics, or remote tracking. Signed rule updates and optional accountability features are explained in the offline policy.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onOpenPrivacyPolicy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Read Privacy Policy & Terms")
                }
            }
        }
    })
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}

private val DURATION_OPTIONS = listOf(
    "1 hour" to 1L,
    "1 day" to 24L,
    "1 week" to 24L * 7,
    "Permanent" to null,
)

@Composable
private fun DurationSelector(
    selectedHours: Long?,
    onSelect: (Long?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        DURATION_OPTIONS.forEach { (label, hours) ->
            FilterChip(
                selected = selectedHours == hours,
                onClick = { onSelect(hours) },
                label = { Text(label) },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
}

@Composable
private fun ExceptionRow(
    exception: dev.gamblock.core.model.CustomDomainException,
    onRemove: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                exception.normalizedDomain,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                exception.expiryLabel(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Remove ${exception.normalizedDomain}",
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun dev.gamblock.core.model.CustomDomainException.expiryLabel(): String {
    val expires = expiresAtEpochMs ?: return "Permanent"
    val remainingHours = (expires - System.currentTimeMillis()) / 3_600_000
    return if (remainingHours > 24) {
        "Expires in ${remainingHours / 24} days"
    } else {
        "Expires in ${maxOf(remainingHours, 1)} hour(s)"
    }
}