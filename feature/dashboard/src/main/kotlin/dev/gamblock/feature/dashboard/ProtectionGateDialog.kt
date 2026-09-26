package dev.gamblock.feature.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.gamblock.data.repository.GateKind
import dev.gamblock.data.repository.ProtectionGateHolder
import dev.gamblock.data.repository.ProtectionGateUiState
import kotlinx.coroutines.delay

private const val COUNTDOWN_FRAME_MS = 250L

/**
 * The single destructive-action dialog. It renders whatever gate the shared
 * [ProtectionGateHolder] reports, so every entry point shows the same rules.
 *
 * [nowProvider] exists so the countdown can be driven deterministically in
 * tests instead of depending on wall-clock time.
 */
@Composable
fun ProtectionGateDialog(
    state: ProtectionGateUiState,
    holder: ProtectionGateHolder,
    nowProvider: () -> Long = System::currentTimeMillis,
) {
    if (!state.isVisible) return

    when (state.kind) {
        GateKind.FortressLocked -> AlertDialog(
            onDismissRequest = holder::dismiss,
            title = { Text("Fortress mode is locked") },
            text = {
                Text(
                    state.fortressLabel
                        ?.let { label ->
                            "A Fortress window ($label) is active. Protection stays on until the window ends."
                        }
                        ?: "A Fortress window is active. Protection stays on until the window ends.",
                )
            },
            confirmButton = { TextButton(onClick = holder::dismiss) { Text("OK") } },
        )

        GateKind.PinRequired -> {
            var pin by remember(state.pinMessage, state.pinLockedUntilRemainingMs) { mutableStateOf("") }
            val locked = state.pinLockedUntilRemainingMs > 0L
            AlertDialog(
                onDismissRequest = holder::dismiss,
                title = { Text("Guardian PIN required") },
                text = {
                    Column {
                        Text(
                            "This action is protected by the 4-digit PIN you set. " +
                                "It is separate from your phone lock.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = pin,
                            onValueChange = { pin = it.filter(Char::isDigit).take(4) },
                            label = { Text("4-digit PIN") },
                            singleLine = true,
                            enabled = !locked,
                            visualTransformation = PasswordVisualTransformation(),
                        )
                        state.pinMessage?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (locked) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Too many wrong attempts. Locked for ${state.pinLockedUntilRemainingMs / 1000}s.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { holder.submitPinBlocking(pin) },
                        enabled = pin.length == 4 && !locked,
                    ) { Text("Unlock") }
                },
                dismissButton = { TextButton(onClick = holder::dismiss) { Text("Cancel") } },
            )
        }

        GateKind.UrgeTimer -> {
            var pledge by remember(state.urgeUnlockAtEpochMs) { mutableStateOf("") }
            // The holder only publishes the deadline, so the dialog does the
            // animating. The loop is bounded by that deadline and always ends.
            val deadline = state.urgeUnlockAtEpochMs
            var nowMs by remember(deadline) { mutableLongStateOf(nowProvider()) }
            LaunchedEffect(deadline) {
                while (nowProvider() < deadline) {
                    delay(COUNTDOWN_FRAME_MS)
                    nowMs = nowProvider()
                }
                nowMs = nowProvider()
            }
            val totalSeconds = state.remainingMs(nowMs) / 1000L
            val done = state.remainingMs(nowMs) <= 0L
            val pledgeMatches = state.matchesPledge(pledge)
            AlertDialog(
                onDismissRequest = holder::dismiss,
                title = { Text("Cooling-off timer") },
                text = {
                    Column {
                        Text(
                            if (done) {
                                "The 15 minutes are up. Type the pledge to continue."
                            } else {
                                "Most urges fade within minutes. Turning protection off has to wait."
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "%d:%02d".format(totalSeconds / 60, totalSeconds % 60),
                            style = MaterialTheme.typography.displaySmall,
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = pledge,
                            onValueChange = { pledge = it },
                            label = { Text("Type: ${state.urgePledgePhrase}") },
                            enabled = done,
                            isError = done && pledge.isNotBlank() && !pledgeMatches,
                        )
                        if (state.urgePledgeAccepted) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Pledge accepted. Unlock to continue.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { holder.submitPledgeBlocking(pledge) },
                        enabled = done && pledgeMatches,
                    ) { Text("Continue") }
                },
                dismissButton = { TextButton(onClick = holder::dismiss) { Text("Cancel & Stay Protected") } },
            )
        }

        GateKind.None -> Unit
    }
}
