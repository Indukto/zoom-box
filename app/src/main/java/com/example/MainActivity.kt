package com.example

import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalHapticFeedback
import com.example.ui.theme.MyApplicationTheme

/**
 * Installed by [MainActivity.installVolumeShutterDispatcher] while the camera
 * screen is foregrounded. Receives key events BEFORE the Activity's own * dispatch (the view hierarchy may mark them consumed for focus/navigation),
 * so the volume-shutter behavior cannot be starved by Compose focus churn.
 */
fun interface VolumeKeyInterceptor {
  /** @return true when the event was consumed (never reaches the system volume handling). */
  fun onKeyEvent(event: KeyEvent): Boolean
}

class MainActivity : ComponentActivity() {
  @Volatile
  private var volumeShutterInterceptor: VolumeKeyInterceptor? = null

  /**
   * Wires the volume hardware keys to the shutter while the camera screen is
   * active. Called from [CameraUi]'s composition (a DisposableEffect keyed on   * composition lifetime) so the hook exists exactly while the camera is the   * foreground surface and is removed on dispose.
   */
  fun installVolumeShutterDispatcher(interceptor: VolumeKeyInterceptor) {
    volumeShutterInterceptor = interceptor
  }

  fun uninstallVolumeShutterDispatcher() {
    volumeShutterInterceptor = null
  }

  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    // Volume up/down fire the shutter when a camera-screen interceptor is
    // installed AND the event is a fresh (non-repeat) press. All other keys,
    // repeat events and released actions fall through to normal dispatch.    // Consuming here also suppresses the system volume HUD over the viewfinder.    val interceptor = volumeShutterInterceptor
    if (interceptor != null &&
      (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
      event.action == KeyEvent.ACTION_DOWN &&
      event.repeatCount == 0 &&
      interceptor.onKeyEvent(event)    ) {
      return true
    }
    return super.dispatchKeyEvent(event)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    voteHighRefreshRate()
    // Temporary startup-jank profiler (debug builds only, no-op in release).
    // See JankMonitor — delete once the cold-start lag is diagnosed.
    JankMonitor.start()
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
