package dev.gamblock.feature.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gamblock.core.designsystem.component.ShieldButton
import dev.gamblock.core.designsystem.component.ShieldCard
import dev.gamblock.core.designsystem.component.ShieldScaffold
import dev.gamblock.core.designsystem.component.ShieldText
import dev.gamblock.core.designsystem.theme.ShieldPalette
import dev.gamblock.core.model.ProtectionSchedule

@Composable
fun SetupRoute(
    onDone: () -> Unit,
    onEnableProtection: () -> Unit,
    onDisableProtection: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    ShieldScaffold(title = "Protection setup", content = { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            ShieldCard {
                ProtectionToggleRow(
                    enabled = state.vpnEnabled,
                    onToggle = {
                        viewModel.setVpnEnabled(it)
                        if (it) onEnableProtection() else onDisableProtection()
                    },
                )
                ShieldText(
                    text = if (state.vpnEnabled) {
                        "VPN protection active. All domain lookups are filtered locally."
                    } else {
                        "Turn on protection to block gambling domains."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Spacer(Modifier.height(16.dp))

            ShieldCard(title = "Schedule (optional)") {
                val schedule = state.schedule
                var scheduleEnabled by rememberSaveable { mutableStateOf(schedule.enabled) }
                var start by rememberSaveable {
                    mutableStateOf("%02d:%02d".format(schedule.startHour, schedule.startMinute))
                }
                var end by rememberSaveable {
                    mutableStateOf("%02d:%02d".format(schedule.endHour, schedule.endMinute))
                }
                var error by rememberSaveable { mutableStateOf<String?>(null) }

                Switch(
                    checked = scheduleEnabled,
                    onCheckedChange = {
                        scheduleEnabled = it
                        if (!it) {
                            viewModel.setSchedule(
                                ProtectionSchedule(
                                    enabled = false,
                                    startHour = schedule.startHour,
                                    startMinute = schedule.startMinute,
                                    endHour = schedule.endHour,
                                    endMinute = schedule.endMinute,
                                ),
                            )
                        }
                    },
                )
                Text(
                    text = "Only enforce during a window (e.g. night watch). Outside the window traffic passes untouched.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )

                if (scheduleEnabled) {
                    OutlinedTextField(
                        value = start,
                        onValueChange = { start = it },
                        label = { Text("Start (HH:MM)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                    OutlinedTextField(
                        value = end,
                        onValueChange = { end = it },
                        label = { Text("End (HH:MM)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                    ShieldButton(
                        text = "Save window",
                        enabled = true,
                        onClick = {
                            val parsed = parseWindow(start, end)
                            if (parsed == null) {
                                error = "Use the format HH:MM, e.g. 22:00"
                            } else {
                                error = null
                                viewModel.setSchedule(parsed)
                            }
                        },
                    )
                    error?.let {
                        Text(
                            it,
                            color = ShieldPalette.Red,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

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
                    label = { Text("How should Shield greet you?") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(24.dp))
            ShieldButton(text = "Done", onClick = onDone, modifier = Modifier.fillMaxWidth())
        }
    })
}

private fun parseWindow(start: String, end: String): ProtectionSchedule? {
    fun parse(s: String): Pair<Int, Int>? {
        val parts = s.trim().split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour to minute
    }

    val s = parse(start) ?: return null
    val e = parse(end) ?: return null
    return ProtectionSchedule(
        enabled = true,
        startHour = s.first,
        startMinute = s.second,
        endHour = e.first,
        endMinute = e.second,
    )
}

@Composable
private fun ProtectionToggleRow(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Protection", style = MaterialTheme.typography.titleMedium)
            Text(
                if (enabled) "On" else "Off",
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) ShieldPalette.Green else ShieldPalette.Gray500,
            )
        }
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}