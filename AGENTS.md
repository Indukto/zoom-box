# AGENTS.md

Guidance for AI coding agents working in this repository. Human-facing docs: [README.md](README.md).

**Zoom Box Camera** is a retro film camera app for Android (API 29+): a live *Zoom Box* crop
overlay on the viewfinder, 13 JSON-defined film looks graded through `.cube` 3D LUTs, CameraX
preview/capture plus Camera2 RAW (DNG), and a deliberately offline, permission-minimal design.
Single Gradle module: `:app`. UI is Jetpack Compose + Material 3, architecture is MVVM with
`StateFlow`.

## Verify your change

Pick the smallest tier that covers your edit and stop there.

| Tier | When | Run |
|---|---|---|
| 0 — skip | docs (`*.md`), comments only, `NOTICE.txt` | nothing |
| 1 — fast | one Kotlin file, one `strings.xml`, one asset JSON/`.cube` | `./gradlew :app:compileDebugKotlin` (~45 s warm), plus one focused suite, e.g. `./gradlew :app:testDebugUnitTest --tests "eu.indukto.zoombox.color.*"` |
| 2 — full | `color/`, `zoom/`, `CameraViewModel.kt`, store/gallery, multi-file, grading math | `./gradlew :app:testDebugUnitTest` (~1 min, 137 tests) |
| UI | Compose layout change (`CameraUi.kt`, panels, Settings/Gallery) | Tier 2 + `./gradlew :app:verifyRoborazziDebug`; run `:app:recordRoborazziDebug` only for an intentional golden change and state why |

There is **no CI** — the tier above is the only gate. Compiling is not enough for
color-pipeline or UI touches.

`lintDebug` is advisory, not green on `main` (30 pre-existing errors: 24
`UnsafeOptInUsageError`, 3 `RestrictedApi`, 2 `NonObservableLocale`, 1
`LocalContextGetResourceValueCall`, plus ~76 warnings, no baseline). Run it only
for manifest/permission/lint-config touches, and ignore pre-existing findings.
Never run `RETRO_BENCH=1 ...RetroFilterBenchmark` unless you touched `RetroFilter`
perf. On a config-cache failure, retry once with `--no-configuration-cache`.

`app/src/test/roborazzi/*.png` are committed goldens (`AppScreenshotsTest`,
`PanelScreenshotTest`, `SettingsScreenshotTest`; Robolectric `@GraphicsMode(NATIVE)`,
`sdk = [34]`, `w411dp-h891dp`). Never hand-edit one.

## Repo map

| Path | Owns |
|---|---|
| `app/src/main/java/com/example/CameraUi.kt` | App root and every Compose screen: `CameraUi`, `CameraPermissionOnboarding` (first-run setup), `CameraActiveScreen`, the floating panels, `PhotoViewerOverlay`, and the host for the look editor deck. ~3.3k lines — search into it, don't rewrite it. |
| `app/src/main/java/com/example/CameraViewModel.kt` | The single ViewModel; all camera, look and capture state as `StateFlow`. |
| `app/src/main/java/com/example/CameraPreviewView.kt` | Custom preview surface (replaces CameraX `PreviewView` where GL params are pushed per frame). |
| `app/src/main/java/com/example/zoom/` | Lens/FOV domain: `LensCatalog`, `LensProfile`, `FovMapper`, `ZoomBoxCalculator`, `PreviewSessionManager` (binding), `RawCapture` (DNG). |
| `app/src/main/java/com/example/color/` | Look system: `CameraProfile`/`CameraProfileLoader`/`CameraProfileRegistry` (JSON model → catalog), `RetroRenderParams` (shared grading snapshot), `LutPreviewRenderer` (GLES shader), `RetroFilter` (CPU filter), `GpuCaptureProcessor` (offscreen EGL), `DoubleExposure`, `CubeLutParser`. |
| `app/src/main/java/com/example/color/UserLookStore.kt`, `UserLutStore.kt`, `CameraProfileWriter.kt`, `LookDraft*.kt` | The user-authored look layer: `filesDir/looks/<id>.json` (save/delete/mint ids), the writer that mirrors the loader's schema, the in-progress `LookDraft`, and the validator that clamps what a slider may store. `UserLutStore` owns imported `.cube` files under `filesDir/luts/` and the `user:<file>.cube` path scheme a look records instead of an absolute path. |
| `app/src/main/java/com/example/LookEditorPanel.kt` | The look editor deck: sliders over the live viewfinder, the LUT row that imports a `.cube`, and the delete confirmation. The film-style picker on the camera screen (`FilmLooksPickerSheet`) is the only entry point — its "New Look" tile at the end of the grid starts a blank draft, the gear on the selected tile edits that look, and there is no separate manager page. Nothing hangs off Settings. The draft lives on the ViewModel, so opening the editor is a state change, not a route. |
| `app/src/main/java/com/example/PhotoStore.kt`, `GalleryScreen.kt`, `GalleryFrame.kt` | On-device photo store, gallery pager/filmstrip. |
| `app/src/main/java/com/example/SettingsScreen.kt`, `UserPreferencesRepository.kt` | Settings UI and DataStore-backed preferences. |
| `app/src/main/java/com/example/LegalScreen.kt`, `TutorialOverlay.kt`, `AppVersion.kt`, `FilmPreset.kt`, `SafeHapticFeedback.kt` | Legal pages, gesture coach-marks, version footer, built-in look fallback, haptics wrapper. |
| `app/src/main/assets/cameras/*.json` | One profile per look — the source of truth for grading. |
| `app/src/main/assets/luts/*.cube` | Bundled 3D LUTs (provenance/licensing: `NOTICE.txt` next to them). User-imported ones live in `filesDir/luts/` — never in assets. |
| `app/src/main/res/values*/strings.xml` | 15 translated locales plus the English default in `values/`. |
| `app/src/test/java/com/example/` | Robolectric/Roborazzi JVM tests, `color/` and `zoom/` per package. |

