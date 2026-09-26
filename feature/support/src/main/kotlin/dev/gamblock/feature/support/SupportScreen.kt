package dev.gamblock.feature.support

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.gamblock.core.designsystem.component.ShieldButton
import dev.gamblock.core.designsystem.component.ShieldCard
import dev.gamblock.core.designsystem.component.ShieldScaffold
import dev.gamblock.core.designsystem.component.ShieldText

@Composable
fun SupportRoute(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val packageInfo = rememberPackageInfo(context)

    ShieldScaffold(title = "Support & guidance", content = { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            ShieldCard(title = "App") {
                ShieldText("Version ${packageInfo.first} (${packageInfo.second})", style = MaterialTheme.typography.bodyMedium)
                ShieldText(
                    "Shield filters domain lookups on this device only. It does not decrypt or inspect HTTPS traffic.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(16.dp))

            ShieldCard(title = "Try it now") {
                ShieldText(
                    "Open a browser and visit:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                ShieldText(
                    "bet-example.test · casino-example.test\nsportsbook-example.test · poker-example.test",
                    style = MaterialTheme.typography.bodyLarge,
                )
                ShieldText(
                    "These reserved .test domains are blocked by our seed rules. Safe sites like safe-example.test are unaffected.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(16.dp))

            ShieldCard(title = "Limitations worth knowing") {
                ShieldText(
                    "A DNS filter can be bypassed by: (1) DNS-over-HTTPS pinned clients, (2) apps with hardcoded resolvers, " +
                        "(3) a second VPN. It cannot see inside encrypted streams. If you are in danger, delete the app.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(16.dp))

            ShieldCard(title = "If you are in crisis") {
                ShieldText(
                    "If you might act on thoughts of harming yourself or someone else, " +
                        "call your local emergency number now: " +
                        dev.gamblock.core.model.CrisisDirectory.EMERGENCY_GUIDANCE + ".",
                    style = MaterialTheme.typography.bodyMedium,
                )
                ShieldText(
                    "The helplines below are stored on your device and work without internet. Availability varies; if one number does not connect, try the next.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(16.dp))

            CrisisDirectorySection(context = context)

            Spacer(Modifier.height(16.dp))

            ShieldButton(
                text = "Report an issue",
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("https://github.com/Vikasvicxy/Gambling-Protection-Apk/issues")
                    }
                    runCatching { context.startActivity(intent) }
                },
            )
            ShieldText(
                "Opens the public GitHub issue tracker. Please leave personal recovery details out of it.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    })
}

@Composable
private fun rememberPackageInfo(context: android.content.Context) = androidx.compose.runtime.remember {
    runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName to info.versionCode
    }.getOrDefault("?" to -1)
}