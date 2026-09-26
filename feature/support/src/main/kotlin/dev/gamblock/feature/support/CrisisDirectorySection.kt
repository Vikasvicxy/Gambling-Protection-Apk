package dev.gamblock.feature.support

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gamblock.core.designsystem.component.ShieldButton
import dev.gamblock.core.designsystem.component.ShieldCard
import dev.gamblock.core.designsystem.component.ShieldText
import dev.gamblock.core.model.CrisisContact
import dev.gamblock.core.model.CrisisDirectory

/**
 * Offline crisis directory. Every action hands the number straight to the
 * system dialer or SMS app, so no data leaves the device and the user sees
 * the number before confirming.
 */
@Composable
fun CrisisDirectorySection(context: Context) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            CrisisDirectory.contacts
        } else {
            CrisisDirectory.contacts.filter { contact ->
                contact.name.contains(trimmed, ignoreCase = true) ||
                    contact.region.contains(trimmed, ignoreCase = true)
            }
        }
    }

    Column {
        ShieldCard(title = "Crisis & helpline directory") {
            androidx.compose.material3.OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { androidx.compose.material3.Text("Search region or name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(8.dp))
        filtered.forEach { contact ->
            CrisisContactCard(context = context, contact = contact)
            Spacer(Modifier.height(8.dp))
        }
        if (filtered.isEmpty()) {
            ShieldText("No matching helpline found.", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CrisisContactCard(context: Context, contact: CrisisContact) {
    ShieldCard(title = contact.name) {
        ShieldText(
            "${contact.region} - ${contact.hours}",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
        )
        contact.phone?.let { phone ->
            Spacer(Modifier.height(4.dp))
            ShieldText(phone, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShieldButton(
                    text = "Call",
                    onClick = { dial(context, phone) },
                    modifier = Modifier.weight(1f),
                )
                ShieldButton(
                    text = "SMS",
                    onClick = { sendText(context, phone) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        contact.textLine?.let { line ->
            Spacer(Modifier.height(4.dp))
            ShieldButton(
                text = "Start text chat ($line)",
                onClick = { startTextChat(context, line) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        contact.website?.let { site ->
            Spacer(Modifier.height(4.dp))
            ShieldButton(
                text = "Open website",
                onClick = { openUrl(context, site) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun dial(context: Context, phone: String) {
    val intent = Intent(Intent.ACTION_DIAL).apply {
        data = Uri.parse("tel:${phone.filter { it.isDigit() || it == '+' }}")
    }
    runCatching { context.startActivity(intent) }
}

private fun sendText(context: Context, phone: String) {
    val intent = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse("smsto:${phone.filter { it.isDigit() || it == '+' }}")
    }
    runCatching { context.startActivity(intent) }
}

private fun startTextChat(context: Context, textLine: String) {
    val normalized = textLine.uppercase()
    val payload = when {
        normalized.contains("741741") -> "HOME to 741741"
        normalized.contains("85258") -> "SHOUT to 85258"
        else -> textLine
    }
    val intent = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse("smsto:")
        putExtra("sms_body", payload)
    }
    runCatching { context.startActivity(intent) }
}

private fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    runCatching { context.startActivity(intent) }
}
