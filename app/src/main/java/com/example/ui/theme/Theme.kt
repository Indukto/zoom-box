package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Film-chrome dark palette. Used as the fallback when dynamic color is
 * unavailable (Android < 12 or `dynamicColor = false`) and by the settings
 * screen, which keeps its dark film-chrome background in both light and dark
 * system modes. Amber primary mirrors the app's exposure/white-balance
 * accent so the chrome still feels like a film camera.
 */
internal val FilmDarkColorScheme =
  darkColorScheme(
    primary = Color(0xFFFBBF24),
    onPrimary = Color(0xFF2B1D00),
    primaryContainer = Color(0xFF4A3A10),
    onPrimaryContainer = Color(0xFFFFE4A3),
    secondary = Color(0xFFD6C7A8),
    onSecondary = Color(0xFF38301F),
    secondaryContainer = Color(0xFF504630),
    onSecondaryContainer = Color(0xFFF3E3C3),
    tertiary = Color(0xFF9FC8FF),
    onTertiary = Color(0xFF003257),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE8E1D6),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFE8E1D6),
    surfaceVariant = Color(0xFF4A4438),
    onSurfaceVariant = Color(0xFFCBC4B5),
    surfaceContainerLowest = Color(0xFF0C0C0C),
    surfaceContainerLow = Color(0xFF1A1A1A),
    surfaceContainer = Color(0xFF1E1E1E),
    surfaceContainerHigh = Color(0xFF292929),
    surfaceContainerHighest = Color(0xFF343434),
    outline = Color(0xFF958F81),
    outlineVariant = Color(0xFF4A4438),
  )

private val LightColorScheme =
  lightColorScheme(
    primary = Color(0xFF7A5E00),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFE08E),
    onPrimaryContainer = Color(0xFF251A00),
    secondary = Color(0xFF665F4D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEDE3CC),
    onSecondaryContainer = Color(0xFF211C0E),
    tertiary = Color(0xFF005BAE),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFFF8F1),
    onBackground = Color(0xFF201B13),
    surface = Color(0xFFFFF8F1),
    onSurface = Color(0xFF201B13),
    surfaceVariant = Color(0xFFEDE0CF),
    onSurfaceVariant = Color(0xFF4D4639),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFEF1E2),
    surfaceContainer = Color(0xFFF8EBDB),
    surfaceContainerHigh = Color(0xFFF3E6D6),
    surfaceContainerHighest = Color(0xFFEDE0D0),
    outline = Color(0xFF7F7667),
    outlineVariant = Color(0xFFD0C4B4),
  )

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Dynamic color is available on Android 12+; colors then come from the
  // user's system wallpaper palette.
  dynamicColor: Boolean = true,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }
      darkTheme -> FilmDarkColorScheme
      else -> LightColorScheme
    }

  // MaterialExpressiveTheme is Material You's newest design language: the
  // expressive shape system, motion scheme, and tonal elevation. Null
  // motionScheme/shapes fall back to the library's expressive defaults.
  MaterialExpressiveTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
