package dev.gamblock.core.designsystem.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val ShieldLightColorScheme = lightColorScheme(
    primary = ShieldPalette.Blue,
    onPrimary = ShieldPalette.White,
    primaryContainer = ShieldPalette.BlueSurface,
    onPrimaryContainer = ShieldPalette.BlueDark,
    secondary = ShieldPalette.BlueLight,
    onSecondary = ShieldPalette.Black,
    background = ShieldPalette.OffWhite,
    onBackground = ShieldPalette.Gray900,
    surface = ShieldPalette.White,
    onSurface = ShieldPalette.Gray900,
    surfaceVariant = ShieldPalette.Gray100,
    onSurfaceVariant = ShieldPalette.Gray700,
    error = ShieldPalette.Red,
    onError = ShieldPalette.White,
    outline = ShieldPalette.Gray300,
)

private val ShieldDarkColorScheme = darkColorScheme(
    primary = ShieldPalette.BlueLight,
    onPrimary = ShieldPalette.BlueDark,
    primaryContainer = ShieldPalette.BlueDark,
    onPrimaryContainer = ShieldPalette.BlueLight,
    secondary = ShieldPalette.BlueLight,
    onSecondary = ShieldPalette.Black,
    background = ShieldPalette.Gray900,
    onBackground = ShieldPalette.OffWhite,
    surface = ShieldPalette.Gray900,
    onSurface = ShieldPalette.OffWhite,
    surfaceVariant = ShieldPalette.Gray700,
    onSurfaceVariant = ShieldPalette.Gray200,
    error = ShieldPalette.RedLight,
    onError = ShieldPalette.Black,
    outline = ShieldPalette.Gray500,
)

@Composable
fun ShieldTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) ShieldDarkColorScheme else ShieldLightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = ShieldTypography,
        content = content,
    )
}