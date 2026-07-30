package com.example

/**
 * Single source-of-truth for the user-facing app version string.
 *
 * To bump the version, edit exactly ONE line of exactly ONE file:
 *
 *   app/build.gradle.kts → defaultConfig → versionName = "X.Y.Z"
 *
 * AGP reads that line at build time and auto-populates
 * `BuildConfig.VERSION_NAME`, which this object exposes as [name] and
 * formats as [display]. Anywhere in the app that needs the version
 * should read from `AppVersion.display` (or `AppVersion.name` for the
 * raw number), so the splash footer, debug logs, settings "About"
 * screen, future analytics, and any other version-aware UI all stay
 * in sync from this one knob.
 *
 * Why this file exists: prior to this refactor the codebase had two
 * competing version strings — `versionName = "1.9.0"` in build.gradle.kts
 * and `"Zoom Cam · v0.3"` hardcoded in CameraUi.kt's splash footer —
 * a classic drift bug. This wrapper makes that class of drift
 * impossible: bump the gradle line, the splash + everything else react.
 *
 * Note: the `BuildConfig` import resolves to `com.example.BuildConfig`,
 * which AGP generates under the configured `android.namespace`. The
 * `app/build.gradle.kts` already enables `buildFeatures.buildConfig = true`
 * so this file is wired at build time without any extra gradle work.
 */
object AppVersion {
    /** Raw version identifier — e.g. `"1.9.0"`. */
    val name: String get() = BuildConfig.VERSION_NAME

    /** Pretty label — e.g. `"v1.9.0"`. */
    val display: String get() = "v${BuildConfig.VERSION_NAME}"
}
