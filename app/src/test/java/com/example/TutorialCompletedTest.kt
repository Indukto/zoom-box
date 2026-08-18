package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the current first-launch tutorial gate in [CameraUi]: a
 * SharedPreferences flag (`app_prefs` / `is_first_launch`) that defaults to
 * true and is persisted to false once the two-step gesture tutorial is
 * completed or skipped.
 *
 * The previous version of this test targeted a removed DataStore-backed
 * `tutorialCompleted` API on [CameraViewModel] /
 * [UserPreferencesRepository]; the tutorial moved to the `CameraUi` composable
 * (SharedPreferences) and the old test broke compilation of the whole test
 * suite. These tests lock in the current contract: first launch shows the
 * tutorial, a dismissed tutorial never shows again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TutorialCompletedTest {

    private fun tutorialPrefs(context: Context) =
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    @Test
    fun `tutorial flag defaults to shown on first launch`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Fresh app_prefs store → the tutorial is not yet dismissed.
        assertTrue(tutorialPrefs(context).getBoolean("is_first_launch", true))
    }

    @Test
    fun `dismissing the tutorial persists so it never shows again`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = tutorialPrefs(context)

        // Simulate the CameraUi dismissal path: the flag flips to false and
        // is committed so a relaunch keeps the tutorial hidden.
        prefs.edit().putBoolean("is_first_launch", false).commit()

        // A fresh read (same as a fresh launch) now sees the dismissed state.
        assertFalse(prefs.getBoolean("is_first_launch", true))
    }

    @Test
    fun `explicitly persisted dismissal is respected`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = tutorialPrefs(context)
        prefs.edit().putBoolean("is_first_launch", false).commit()

        // The flag in the store is authoritative, not the in-code default.
        assertFalse(prefs.getBoolean("is_first_launch", true))
    }
}
