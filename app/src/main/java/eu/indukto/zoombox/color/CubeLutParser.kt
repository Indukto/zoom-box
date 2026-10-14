package eu.indukto.zoombox.color

import android.content.Context
import androidx.compose.runtime.Stable
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader

/**
 * A parsed Adobe `.cube` 3D LUT.
 *
 * Data layout: a flat [FloatArray] of length `size * size * size * 3` where each
 * consecutive triple is an (R, G, B) output sample in [0, 1]. The index for an
 * input coordinate with grid integers (r, g, b) in `0 until size` is:
 *
 *   index(r, g, b) = ((b * size) + g) * size + r) * 3
 *
 * This matches the G'MIC / Adobe convention (R varies fastest, then G, then B).
 */
@Stable
data class CubeLut(
    val size: Int,
    val domainMin: FloatArray,
    val domainMax: FloatArray,
    val data: FloatArray
) {
    /** Returns the linear array offset of the (r, g, b) lattice sample. */
    fun indexOf(r: Int, g: Int, b: Int): Int = ((b * size) + g) * size + r
}

/**
 * Parses an Adobe `.cube` 3D LUT from the app's `assets/` directory, or from a
 * file the app owns (a LUT the user imported — see [UserLutStore]).
 *
 * Supported header keywords: `LUT_3D_SIZE`, `DOMAIN_MIN`, `DOMAIN_MAX`, `TITLE`.
 * Lines starting with `#` and blank lines are ignored. Each data line must be
 * three whitespace-separated floats in [0, 1].
 */
object CubeLutParser {

    /**
     * Parses the LUT at the given relative path under `assets/`.
     *
     * @param assetPath e.g. `"luts/warm_portrait.cube"`
     * @param context   any application/activity context
     */
    fun parse(assetPath: String, context: Context): CubeLut =
        context.assets.open(assetPath).use { parse(it, assetPath) }

    /**
     * Parses a `.cube` document from [file]. Same rules as the asset form —
     * the import path runs a user-picked file through this before a look is
     * allowed to record it, so a LUT that could never grade is rejected while
     * it is still a throwaway copy.
     */
    fun parse(file: File): CubeLut =
        file.inputStream().use { parse(it, file.name) }

    /** The one implementation; [source] only ever appears in error messages. */
    private fun parse(stream: InputStream, source: String): CubeLut {
        var size = 0
        var domainMin = floatArrayOf(0f, 0f, 0f)
        var domainMax = floatArrayOf(1f, 1f, 1f)
        val samples = ArrayList<Float>(8 * 8 * 8 * 3)

        BufferedReader(InputStreamReader(stream)).forEachLine { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachLine

            // Header keyword lines begin with a non-numeric token.
            val firstChar = line[0]
            if (!firstChar.isDigit() && firstChar != '.' && firstChar != '-' && firstChar != '+') {
                val upper = line.uppercase()
                when {
                    upper.startsWith("LUT_3D_SIZE") -> {
                        size = line.substringAfter(' ').trim().toIntOrNull() ?: 0
                    }
                    upper.startsWith("DOMAIN_MIN") -> {
                        val parts = line.substringAfter(' ').trim().split(Regex("\\s+"))
                        if (parts.size >= 3) {
                            domainMin = floatArrayOf(parts[0].toFloat(), parts[1].toFloat(), parts[2].toFloat())
                        }
                    }
                    upper.startsWith("DOMAIN_MAX") -> {
                        val parts = line.substringAfter(' ').trim().split(Regex("\\s+"))
                        if (parts.size >= 3) {
                            domainMax = floatArrayOf(parts[0].toFloat(), parts[1].toFloat(), parts[2].toFloat())
                        }
                    }
                    // TITLE and any other keywords are ignored.
                }
                return@forEachLine
            }

            // Data line: three floats.
            val parts = line.split(Regex("\\s+"))
            if (parts.size >= 3) {
                samples.add(parts[0].toFloat())
                samples.add(parts[1].toFloat())
                samples.add(parts[2].toFloat())
            }
        }

        require(size > 0) { "Invalid .cube file '$source': missing LUT_3D_SIZE" }
        val expected = size * size * size * 3
        require(samples.size == expected) {
            "Invalid .cube file '$source': expected $expected floats, found ${samples.size}"
        }

        return CubeLut(
            size = size,
            domainMin = domainMin,
            domainMax = domainMax,
            data = FloatArray(samples.size) { samples[it] }
        )
    }
}

