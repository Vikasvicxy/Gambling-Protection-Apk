package dev.gamblock.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.gamblock.core.designsystem.component.ShieldButton
import dev.gamblock.core.designsystem.component.ShieldCard
import dev.gamblock.core.designsystem.component.ShieldText
import dev.gamblock.core.designsystem.theme.ShieldPalette

@Composable
fun OnboardingRoute(
    onDone: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    var step by rememberSaveable { mutableStateOf(0) }
    var consent1 by rememberSaveable { mutableStateOf(false) }
    var consent2 by rememberSaveable { mutableStateOf(false) }
    val steps = listOf("Welcome", "How it works", "Consent")

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Spacer(Modifier.height(40.dp))
            Text(
                text = "Shield",
                style = MaterialTheme.typography.displayLarge,
                color = ShieldPalette.Blue,
            )
            Text(
                text = "Gambling protection that lives on your device.",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(24.dp))
            Text(text = steps[step], style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            when (step) {
                0 -> welcomeStep()
                1 -> howItWorksStep()
                2 -> ConsentStep(
                    consent1 = consent1,
                    consent2 = consent2,
                    onToggle1 = { consent1 = !consent1 },
                    onToggle2 = { consent2 = !consent2 },
                )
            }

            Spacer(Modifier.height(32.dp))
            if (step < 2) {
                ShieldButton(
                    text = if (step == 1) "Continue" else "Next",
                    onClick = { step = (step + 1).coerceAtMost(2) },
                )
                if (step > 0 || step < 2) {
                    Spacer(Modifier.height(8.dp))
                    if (step > 0) {
                        ShieldButton(
                            text = "Back",
                            onClick = { step = (step - 1).coerceAtLeast(0) },
                        )
                    }
                }
            } else {
                ShieldButton(
                    text = "Get started",
                    onClick = {
                        viewModel.complete()
                        onDone()
                    },
                    enabled = consent1 && consent2,
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            repeat(steps.size) { index ->
                Text(
                    text = if (index <= step) "\u25CF" else "\u25CB",
                    color = if (index <= step) ShieldPalette.Blue else ShieldPalette.Gray300,
                )
                Spacer(Modifier.padding(horizontal = 4.dp))
            }
        }
    }
}

@Composable
private fun welcomeStep() {
    ShieldText(
        text = "Privacy first",
        style = MaterialTheme.typography.titleLarge,
    )
    ShieldText(
        text = "Core DNS filtering, local preferences, and blocked-domain counters stay on this device. " +
            "Shield has no advertising SDK, analytics, or remote tracking. Signed blocklist updates may be " +
            "downloaded over HTTPS, and optional accountability features share only the information you explicitly enable.",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun howItWorksStep() {
    ShieldText(
        text = "DNS filter behind a local VPN",
        style = MaterialTheme.typography.titleLarge,
    )
    ShieldText(
        text = "Shield creates a VPN tunnel that only carries DNS hostname lookups. It answers " +
            "blocked domains locally and forwards other lookups to your normal network DNS. " +
            "No website contents are inspected or collected. Signed rules may be updated over HTTPS, " +
            "while optional accountability sharing remains off unless you enable it.",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
    ShieldCard(modifier = Modifier.padding(top = 16.dp)) {
        ShieldText(
            text = "A local VPN is chosen because only Android allows it legally and " +
                "reliably; the actual traffic is untouched.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ConsentStep(
    consent1: Boolean,
    consent2: Boolean,
    onToggle1: () -> Unit,
    onToggle2: () -> Unit,
) {
    ShieldText(text = "Consent & safety", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(16.dp))
    ShieldCard {
        CheckboxRow(
            checked = consent1,
            onCheckedChange = onToggle1,
            label = "I understand Shield is a DNS filter, not a complete blocker. It cannot stop opted-out apps, hardcoded resolvers or HTTPS-based bypasses.",
        )
        CheckboxRow(
            checked = consent2,
            onCheckedChange = onToggle2,
            label = "I consent to blocked-domain statistics being stored locally on this phone only.",
        )
    }
}

@Composable
private fun CheckboxRow(
    checked: Boolean,
    onCheckedChange: () -> Unit,
    label: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = { onCheckedChange() })
        ShieldText(text = label, modifier = Modifier.padding(start = 8.dp))
    }
}