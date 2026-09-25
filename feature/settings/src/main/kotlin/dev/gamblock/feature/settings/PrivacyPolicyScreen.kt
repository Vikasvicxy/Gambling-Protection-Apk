package dev.gamblock.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gamblock.core.designsystem.component.ShieldCard
import dev.gamblock.core.designsystem.component.ShieldScaffold
import dev.gamblock.core.designsystem.component.ShieldText

@Composable
fun PrivacyPolicyRoute(onBack: () -> Unit) {
    ShieldScaffold(title = "Privacy & terms", content = { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            ShieldCard(title = "Privacy policy") {
                ShieldText(
                    text = "Shield is designed to keep protection local. The VPN handles DNS hostname lookups only and decides whether to block a domain on this device. It does not inspect or collect browsing content, page contents, contacts, messages, passwords, location, or other personal data.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                ShieldText(
                    text = "Blocked-domain counters, preferences, exceptions, and protection state are stored in the app's local database. Shield includes no advertising SDK, third-party analytics, cloud analytics, or remote tracking.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                ShieldText(
                    text = "Shield may download signed blocklist releases over HTTPS to keep gambling rules current. Optional accountability or parent/guardian features, only when explicitly enabled, can share a pseudonymous device identifier and aggregate protection events with the selected trusted party. A report may include the normalized domain that the user explicitly chooses to report.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(16.dp))

            ShieldCard(title = "VPN disclosure") {
                ShieldText(
                    text = "The Android VPN permission is used to create a local DNS filtering tunnel so gambling domains can be blocked. DNS hostnames are processed locally on this device. The VPN does not transmit or collect browsing content or personal data.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                ShieldText(
                    text = "When you accept the disclosure, Android may show its own VPN confirmation screen. Shield never bypasses that system consent step.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(16.dp))

            ShieldCard(title = "Terms of use") {
                ShieldText(
                    text = "Shield provides a best-effort local DNS filter, not a complete or guaranteed security control. Apps with their own encrypted DNS, hardcoded resolvers, private tunnels, or another VPN may bypass DNS filtering.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                ShieldText(
                    text = "Use Shield only on devices and accounts you own or are authorized to manage. Do not use it to interfere with another person's device, network, or lawful access. The software is provided without a guarantee that every gambling domain or bypass technique will be identified.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(16.dp))

            ShieldCard(title = "Your choices") {
                ShieldText(
                    text = "You can turn protection off, remove local exceptions, clear local history, and disable optional accountability features from Settings. Uninstalling Shield removes its local app data.",
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                ShieldText(
                    text = "This viewer is bundled with the app and remains available offline.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    })
}
