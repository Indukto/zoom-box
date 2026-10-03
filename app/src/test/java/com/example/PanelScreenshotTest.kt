package com.example

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.ui.theme.MyApplicationTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.github.takahirom.roborazzi.captureRoboImage
import dev.chrisbanes.haze.rememberHazeState

/**
 * Headless screenshots of the floating control panels (no camera needed).
 * Used to eyeball the Material 3 exposure slider and the white-balance
 * panel after restyles.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PanelScreenshotTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun captureExposurePanel() {
    ApplicationProvider.getApplicationContext<android.content.Context>()
    composeTestRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        Box(
          Modifier
            .background(Color.Black)
            .padding(16.dp)
        ) {
          MorphedPanelChrome(hazeState = rememberHazeState()) {
            ExposurePanel(exposure = 1.5f, onValueChange = {})
          }
        }
      }
    }
    composeTestRule.onRoot().captureRoboImage("src/test/roborazzi/exposure_panel.png")
  }

  @Test
  fun captureWhiteBalancePanel() {
    ApplicationProvider.getApplicationContext<android.content.Context>()
    composeTestRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        Box(
          Modifier
            .background(Color.Black)
            .padding(16.dp)
        ) {
          MorphedPanelChrome(hazeState = rememberHazeState()) {
            WhiteBalancePanel(temperature = 0.5f, tint = 0.5f, onValueChange = { _, _ -> })
          }
        }
      }
    }
    composeTestRule.onRoot().captureRoboImage("src/test/roborazzi/white_balance_panel.png")
  }
}
