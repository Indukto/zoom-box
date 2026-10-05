package com.example

import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalHapticFeedback
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    voteHighRefreshRate()
    setContent {
      MyApplicationTheme {
        val haptic = LocalHapticFeedback.current
        CompositionLocalProvider(
          LocalHapticFeedback provides SafeHapticFeedback(haptic)
        ) {
          CameraUi()
        }
      }
    }
  }

  /**
   * Votes for the panel's top refresh rate (90/120Hz…) so the Compose UI
   * and its animations render above 60fps on capable phones. Without an
   * explicit vote many OEM schedulers keep the app at 60Hz to save power.
   * Uses WindowManager.LayoutParams.preferredRefreshRate — the documented
   * window-level vote, applied to every surface in the window (there is no
   * Window.setFrameRate; setFrameRate() lives on Surface). No-op on
   * 60Hz-only panels, and wrapped in try/catch so a display hint can never
   * block startup. Battery-saver/thermal throttling can still cap the rate
   * afterwards, and panels needing a hard mode switch may stay at 60Hz.
   */
  private fun voteHighRefreshRate() {
    try {
      val display = getSystemService(DisplayManager::class.java)
        ?.getDisplay(Display.DEFAULT_DISPLAY) ?: return
      val topFps = display.supportedModes.maxOfOrNull { it.refreshRate } ?: return
      if (topFps <= 60.5f) return // Panel tops out at 60Hz — nothing to vote for.
      @Suppress("DEPRECATION")
      window.attributes = window.attributes.apply { preferredRefreshRate = topFps }
    } catch (_: Exception) {
      // Refresh hint failed — the system keeps its default; ignore.
    }
  }
}
