package com.example

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot coverage for [LiquidGlassZoomSelector].
 *
 * The glass *body* (refraction / rim / tint) is drawn by the GL effect pass
 * and cannot run on the JVM, so these tests pin down what Compose owns: the
 * label readout and, when the GL shader is inactive (Normal preset), the
 * frosted fallback chrome. Both states render over a stand-in "camera" color.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiquidGlassZoomSelectorScreenshotTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun captureFallbackChromeWhenGlassInactive() {
    composeTestRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        Box(
          modifier = Modifier.size(240.dp, 160.dp).background(Color(0xFF2A2A2A)),
          contentAlignment = Alignment.Center
        ) {
          LiquidGlassZoomSelector(
            zoomRatio = 2.35f,
            focalLengthMm = 58,
            glassActive = false
          )
        }
      }
    }
    composeTestRule.onNodeWithTag("liquid_glass_zoom_selector").assertExists()
    composeTestRule.onNodeWithText("2.4\u00d7").assertExists()
    composeTestRule.onNodeWithText("58mm").assertExists()
    composeTestRule.onRoot()
      .captureRoboImage("src/test/roborazzi/liquid_glass_zoom_selector_fallback.png")
  }

  @Test
  fun captureGlassModeLabelsOnly() {
    composeTestRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        Box(
          modifier = Modifier.size(240.dp, 160.dp).background(Color(0xFF2A2A2A)),
          contentAlignment = Alignment.Center
        ) {
          LiquidGlassZoomSelector(
            zoomRatio = 1.0f,
            focalLengthMm = 24,
            glassActive = true
          )
        }
      }
    }
    composeTestRule.onNodeWithTag("liquid_glass_zoom_selector").assertExists()
    composeTestRule.onNodeWithText("1.0\u00d7").assertExists()
    composeTestRule.onNodeWithText("24mm").assertExists()
    composeTestRule.onRoot()
      .captureRoboImage("src/test/roborazzi/liquid_glass_zoom_selector_glass.png")
  }
}
