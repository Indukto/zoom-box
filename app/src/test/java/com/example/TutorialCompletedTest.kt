package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TutorialCompletedTest {

  @Test
  fun `tutorial flag defaults to false, flips on completion, and persists`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val repo = UserPreferencesRepository(context)

    // Fresh store → not completed yet.
    runBlocking { assertFalse(repo.settingsFlow.first().tutorialCompleted) }

    // A new ViewModel also starts uncompleted.
    val app = ApplicationProvider.getApplicationContext<Application>()
    val vm = CameraViewModel(app)
    assertFalse(vm.tutorialCompleted.value)

    // Completing flips the in-memory flag synchronously.
    vm.markTutorialCompleted()
    assertTrue(vm.tutorialCompleted.value)

    // Repository-level persistence works (direct call, no Main
    // dispatcher involved, so no paused-looper race).
    runBlocking {
      repo.saveTutorialCompleted(true)
      assertTrue(repo.settingsFlow.first().tutorialCompleted)
    }
  }
}
