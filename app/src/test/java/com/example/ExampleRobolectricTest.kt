package com.example

import android.app.Application
import androidx.activity.ComponentActivity
import android.content.Context
import android.graphics.Bitmap
import com.example.zoom.AspectRatio
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExampleRobolectricTest {

  @get:Rule
  val composeTestRule = createAndroidComposeRule<ComponentActivity>()

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

    // Grid toggle: the grid_overlay_button just calls toggleGridLines(), so
    // exercise the same state transition at the ViewModel level. The full
    // camera screen (CameraActiveScreen) can't be composed headlessly — it
    // hosts GLSurfaceView / CameraX surfaces that Robolectric can't provide.
    assertFalse(viewModel.showGridLines.value)
    viewModel.toggleGridLines()
    assertTrue(viewModel.showGridLines.value)

    // Settings screen is composable headlessly; verify the aspect-ratio chip
    // wiring flips the persisted state like the real click does. The initial
    // value is intentionally not assumed to be 4:3 — DataStore persists across
    // tests in this class, so the chip is chosen to differ from whatever the
    // async settings load settled on.
    composeTestRule.setContent {
      SettingsScreen(viewModel = viewModel, onClose = {})
    }

    val current = viewModel.aspectRatio.value
    val target = AspectRatio.entries.first { it != current }
    val aspectRatioItem =
        composeTestRule.onNodeWithTag("aspect_ratio_chip_${target.label}")
    aspectRatioItem.assertExists()
    aspectRatioItem.performClick()
    assertEquals(target, viewModel.aspectRatio.value)
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
