# AGENTS.md

Guidance for AI coding agents. Human-facing docs: [README.md](README.md).

**Zoom Box Camera** is a retro film camera app for Android (API 29+): a live *Zoom Box*
crop overlay on the viewfinder, 13 JSON-defined film looks graded through `.cube` 3D LUTs,
CameraX preview/capture plus Camera2 RAW (DNG), and a deliberately offline,
permission-minimal design. Single Gradle module `:app`; UI is Compose + Material 3
(Material 3 Expressive), architecture is MVVM over `StateFlow`. Kotlin 2.2.10, AGP 9.4.1,
Gradle 9.8, `compileSdk` 36.1, JDK 17+.

All Kotlin lives under `app/src/main/java/eu/indukto/zoombox/` — abbreviated `Z/` below;
the JVM tests mirror that root at `app/src/test/java/eu/indukto/zoombox/`.

## Verify your change

Pick the smallest tier that covers your edit and stop there.

| Tier | When | Run |
|---|---|---|
| 0 — skip | docs (`*.md`), comments only, licence/notice files | nothing |
| 1 — fast | one Kotlin file, one `strings.xml`, one asset JSON/`.cube` | `./gradlew :app:compileDebugKotlin` (~45 s warm), plus one focused suite, e.g. `./gradlew :app:testDebugUnitTest --tests "eu.indukto.zoombox.color.*"` |
| 2 — full | `color/`, `zoom/`, `CameraViewModel.kt`, store/gallery, multi-file, grading math | `./gradlew :app:testDebugUnitTest` (~1 min warm) |
| UI | Compose layout change (`CameraUi.kt`, panels, Settings/Gallery) | Tier 2 + `./gradlew :app:verifyRoborazziDebug`; run `:app:recordRoborazziDebug` only for an intentional golden change and state why |

There is **no CI** — the tier above is the only gate, so compiling is not enough for a
color-pipeline or UI touch. `app/src/test/roborazzi/*.png` are committed goldens
(`AppScreenshotsTest`, `PanelScreenshotTest`, `SettingsScreenshotTest`; Robolectric
`@GraphicsMode(NATIVE)`, `sdk = [34]`, `w411dp-h891dp`). Never hand-edit one.

`lintDebug` **fails the build** and is not a gate: `main` carries 30 pre-existing errors
(24 `UnsafeOptInUsageError`, 3 `RestrictedApi`, 2 `NonObservableLocale`, 1
`LocalContextGetResourceValueCall`) plus ~65 warnings, and there is deliberately no
baseline file. Treat a finding as pre-existing when its ID is on that list; run lint only
for manifest/permission/lint-config touches and never "fix" what was already there. Never
run `RETRO_BENCH=1 ...RetroFilterBenchmark` unless you touched `RetroFilter` perf. On a
config-cache failure, retry once with `--no-configuration-cache`.

## Repo map

