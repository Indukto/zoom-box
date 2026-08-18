<div align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="Krate Logo" width="128"/>
  <h1>Zoom Box Camera</h1>
 
</div>




Retro-themed Android camera app built with Jetpack Compose and CameraX. Uses a "Zoom Box" framing overlay that shows the exact crop area before capture, paired with vintage film color processing, 3D LUT grading, and manual controls.

### If you have feature requests put them into issues 

[![Screenshot-20260730-231458.png](https://i.postimg.cc/Kc38gxQY/Screenshot-20260730-231458.png)](https://postimg.cc/K4F26X6X) [![Screenshot-20260730-231920.png](https://i.postimg.cc/YSfMxCZz/Screenshot-20260730-231920.png)](https://postimg.cc/HJxqYdpr) 



## Features

- **Zoom Box** — Live 4:3 overlay on the viewfinder that previews the final crop. Digital zoom (1×–3×) scales the box so you frame exactly what gets saved.
- **Multi-lens** — Ultra-wide, Primary (with digital zoom), Tele. Digital zoom uses a center crop from the full sensor.
- **Manual controls** — Exposure comp (−3 to +3 EV, ⅒-stop), color temperature/tint plot, flash modes, grid lines, self-timer (3/5/10s).
- **14 film looks** — Warm Portrait, Monochrome 400, Instant Classic, Cross Process, Instant Vintage, Moody, Muted Meadow, Sunlit Spill, Golden 200, Street Mono 400, Vivid Cool 400, CCD Digicam, Pastel Instant, and Normal (pass-through). Each is defined by a 3D LUT plus per-look tonal parameters.
- **JSON look profiles** — Every look is fully described by an editable `assets/cameras/*.json` file (the capture pipeline prefers the JSON, falling back to the built-in enum). Adding or tuning a look is an asset change, not a code change.
- **Film processing pipeline** — A shared `RetroRenderParams` snapshot (LUT + film curve, contrast/saturation, split toning, bloom, fringing, soft focus, milky haze, grain, and artifacts) drives all three render back-ends with one signal chain:
  - **Live preview** — GLES fragment shader (grain + vignette shown in the viewfinder; dust/scratches/light-leak are capture-only).
  - **CPU capture** — pixel filter applied to the saved JPEG.
  - **GPU capture** — offscreen EGL processor that reuses the exact same preview shader, enabled by default with automatic CPU fallback on any failure.
- **Film artifacts** — Highlight roll-off (filmic shoulder), black-point fade, parametric vignette, procedural film grain (value-noise, midtone-weighted, optional chroma for dye-cloud/CCD speckle), and procedurally generated dust specks, vertical scratches, and warm corner light leaks — no texture assets required.
- **RAW (DNG)** — Full-resolution RAW via Camera2 API, parallel to JPEG output. Per-lens detection.
- **In-app gallery** — Film-card layout with EXIF metadata, share, delete.

## Tech Stack

| Layer | Tech |
|---|---|
| UI | Jetpack Compose + Material 3 |
| Camera | CameraX (Preview + ImageCapture) + Camera2 (RAW) |
| Arch | MVVM (ViewModel + StateFlow) |
| Async | Kotlin Coroutines & Flow |
| Effects | GLES fragment shaders, CPU pixel filter, EGL offscreen capture, Adobe .cube 3D LUT |
| Look profiles | JSON in `assets/cameras/` (model + loader + registry in `com.example.color`) |
| Persistence | Jetpack DataStore |
| Build | Gradle + Kotlin DSL |

## Film Looks

Each look combines a `.cube` 3D LUT (in `app/src/main/assets/luts/`) with per-look defaults — grain strength/chroma, film S-curve, contrast, saturation, bloom, split toning, fringing, soft focus, milky haze, highlight roll-off, fade, vignette, dust, scratches, and light leak — all expressed in the look's JSON profile.

The five newest looks (Golden 200, Street Mono 400, Vivid Cool 400, CCD Digicam, Pastel Instant) use LUTs adapted from the MIT-licensed DAZZ Retro Camera reference and renamed to avoid third-party trademarks; see `app/src/main/assets/luts/NOTICE.txt` for provenance.

## Getting Started

**Prerequisites:** Android Studio Ladybug (2024.2.1)+, device/emulator on API 24+.

```
git clone https://github.com/Indukto/Bhig.git
cd Bhig
```

Open in Android Studio, sync Gradle, and run.

## Testing

```
./gradlew :app:compileDebugKotlin
```

JVM tests for the color pipeline (preset → params mapping, JSON profile parsing, JSON↔enum parity, artifact fields) live in `app/src/test/java/com/example/color/`:

```
./gradlew :app:testDebugUnitTest --tests "com.example.color.*"
```

Note: the full `testDebugUnitTest` task currently fails to compile on a pre-existing, unrelated test (`TutorialCompletedTest.kt` references removed DataStore methods); the `com.example.color.*` tests are unaffected and pass.
