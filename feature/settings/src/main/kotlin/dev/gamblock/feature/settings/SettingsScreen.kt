package dev.gamblock.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gamblock.core.designsystem.component.ShieldCard
import dev.gamblock.core.designsystem.component.ShieldScaffold
import dev.gamblock.core.designsystem.component.ShieldText

@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

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

            ShieldCard(title = "Privacy") {
                ShieldText(
                    "Everything is stored on this device. No account, no analytics, no network calls to Shield servers.",
                    style = MaterialTheme.typography.bodySmall,
                )
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