package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build

/**
 * Draw the film-card frame onto a photo so it ships inside the saved JPEG
 * (visible in every gallery app), matching the white card the in-app
 * gallery draws when the Photo Frame setting is enabled: a cream
 * background, a padded photo, and a footer with the device name (left)
 * and an EXIF summary — focal length, shutter speed, ISO (right).
 *
 * Sizes are scaled from the photo width using a 360 dp reference width (a
 * typical phone layout), so a 3 MP and a full-resolution capture both get
 * proportionally identical frames. Orientation is intentionally omitted
 * from the footer — saved photos are always tagged NORMAL.
 */
internal fun bakeGalleryFrame(
    photo: Bitmap,
    focalLength: Int,
    exposureTime: Double,
    iso: Int
): Bitmap {
    val scale = photo.width / 360f
    val pad = (12f * scale).toInt().coerceAtLeast(1)
    val spacer = (14f * scale).toInt().coerceAtLeast(1)
    val textSize = (12f * scale).toInt().coerceAtLeast(1)

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.textSize = textSize.toFloat()
        // Black at 55 % alpha — matches the gallery footer text.
        color = 0x8C000000.toInt()
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    }
    val fm = textPaint.fontMetrics
    val textHeight = (fm.descent - fm.ascent).toInt()

    val frameW = photo.width + 2 * pad
    val frameH = pad + photo.height + spacer + textHeight + pad

    val frame = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(frame)
    // Cream card background, same colour as the gallery card.
    canvas.drawColor(0xFFF9FAF9.toInt())
    canvas.drawBitmap(photo, pad.toFloat(), pad.toFloat(), null)

    val baseline = pad + photo.height + spacer - fm.ascent

    // Device name, left-aligned.
    canvas.drawText(Build.MODEL, pad.toFloat(), baseline, textPaint)

    // EXIF summary, right-aligned (formatted exactly like the gallery
    // footer: "24mm  1/1000s  ISO 100").
    val shutterSpeed = if (exposureTime > 0.0) {
        if (exposureTime < 1.0) { val denom = kotlin.math.round(1.0 / exposureTime).toInt(); "1/${denom}s" }
        else { "${kotlin.math.round(exposureTime).toInt()}s" }
    } else "--"
    val isoText = if (iso > 0) "ISO $iso" else "--"
    val metaText = "${focalLength}mm  $shutterSpeed  $isoText"
    val metaWidth = textPaint.measureText(metaText)
    canvas.drawText(metaText, frameW - pad - metaWidth, baseline, textPaint)

    return frame
}
