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
    val viewModel = cameraViewModelAtDefaults()
    composeTestRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        SettingsScreen(viewModel = viewModel, onClose = {})
      }
    }
    settle(viewModel)
    composeTestRule.onRoot().captureRoboImage("src/test/roborazzi/settings_screen_dark.png")
  }

  @Test
  fun captureSettingsScreenLight() {
    val viewModel = cameraViewModelAtDefaults()
    composeTestRule.setContent {
      MyApplicationTheme(darkTheme = false) {
        SettingsScreen(viewModel = viewModel, onClose = {})
      }
    }
    settle(viewModel)
    composeTestRule.onRoot().captureRoboImage("src/test/roborazzi/settings_screen_light.png")
  }

  private fun cameraViewModelAtDefaults() =
    CameraViewModel(ApplicationProvider.getApplicationContext())

  /**
   * Pins the page to the defaults before it is captured.
   *
   * `preferencesDataStore` is a process-wide delegate, so every test in the
   * JVM shares one DataStore whose file path was fixed by whichever test
   * touched it first (usually a *deleted* Robolectric temp directory by the
   * time this runs). The page renders persisted state — the double-exposure
   * switch, the output resolution — so without this the golden would encode
   * whatever a previously-run test happened to toggle, and would flip the
   * moment the suite's order changed. `waitForIdle` first lets the ViewModel's
   * own settings load land, so the reset is what the capture sees.
   */
  private fun settle(viewModel: CameraViewModel) {
    composeTestRule.waitForIdle()
    viewModel.resetSettingsToDefaults()
    // The lens is persisted too and resetSettingsToDefaults leaves it alone
    // (a real user's camera choice should survive a settings reset), while the
    // RAW row's subtitle reads it: front-camera state renders "Not available
    // on the front camera" instead of "This lens doesn't support RAW".
    if (viewModel.isFrontCamera.value) viewModel.toggleCamera()
    composeTestRule.waitForIdle()
  }
}
