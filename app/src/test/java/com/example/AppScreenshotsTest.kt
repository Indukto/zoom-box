package com.example

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Headless README screenshots — no camera or GL surface needed, so they can
 * be regenerated on any machine via `recordRoborazziDebug`:
 *
 * - gallery_empty.png: [GalleryViewer] with no captures ([GalleryEmptyState]).
 * - tutorial_zoom.png: the first-run zoom gesture cue over a black
 *   viewfinder stand-in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppScreenshotsTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun captureGalleryEmpty() {
    val viewModel = CameraViewModel(ApplicationProvider.getApplicationContext())
    composeTestRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        GalleryViewer(
          photos = emptyList(),
          initialIndex = 0,
          viewModel = viewModel,
          onClose = {}
        )
      }
    }
    composeTestRule.onRoot().captureRoboImage("src/test/roborazzi/gallery_empty.png")
  }

  @Test
  fun captureTutorialZoom() {
    ApplicationProvider.getApplicationContext<android.content.Context>()
    composeTestRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
          TutorialOverlay(
            step = TutorialStep.Zoom,
            onAdvance = {},
            onSkipAll = {}
          )
        }
      }
    }
    composeTestRule.onRoot().captureRoboImage("src/test/roborazzi/tutorial_zoom.png")
  }
}
