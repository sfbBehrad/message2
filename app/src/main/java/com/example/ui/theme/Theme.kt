package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = TelegramBlue,
    onPrimary = Color.White,
    primaryContainer = TelegramBlueDark,
    onPrimaryContainer = Color.White,
    secondary = TelegramAmber,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF382914),
    onSecondaryContainer = Color(0xFFFFDDB3),
    tertiary = TelegramGreen,
    background = TelegramBgDark,
    onBackground = TextBright,
    surface = TelegramSurface,
    onSurface = TextBright,
    surfaceVariant = TelegramSurfaceHover,
    onSurfaceVariant = TextMuted,
    outline = BorderSubtle
)

@Composable
fun RadioBridgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
