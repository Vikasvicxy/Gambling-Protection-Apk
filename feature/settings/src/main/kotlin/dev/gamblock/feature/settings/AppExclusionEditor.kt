package dev.gamblock.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import dev.gamblock.core.designsystem.component.ShieldButton
import dev.gamblock.core.designsystem.component.ShieldCard
import dev.gamblock.core.designsystem.component.ShieldText
import dev.gamblock.core.designsystem.theme.ShieldPalette
import dev.gamblock.core.model.InstalledAppCandidate
import dev.gamblock.core.model.InstalledAppFilter

/** Plain-language statement of what an exemption costs, shown before the picker. */
const val EXCLUSION_EXPLAINER: String =
    "Allows critical financial or work apps to bypass the VPN if they block VPN connections. " +
        "Protected DNS filtering will not apply to these apps, so a gambling site reached " +
        "inside an excluded app will not be blocked."

@Composable
fun AppExclusionEditor(
    candidates: List<InstalledAppCandidate>,
    excludedPackages: Set<String>,
    onToggle: (String) -> Unit,
    onManualAdd: (String) -> Unit,
    onRemoveAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var manualEntry by remember { mutableStateOf("") }
    val context = LocalContext.current

    val visible = remember(candidates, query) {
        InstalledAppFilter.userFacing(candidates, query)
    }

    ShieldCard(modifier = modifier, title = "Bypass Protection for Selected Apps") {
        ShieldText(
            text = EXCLUSION_EXPLAINER,
            style = MaterialTheme.typography.bodySmall,
            color = ShieldPalette.Orange,
        )
        ShieldText(
            text = "${excludedPackages.size} app(s) currently bypass DNS filtering.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (excludedPackages.isNotEmpty()) {
            ShieldButton(
                text = "Remove all exemptions",
                onClick = onRemoveAll,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { ShieldText("Search apps or package names") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )

        // Escape hatch for apps the platform will not show us. Without
        // QUERY_ALL_PACKAGES the picker is a partial list, and a user whose bank
        // is missing would otherwise have no way to exempt it at all.
        OutlinedTextField(
            value = manualEntry,
            onValueChange = { manualEntry = it },
            label = { ShieldText("Add by package name (e.g. com.yourbank.app)") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
        ShieldButton(
            text = "Add package",
            onClick = {
                val trimmed = manualEntry.trim()
                if (trimmed.isNotEmpty()) {
                    onManualAdd(trimmed)
                    manualEntry = ""
                }
            },
            enabled = manualEntry.isNotBlank(),
        )

        if (candidates.isEmpty()) {
            ShieldText(
                text = "No apps to show yet. Add one by package name above.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        } else if (visible.isEmpty()) {
            ShieldText(
                text = "No app matches \"$query\".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(visible, key = { it.packageName }) { candidate ->
                    AppExclusionRow(
                        candidate = candidate,
                        excluded = candidate.packageName in excludedPackages,
                        loadIcon = { loadAppIcon(context, candidate.packageName) },
                        onClick = { onToggle(candidate.packageName) },
                    )
                }
            }
        }

        ShieldText(
            text = "Shield only shows apps the system allows it to see, so this list may be " +
                "incomplete. Changes take effect the next time protection reconnects.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun AppExclusionRow(
    candidate: InstalledAppCandidate,
    excluded: Boolean,
    loadIcon: () -> android.graphics.Bitmap?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val bitmap = remember(candidate.packageName) { loadIcon() }
        if (bitmap != null) {
            Image(bitmap = bitmap)
        } else {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            ShieldText(
                text = candidate.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ShieldText(
                text = candidate.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = if (excluded) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = if (excluded) "Excluded" else "Not excluded",
            tint = if (excluded) {
                ShieldPalette.Orange
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun Image(bitmap: android.graphics.Bitmap) {
    androidx.compose.foundation.Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier.size(40.dp),
    )
}

private fun loadAppIcon(context: android.content.Context, packageName: String) = runCatching {
    context.packageManager.getApplicationIcon(packageName).toBitmap(width = 80, height = 80)
}.getOrNull()
