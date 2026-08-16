# ZoomBox Camera

Retro-themed Android camera app built with Jetpack Compose and CameraX. Uses a "Zoom Box" framing overlay that shows the exact crop area before capture, paired with vintage film color processing, 3D LUT grading, and manual controls.

### If you have feature requests put them into issues 

[![Screenshot-20260730-231458.png](https://i.postimg.cc/Kc38gxQY/Screenshot-20260730-231458.png)](https://postimg.cc/K4F26X6X) [![Screenshot-20260730-231920.png](https://i.postimg.cc/YSfMxCZz/Screenshot-20260730-231920.png)](https://postimg.cc/HJxqYdpr) 



## Features

- **Zoom Box** — Live 4:3 overlay on the viewfinder that previews the final crop. Digital zoom (1×–3×) scales the box so you frame exactly what gets saved.
- **Multi-lens** — Ultra-wide, Primary (with digital zoom), Tele. Digital zoom uses a center crop from the full sensor.
- **Manual controls** — Exposure comp (−3 to +3 EV, ⅒-stop), color temperature/tint plot, flash modes, grid lines, self-timer (3/5/10s).
- **8 film presets** — Kodak Portra, Kodak BW, Polaroid, Kodak Elite 100 X-Pro, Polaroid 669, Moody, Muted Meadow, Sunlit Spill. Applied via 3D LUT (GPU for viewfinder, CPU for JPEG).
- **Film effects** — S-curve, chromatic fringing, halation/bloom, split toning, vignette.
- **RAW (DNG)** — Full-resolution RAW via Camera2 API, parallel to JPEG output. Per-lens detection.
- **In-app gallery** — Film-card layout with EXIF metadata, share, delete.

## Tech Stack

| Layer | Tech |
|---|---|
| UI | Jetpack Compose + Material 3 |
| Camera | CameraX (Preview + ImageCapture) + Camera2 (RAW) |
| Arch | MVVM (ViewModel + StateFlow) |
| Async | Kotlin Coroutines & Flow |
| Effects | ColorMatrix, GLES fragment shaders, Adobe .cube 3D LUT |
| Persistence | Jetpack DataStore |
| Build | Gradle + Kotlin DSL |

## Getting Started

**Prerequisites:** Android Studio Ladybug (2024.2.1)+, device/emulator on API 24+.

```
git clone https://github.com/Indukto/Bhig.git
cd Bhig
```

Open in Android Studio, sync Gradle, and run.

## Testing

```
./gradlew testDebugUnitTest
```