## Invariants — do not break these

1. **Offline is the product.** No `INTERNET` permission, no analytics, ads, trackers or crash
   reporting, and no new permission without an explicit request. The manifest, store listing and
   data-safety answers all assert this.
2. **Bundled looks are assets; user looks are documents.** The thirteen shipped looks are
   `assets/cameras/` JSON plus `.cube` files and are never written to — add or tune one as an
   asset change. Looks the *user* authors live in `filesDir/looks/<id>.json` in the same schema
   and are edited in-app; editing a bundled look saves an override file under the same id that
   shadows the shipped grade in place (no copies), and deleting it restores the factory grade.
   `CameraProfileRegistry` merges both and nothing else may fork the
   grading math. A look's `lutPath` is either a bundled asset path or the
   `user:<file>.cube` form that `UserLutStore` owns, and
   `CameraViewModel.loadLutByPath` is the single resolver for both — preview and
   capture read that one path or they drift. The writer and the loader are one contract — `CameraProfileWriter` emits exactly
   the keys `CameraProfileLoader` reads, and `CameraProfileWriterTest` round-trips every bundled
   profile, so renaming a key on one side only fails the suite. `FilmPreset` is only the fallback
   for assets that fail to load — keep the JSON ↔ enum parity that the tests lock down.
3. **One `RetroRenderParams` snapshot drives all three render back-ends** (live GL shader, CPU
   capture, GPU capture). Change the shared param model and all three follow; never fork the math
   into a single back-end.
4. **The version has one source:** `versionName` in `app/build.gradle.kts` →
   `BuildConfig.VERSION_NAME` → `AppVersion`. Don't hardcode a version anywhere.
5. **Partial localizations are deliberate.** Legal text exists in English and German only and the
   rest falls back to English; `MissingTranslation` is a configured warning in `app/build.gradle.kts`.
   Don't "fix" it by machine-translating legal copy.

## Conventions

- Kotlin is 4-space indented with no trailing commas, `kotlin.code.style=official`; Gradle `.kts`
  files and JSON are 2-space; XML resources are 4-space. `.editorconfig` encodes this — keep it
  in sync if the style ever changes.
- Line endings are LF everywhere: `.gitattributes` forces it to keep published
  sources byte-identical. Never rewrite a file with CRLF.
- Comment style is a real convention here. KDoc and inline comments explain **why** a choice was
  made (platform quirks, pointer-pipeline caveats, EGL lifecycle, OEM camera behaviour), not what
  the next line does. Match that tone; skip comments that only restate code.
- Composables are colocated with their only caller and kept `private`/`internal` unless a test
  needs them (the few public ones exist for Robolectric); interactive elements carry
  `.testTag("...")` so tests can drive them, and user-facing text comes from `stringResource`.
- The camera permission is the app's only platform permission. Anything that needs another one is
  a product decision, not a refactor.
- New user-facing strings go to `app/src/main/res/values/strings.xml` (the default locale).
  Translate into the other `values-*` directories only when you're confident in the wording.

## Gotchas

- **Toolchain:** Gradle 9.8 + AGP 9 need **JDK 17+**, and `local.properties` must point `sdk.dir`
  at an Android SDK that has the compileSdk platform and build-tools installed. If `./gradlew`
  fails with `JAVA_HOME is set to an invalid directory`, set `JAVA_HOME` for the command to a
  JDK 17+ install (`JAVA_HOME="/path/to/jdk" ./gradlew ...`).
- **Dependency ceilings:** `compileSdk` is 36.1, so newer Compose BOM / material3 / haze releases
  are deliberately avoided. `gradle/libs.versions.toml` records the reason next to each pinned
  version — read those comments before bumping anything, and expect AAR-metadata failures.
- Configuration cache, build cache and parallel builds are enabled; if a task misbehaves, retry
  with `--no-configuration-cache`.
- Release signing reads `KEYSTORE_PATH` / `STORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` from the
  environment. Without them `assembleRelease` stays unsigned for local testing only —
  Play Store uploads need a signed AAB. Never check in a keystore or credentials.
- Never commit `local.properties`, `*.jks`, `docs/`, `tools/`, `.freebuff/`, `.artifacts/`,
  `.zcode/`, `.build_tmp/`, `app/debug/` or release APKs (all gitignored). `docs/` and `tools/`
  are maintainer-local: don't reference them from committed files.
