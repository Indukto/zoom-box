package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.example.R

/**
 * Inter, bundled in res/font (latin subsets). Used as the single UI
 * typeface: regular/medium for body, bold for labels and readouts.
 */
val Inter = FontFamily(
  Font(R.font.inter_regular, FontWeight.Normal),
  Font(R.font.inter_medium, FontWeight.Medium),
  Font(R.font.inter_bold, FontWeight.Bold)
)

// Material typography with every style on Inter. Explicit per-style copies
// (rather than a single default) so M3 components, dialogs, and sheets all
// inherit the app typeface even where they read a specific text style.
private val fallback = Typography()
val Typography =
  Typography(
    displayLarge = fallback.displayLarge.copy(fontFamily = Inter),
    displayMedium = fallback.displayMedium.copy(fontFamily = Inter),
    displaySmall = fallback.displaySmall.copy(fontFamily = Inter),
    headlineLarge = fallback.headlineLarge.copy(fontFamily = Inter),
    headlineMedium = fallback.headlineMedium.copy(fontFamily = Inter),
    headlineSmall = fallback.headlineSmall.copy(fontFamily = Inter),
    titleLarge = fallback.titleLarge.copy(fontFamily = Inter),
    titleMedium = fallback.titleMedium.copy(fontFamily = Inter),
    titleSmall = fallback.titleSmall.copy(fontFamily = Inter),
    bodyLarge = fallback.bodyLarge.copy(fontFamily = Inter),
    bodyMedium = fallback.bodyMedium.copy(fontFamily = Inter),
    bodySmall = fallback.bodySmall.copy(fontFamily = Inter),
    labelLarge = fallback.labelLarge.copy(fontFamily = Inter),
    labelMedium = fallback.labelMedium.copy(fontFamily = Inter),
    labelSmall = fallback.labelSmall.copy(fontFamily = Inter)
  )
