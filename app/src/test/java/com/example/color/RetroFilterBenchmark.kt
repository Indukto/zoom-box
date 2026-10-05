package com.example.color

import android.graphics.Bitmap
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Reproducible timing + behavior harness for [applyRetroFilter] on a fixed
 * 4000x3000 (~12 MP) synthetic bitmap. Left in the repo so perf claims can be
 * re-checked on any machine:
 *
 * ```
 * RETRO_BENCH=1 ./gradlew :app:testDebugUnitTest --tests "com.example.color.RetroFilterBenchmark"
 * ```
 *
 * Skipped (not run) unless `RETRO_BENCH=1` is in the environment or the
 * `retro.bench` system property is `true`, so the normal suite stays fast.
 *
 * For each parameter set it prints one `BENCH ...` line with the median and
 * min wall time of 5 timed runs after 1 warm-up, plus a checksum of the output
 * pixels. The checksum is the behavior lock: a "behavior-preserving" refactor
 * must leave it untouched, while an intentional look change (e.g. a new grain
 * hash) is expected to move it — for the parameter sets that never call the
 * hash, it must never move.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RetroFilterBenchmark {

    @Test
    fun `benchmark applyRetroFilter on fixed 12MP synthetic bitmap`() {
        assumeTrue(
            "set RETRO_BENCH=1 (or -Dretro.bench=true) to run the benchmark",
            System.getenv("RETRO_BENCH") == "1" || System.getProperty("retro.bench") == "true"
        )

        val w = 4000
        val h = 3000
        // Fixed deterministic content: horizontal x vertical gradient plus a
        // cheap high-frequency component, so no stage sees a degenerate input.
        val src = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            (0xFF shl 24) or
                ((x * 255 / (w - 1)) shl 16) or
                ((y * 255 / (h - 1)) shl 8) or
                (((x xor y) * 3) and 0xFF)
        }

        // Grain-heavy: mirrors FilmPreset.STREET_MONO_400's look (film curve,
        // hard contrast, strong grain, vignette) — the config where the noise
        // hash dominates the per-pixel cost.
        val grainHeavy = RetroRenderParams(
            filmCurve = 0.40f,
            contrast = 1.45f,
            saturation = 0f,
            grainStrength = 0.35f,
            grainChroma = 0f,
            vignette = 1.10f
        )
        // Grain-free: tonal stages only (mirrors a warm-portrait-like grade
        // without grain) — isolates the per-pixel loop cost from the hash.
        val grainFree = RetroRenderParams(
            filmCurve = 0.20f,
            contrast = 1.05f,
            saturation = 1.10f,
            bloom = 0.18f,
            shadowTintB = 0.025f,
            shadowTintStrength = 0.06f,
            highlightTintR = 0.04f,
            highlightTintStrength = 0.08f,
            vignette = 1f
        )

        bench("grain_heavy", w, h, src, grainHeavy)
        bench("grain_free", w, h, src, grainFree)
    }

    private fun bench(
        name: String,
        w: Int,
        h: Int,
        src: IntArray,
        params: RetroRenderParams
    ) {
        val runs = 5
        val times = LongArray(runs)
        var checksum = 0L
        // 1 warm-up run (JIT, thread-local buffer allocation, chunk dispatch)
        // followed by `runs` timed runs.
        repeat(runs + 1) { iteration ->
            // Fresh bitmap from identical source every run: applyRetroFilter
            // mutates a mutable receiver in place, and each run must grade the
            // same input for its checksum to be meaningful.
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(src, 0, w, 0, 0, w, h)
            val t0 = System.nanoTime()
            val out = runBlocking { bitmap.applyRetroFilter(params) }
            val elapsed = (System.nanoTime() - t0) / 1_000_000
            val pixels = IntArray(w * h)
            out.getPixels(pixels, 0, w, 0, 0, w, h)
            var sum = 0L
            for (c in pixels) sum = sum * 31 + (c.toLong() and 0xFFFFFFFFL)
            checksum = sum
            bitmap.recycle()
            if (iteration > 0) times[iteration - 1] = elapsed
        }
        times.sort()
        val median = times[runs / 2]
        val min = times[0]
        println("BENCH config=$name size=${w}x$h runs=$runs median_ms=$median min_ms=$min checksum=0x${checksum.toULong().toString(16)}")
    }
}
