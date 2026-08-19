package com.example

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.example.ui.theme.MyApplicationTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.github.takahirom.roborazzi.captureRoboImage

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenshotTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun captureSettingsScreenDark() {
    val viewModel = CameraViewModel(ApplicationProvider.getApplicationContext())
    composeTestRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        SettingsScreen(viewModel = viewModel, onClose = {})
      }
    }
    composeTestRule.onRoot().captureRoboImage("src/test/roborazzi/settings_screen_dark.png")
  }

  @Test
  fun captureSettingsScreenLight() {
    val viewModel = CameraViewModel(ApplicationProvider.getApplicationContext())
    composeTestRule.setContent {
      MyApplicationTheme(darkTheme = false) {
        SettingsScreen(viewModel = viewModel, onClose = {})
      }
    }
    composeTestRule.onRoot().captureRoboImage("src/test/roborazzi/settings_screen_light.png")
  }
}
