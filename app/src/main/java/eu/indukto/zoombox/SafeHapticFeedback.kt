package eu.indukto.zoombox

import android.util.Log
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/**
 * A wrapper for [HapticFeedback] that catches [IllegalArgumentException] which can be thrown
 * by the system on certain devices (e.g. "Error setting amplitude to -1.0").
 */
class SafeHapticFeedback(private val delegate: HapticFeedback) : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        try {
            delegate.performHapticFeedback(hapticFeedbackType)
        } catch (e: IllegalArgumentException) {
            // Log the error but don't crash the app
            Log.w("SafeHapticFeedback", "Caught system haptic error: ${e.message}")
        } catch (e: Exception) {
            // Catch other potential platform-specific issues
            Log.e("SafeHapticFeedback", "Unexpected haptic error", e)
        }
    }
}