| Path | Owns |
|---|---|
| `Z/CameraUi.kt` | App root and every Compose screen: `CameraUi`, `CameraPermissionOnboarding` (first-run setup), `CameraActiveScreen`, the floating panels, `FilmLooksPickerSheet`, `PhotoViewerOverlay`, and the host for the look editor deck. ~3.4k lines — search into it, don't rewrite it. |
| `Z/CameraPreviewView.kt` | The camera-screen host composable: binds `LutPreviewView` + `PreviewSessionManager`, owns the look crossfade, plus the private Camera2 HAL path that RAW capture rides on. |
| `Z/CameraViewModel.kt` | The single ViewModel; all camera, look and capture state as `StateFlow`. |
| `Z/MainActivity.kt` | Activity, edge-to-edge, and the volume-key shutter interception (`VolumeKeyInterceptor`). |
| `Z/LookEditorPanel.kt` | The look editor deck: sliders over the live viewfinder, the LUT row that imports a `.cube`, delete/restore confirmation, and `LookEditorPanelHost`. `FilmLooksPickerSheet` is the only entry point — its "New Look" tile starts a blank draft, the gear on the selected tile edits that look, and there is no manager page and nothing off Settings. The draft lives on the ViewModel, so opening the editor is a state change, not a route. |
| `Z/PhotoStore.kt`, `Z/GalleryScreen.kt`, `Z/GalleryFrame.kt` | On-device photo store (private working copies + the MediaStore mirror) and the gallery pager/filmstrip. |
| `Z/SettingsScreen.kt`, `Z/UserPreferencesRepository.kt` | Settings UI and DataStore-backed preferences. |
| `Z/LegalScreen.kt`, `Z/TutorialOverlay.kt`, `Z/AppVersion.kt`, `Z/FilmPreset.kt`, `Z/SafeHapticFeedback.kt`, `Z/BugReport.kt`, `Z/LiquidGlass.kt` | Legal pages, gesture coach-marks, version wrapper, built-in look fallback, haptics wrapper, copy-to-clipboard bug report, AGSL glass rim. |
| `Z/ui/theme/Theme.kt`, `Z/ui/theme/Type.kt` | Material 3 Expressive theming: the film-chrome dark palette and the type scale. |
| `Z/zoom/` | Lens/FOV domain: `LensCatalog`, `LensProfile`, `FovMapper`, `ZoomBoxCalculator`, `AspectRatio`, `CropRectCalculator`, `FocalLabelAnchor`, `PreviewSessionManager` (CameraX binding), `RawCapture` (DNG). |
| `Z/color/` | Look system: `CameraProfile`/`CameraProfileLoader`/`CameraProfileRegistry`/`CameraProfileWriter` (JSON model ↔ catalog), `LookEntry`, `LookSwatch`, `LookDraft`/`LookDraftValidator`, `UserLookStore`, `UserLutStore`, `RetroRenderParams` (shared grading snapshot), `LutPreviewRenderer` (GLES shader), `LutPreviewView` (the `TextureView` that hosts it in place of CameraX's `PreviewView`), `RetroFilter` (CPU filter), `GpuCaptureProcessor` (offscreen EGL), `DoubleExposure`, `CubeLutParser`. |
| `app/src/main/assets/cameras/*.json` | One profile per bundled look — the source of truth for grading. |
| `app/src/main/assets/luts/*.cube` | Bundled 3D LUTs. User-imported ones live in `filesDir/luts/` — never in assets. |
| `app/src/main/res/values*/strings.xml` | English default in `values/` plus 15 translated locales. |
| `app/src/test/java/eu/indukto/zoombox/` | Robolectric/Roborazzi JVM tests, `color/` and `zoom/` sub-packages. |

## Invariants — do not break these

1. **Offline is the product.** No `INTERNET` permission, no analytics, ads, trackers or
   crash reporting, and no new permission without an explicit request. The manifest, store
   listing and data-safety answers all assert this.
2. **Bundled looks are assets; user looks are documents.** The thirteen shipped looks are
   `assets/cameras/` JSON plus `.cube` files and are never written to — add or tune one as
   an asset change. Three sub-rules hold that line:
   - A look the *user* authors lives in `filesDir/looks/<id>.json` in the same schema.
     Editing a bundled look writes an override under the same id that shadows the shipped
     grade in place (no copies); deleting it restores the factory grade.
     `CameraProfileRegistry` is the one place that merges the two.
   - A look's `lutPath` is either a bundled asset path or the `user:<file>.cube` form
     `UserLutStore` owns, and `CameraViewModel.loadLutByPath` is the single resolver for
     both. Preview and capture read that one path or they drift.
   - The writer and the loader are one contract: `CameraProfileWriter` must emit exactly
     the keys `CameraProfileLoader` reads. Renaming a key on one side only is silent today
     — there is no round-trip test — so change both in the same commit.
3. **One `RetroRenderParams` snapshot drives all three render back-ends** (live GL shader,
   CPU capture, GPU capture). Change the shared param model and all three follow; never
   fork the math into a single back-end.
4. **The version has one source:** `versionName` in `app/build.gradle.kts` →
   `BuildConfig.VERSION_NAME` → `AppVersion`. Don't hardcode a version anywhere.
5. **Locales stay in sync with `values/`.** All 15 translated locales carry the full
   key set, legal/GDPR copy included, so a user reading the privacy disclosure sees it
   in their own language. `MissingTranslation` is a configured warning in
   `app/build.gradle.kts` that flags any drift — keep it at zero. When you add a string
   to `values/strings.xml`, translate it into every `values-*` file in the same commit;
   the order of the keys should mirror the base file so diffs stay readable.

## Conventions

- Kotlin is 4-space indented with no trailing commas, `kotlin.code.style=official`; Gradle
  `.kts` files and JSON are 2-space; XML resources are 4-space. `.editorconfig` encodes
  this — keep it in sync if the style ever changes.
- Line endings are LF everywhere: `.gitattributes` forces it to keep published sources
  byte-identical. Never rewrite a file with CRLF.
- Comment style is a real convention here. KDoc and inline comments explain **why** a
  choice was made (platform quirks, pointer-pipeline caveats, EGL lifecycle, OEM camera
  behaviour), not what the next line does. Match that tone; skip comments that only
  restate code.
- Composables are colocated with their only caller and kept `private`/`internal` unless a
  test needs them (the few public ones exist for Robolectric); interactive elements carry
  `.testTag("...")` so tests can drive them, and user-facing text comes from
  `stringResource`.
- New user-facing strings go to `app/src/main/res/values/strings.xml` (the default
  locale). Translate into the other `values-*` directories only when you're confident in
  the wording.
- Anything that needs a platform permission beyond the camera is a product decision, not a
  refactor.

## Gotchas

- **Toolchain:** AGP 9.4.1 needs **JDK 17+**, and `local.properties` must point `sdk.dir`
  at an Android SDK that has the compileSdk platform and build-tools installed. If
  `./gradlew` fails with `JAVA_HOME is set to an invalid directory`, set `JAVA_HOME` for
  the command to a JDK 17+ install (`JAVA_HOME="/path/to/jdk" ./gradlew ...`).
- **Dependency ceilings:** `compileSdk` is 36.1, so newer Compose BOM / material3 / haze
  releases are deliberately avoided. `gradle/libs.versions.toml` records the reason next
  to each pinned version — read those comments before bumping anything, and expect
  AAR-metadata failures.
- **The package was renamed** from `com.example` to `eu.indukto.zoombox`, and the fallout
  is still in the tree: `.gitignore` ends with a dozen `/app/src/test/java/com/example/…`
  rules that match nothing (one of them, `CameraProfileWriterTest.kt`, was how several
  look-editor tests stayed out of the repo). Assume any `com/example` path you read is
  stale; grep before trusting one.
- **No LUT provenance file.** The bundled `.cube` files have no `NOTICE.txt` in the tree
  (the app ships GPL-3.0). If you add a LUT from outside the project, record where it came
  from and under what licence.
- Configuration cache, build cache and parallel builds are enabled; if a task misbehaves,
  retry with `--no-configuration-cache`.
- Release signing reads `KEYSTORE_PATH` / `STORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`
  from the environment. Without them `assembleRelease` stays unsigned for local testing
  only — Play Store uploads need a signed AAB. Never check in a keystore or credentials.
- Never commit `local.properties`, `*.jks`, `docs/`, `tools/`, `.freebuff/`, `.artifacts/`,
  `.zcode/`, `.build_tmp/`, `app/debug/` or release APKs (all gitignored). `docs/` and
  `tools/` are maintainer-local: don't reference them from committed files.
