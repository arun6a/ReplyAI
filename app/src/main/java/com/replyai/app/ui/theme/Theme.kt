package com.replyai.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = Emerald,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = EmeraldLight,
    onPrimaryContainer = androidx.compose.ui.graphics.Color.White,
    secondary = EmeraldDark,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    error = StatusError,
)

private val DarkColors = darkColorScheme(
    primary = Emerald,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = EmeraldDark,
    onPrimaryContainer = androidx.compose.ui.graphics.Color.White,
    secondary = EmeraldLight,
    onSecondary = androidx.compose.ui.graphics.Color.Black,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    error = StatusError,
)

@Composable
fun ReplyAITheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        typography = ReplyAITypography,
        content = content,
    )
}
