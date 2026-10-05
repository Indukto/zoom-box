package com.example.zoom

import androidx.compose.runtime.Stable

/**
 * Photo capture aspect ratios. Each value carries the [heightToWidth] multiplier
 * that is used both to size the on-screen zoom-box and to crop the saved JPEG.
 *
 * Ratios are expressed in portrait orientation (height > width when held in
 * the camera's natural vertical grip). The 4:3 sensor ratio is therefore
 * recorded as `height = 4/3 × width ≈ 1.35f`; the 3:2 portrait variant
 * records `height = 3/2 × width = 1.5f`; the 16:9 portrait variant records
 * `height = 16/9 × width ≈ 1.78f`; the 1:1 square crops evenly.
 *
 * Listed in display order so the UI can iterate [values] without an extra
 * display-name map.
 */
@Stable
enum class AspectRatio(val label: String, val heightToWidth: Float) {
    /** Standard sensor portrait — 3:4 landscape/portrait equivalent. */
    RATIO_4_3("4:3", 1.35f),
    /** Taller portrait crop — 2:3 landscape/portrait equivalent. */
    RATIO_3_2("3:2", 1.5f),
    /** Extra-tall portrait crop — 9:16 landscape/portrait equivalent. */
    RATIO_16_9("16:9", 16f / 9f),
    /** Square — Instagram-style 1:1 crop. */
    RATIO_1_1("1:1", 1.0f);

    companion object {
        /** Default aspect ratio used when no user preference is set. */
        val DEFAULT = RATIO_4_3
    }
}
