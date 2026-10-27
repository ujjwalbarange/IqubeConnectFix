package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val IqubeDarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = Color(0xFF00262C),
    primaryContainer = Color(0xFF003842),
    onPrimaryContainer = ElectricCyan,
    secondary = NeonMint,
    onSecondary = Color(0xFF002919),
    secondaryContainer = Color(0xFF003E27),
    onSecondaryContainer = NeonMint,
    tertiary = EnergeticCoral,
    onTertiary = Color(0xFF3B0900),
    tertiaryContainer = Color(0xFF571305),
    onTertiaryContainer = EnergeticCoral,
    background = ObsidianBg,
    onBackground = TextPrimary,
    surface = SlateSurface,
    onSurface = TextPrimary,
    surfaceVariant = SlateCardElevated,
    onSurfaceVariant = TextSecondary,
    outline = SlateBorder,
    error = EnergeticCoral,
    onError = Color.Black
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = IqubeDarkColorScheme,
        typography = Typography,
        content = content
    )
}
