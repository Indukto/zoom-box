package com.example

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import com.example.zoom.AspectRatio
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("ZoomBox Camera", appName)
  }

  @Test
  fun test_viewmodel_initial_states() {
    val viewModel = CameraViewModel(ApplicationProvider.getApplicationContext())
    assertFalse(viewModel.showGridLines.value)
    assertFalse(viewModel.showGalleryFrame.value)
    assertEquals(AspectRatio.RATIO_4_3, viewModel.aspectRatio.value)
  }

  @Test
  fun test_viewmodel_toggle_gallery_frame() {
    val viewModel = CameraViewModel(ApplicationProvider.getApplicationContext())
    // The film-card frame around gallery photos must default to OFF.
    assertFalse(viewModel.showGalleryFrame.value)
    viewModel.toggleGalleryFrame()
    assertTrue(viewModel.showGalleryFrame.value)
    viewModel.toggleGalleryFrame()
    assertFalse(viewModel.showGalleryFrame.value)
  }

  @Test
  fun test_bake_gallery_frame_enlarges_photo_with_cream_background() {
    val viewModel = CameraViewModel(ApplicationProvider.getApplicationContext())
    val photo = Bitmap.createBitmap(360, 480, Bitmap.Config.ARGB_8888)
    val framed = viewModel.bakeGalleryFrame(
      photo = photo,
      focalLength = 24,
      exposureTime = 1.0 / 1000.0,
      iso = 100
    )
    // The frame adds padding + a footer strip on both axes.
    assertTrue(framed.width > photo.width)
    assertTrue(framed.height > photo.height)
    // Top-left corner is the cream card background, not the photo.
    assertEquals(0xFFF9FAF9.toInt(), framed.getPixel(0, 0))
    photo.recycle()
    framed.recycle()
  }

  @Test
  fun test_viewmodel_toggle_grid_lines() {
    val viewModel = CameraViewModel(ApplicationProvider.getApplicationContext())
    assertFalse(viewModel.showGridLines.value)
    viewModel.toggleGridLines()
    assertTrue(viewModel.showGridLines.value)
    viewModel.toggleGridLines()
    assertFalse(viewModel.showGridLines.value)
  }

  @Test
  fun test_viewmodel_set_aspect_ratio() {
    val viewModel = CameraViewModel(ApplicationProvider.getApplicationContext())
    assertEquals(AspectRatio.RATIO_4_3, viewModel.aspectRatio.value)
    viewModel.setAspectRatio(AspectRatio.RATIO_1_1)
    assertEquals(AspectRatio.RATIO_1_1, viewModel.aspectRatio.value)
    viewModel.setAspectRatio(AspectRatio.RATIO_4_3)
    assertEquals(AspectRatio.RATIO_4_3, viewModel.aspectRatio.value)
  }

  @Test
  fun test_ui_settings_menu_interaction() {
    val viewModel = CameraViewModel(ApplicationProvider.getApplicationContext())

    composeTestRule.setContent {
      var showSettings by remember { mutableStateOf(false) }
      if (showSettings) {
        SettingsScreen(viewModel = viewModel, onClose = { showSettings = false })
      } else {
        CameraActiveScreen(viewModel = viewModel, onOpenSettings = { showSettings = true })
      }
    }

    // Verify initial state
    assertFalse(viewModel.showGridLines.value)
    assertEquals(AspectRatio.RATIO_4_3, viewModel.aspectRatio.value)

    // Find and click the grid lines item to toggle it (on main screen)
    val gridLinesItem = composeTestRule.onNodeWithTag("grid_overlay_button")
    gridLinesItem.assertExists()
    gridLinesItem.performClick()

    // State should now be updated (Grid lines enabled)
    assertTrue(viewModel.showGridLines.value)

    // Find and click the settings button to show the menu
    val settingsBtn = composeTestRule.onNodeWithTag("settings_menu_button")
    settingsBtn.assertExists()
    settingsBtn.performClick()

    // Find and click the aspect ratio item to toggle it to 1:1 (in settings)
    val aspectRatioItem = composeTestRule.onNodeWithTag("aspect_ratio_chip_1:1")
    aspectRatioItem.assertExists()
    aspectRatioItem.performClick()

    // State should now be updated (Aspect ratio is 1:1)
    assertEquals(AspectRatio.RATIO_1_1, viewModel.aspectRatio.value)
  }

  @Test
  fun test_viewmodel_cycle_lens_is_noop_on_front_camera() {
    // Front camera has only one lens; cycling the bubble should not change
    // _selectedLensRole. Locks in the front-camera guard so a future
    // refactor can't silently re-introduce the "13/24/116 cycle on selfie
    // mode" bug.
    val viewModel = CameraViewModel(ApplicationProvider.getApplicationContext())
    viewModel.toggleCamera()
    assertTrue(viewModel.isFrontCamera.value)
    val before = viewModel.selectedLensRole.value
    viewModel.cycleLens()
    assertEquals(before, viewModel.selectedLensRole.value)
  }
}
