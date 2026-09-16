package dev.gamblock.core.designsystem.component

import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.gamblock.core.designsystem.theme.ShieldPalette

@Composable
fun ShieldButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: @Composable (() -> Unit)? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minWidth = 120.dp, minHeight = 48.dp),
        enabled = enabled && !loading,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        ),
        shape = MaterialTheme.shapes.medium,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier,
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            icon?.invoke()
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
fun ShieldOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minWidth = 120.dp, minHeight = 48.dp),
        enabled = enabled && !loading,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.primary,
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        ),
        shape = MaterialTheme.shapes.medium,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier,
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Text(text = text, style = MaterialTheme.typography.labelLarge)
        }
    }
}