package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme =
  lightColorScheme(
    primary = EditorialNavy,
    onPrimary = WarmPaper,
    primaryContainer = EditorialSkyContainer,
    onPrimaryContainer = EditorialOnSkyContainer,
    secondary = EditorialNavyLight,
    onSecondary = WarmPaper,
    secondaryContainer = WarmPaperElevated,
    onSecondaryContainer = InkCharcoal,
    background = WarmAlabaster,
    onBackground = InkCharcoal,
    surface = WarmPaper,
    onSurface = InkCharcoal,
    surfaceVariant = WarmPaperElevated,
    onSurfaceVariant = InkMuted,
    outline = WarmOutline,
    outlineVariant = WarmOutline,
    error = PdfCrimson,
    onError = WarmPaper,
  )

private val DarkColorScheme =
  darkColorScheme(
    primary = DarkAccentBlue,
    onPrimary = DarkSlateBg,
    primaryContainer = DarkAccentContainer,
    onPrimaryContainer = DarkTextPrimary,
    secondary = EditorialSkyContainer,
    onSecondary = DarkSlateBg,
    secondaryContainer = DarkSlateElevated,
    onSecondaryContainer = DarkTextPrimary,
    background = DarkSlateBg,
    onBackground = DarkTextPrimary,
    surface = DarkSlateSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSlateElevated,
    onSurfaceVariant = DarkTextMuted,
    outline = DarkSlateOutline,
    outlineVariant = DarkSlateOutline,
    error = PdfCrimson,
    onError = WarmPaper,
  )

@Composable
fun DocuViewTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

  MaterialTheme(
    colorScheme = colorScheme,
    typography = AppTypography,
    content = content,
  )
}
