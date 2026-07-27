# 📸 ZoomBox Camera

**ZoomBox Camera** is a professional, retro-themed camera application for Android built with Jetpack Compose and CameraX. It features a unique "Zoom Box" framing overlay that shows the exact crop area before capture, combined with vintage film-style color processing, 3D LUT color grading, and tactile manual controls.

---

[![Screenshot-20260718-192853.png](https://i.postimg.cc/JzLvjMzg/Screenshot-20260718-192853.png)](https://postimg.cc/qz11TfnX)  [![Screenshot-20260718-192912.png](https://i.postimg.cc/0ysF7PyV/Screenshot-20260718-192912.png)](https://postimg.cc/ZCwwSk3N)

---

## ✨ Key Features

### 🎯 Zoom Box Framing
A live 4:3 overlay on the viewfinder that previews the exact crop area of the final image. When using the PRIMARY lens with digital zoom (1×–3×), the zoom box visually scales to indicate the captured region, so you frame your shot *exactly* as it will be saved.

### 🔭 Multi-Lens System
- **Ultra-Wide** (~13mm) — expansive landscape and architectural shots.
- **Primary** (≈24mm native, 1×–3× digital zoom) — the main shooter with smooth zoom-box feedback.
- **Tele** (~116mm) — compressed perspective for portraits and distant subjects.

Digital zoom on the PRIMARY lens is backed by a center crop from the full-resolution sensor, preserving maximum image quality.

### 🎛️ Manual Controls
- **Exposure Compensation** (−3 to +3 EV, ⅒-stop granularity) — brighten or darken before capture.
- **Color Temperature & Tint** (2D color plot) — from cool teal to warm amber with a live viewfinder tint overlay.
- **Flash Modes** — Auto / On / Off.
- **Front/Back Camera Toggle** — with automatic mirror-flip for selfies.
- **Grid Lines** — rule-of-thirds overlay for composition.
- **Self-Timer** — 3s / 5s / 10s countdown.

### 🎞️ Retro Film Processing
- **8 film presets** with distinct color grades:
  - 🌅 **Kodak Portra** — warm, golden skin tones
  - 🌑 **Kodak BW** — classic black & white
  - 📸 **Polaroid** — cool, instant-film look
  - 🎞️ **Kodak Elite 100 X-Pro** — cross-processed, high contrast
  - 🌆 **Polaroid 669** — purple-tinted, expired-film aesthetic
  - 🌧️ **Moody** — dark, cinematic teal/orange
  - 🌿 **Muted Meadow** — soft, desaturated greens
  - ☀️ **Sunlit Spill** — warm, golden-hour glow
- **3D LUT color grading** — Adobe `.cube` LUT files parsed and applied via GPU shader (GLES) for real-time viewfinder preview, with a matching CPU path for post-capture JPEG processing.
- **Filmic S-curve** — smooth shoulder/toe roll-off replacing hard clipping.
- **Chromatic fringing** — simulated color channel misregistration for instant-film character.
- **Halation / bloom** — warm glow around bright highlights (Portra glow).
- **Split toning** — independent shadow and highlight color tints.
- **Vignette effect** — subtle radial darkening that pulls focus to the subject.
- All processing is applied losslessly to the final JPEG after capture, respecting the original EXIF data.

### 📷 RAW Capture (DNG)
- Full-resolution RAW bayer capture via Camera2 API, written as Adobe DNG.
- Per-lens RAW support detection — only enabled on sensors that advertise `RAW_SENSOR` capability.
- Parallel output alongside JPEG pipeline; RAW files are saved as `RETRO_RAW_*.dng`.
- Physical lens targeting for multi-camera devices (API 28+).

### 🖼️ In-App Gallery
- View captured photos in a film-style card layout with EXIF metadata (focal length, shutter speed, ISO).
- Scrollable filmstrip for quick navigation between shots.
- Share via system share sheet or delete unwanted captures.
- Photos are saved to device gallery (`Pictures/ZoomBoxCamera`) with rename pattern `*_<focal>mm.jpg`.

### 🧩 Clean Architecture
- **MVVM** with Kotlin StateFlows for reactive UI.
- **Modular camera domain** (`zoom/` package) for lens enumeration, FOV mapping, capture orchestration, and RAW pipeline.
- **GPU-accelerated color pipeline** (`color/` package) with GLSurfaceView + fragment shaders.
- 100% **Jetpack Compose** UI — modern, declarative, and maintainable.

---

## 🛠 Tech Stack

| Layer          | Technology                                |
|----------------|-------------------------------------------|
| UI             | Jetpack Compose + Material 3              |
| Camera         | CameraX (`Preview` + `ImageCapture`) + Camera2 (RAW) |
| Architecture   | MVVM (ViewModel + StateFlow)              |
| Async          | Kotlin Coroutines & Flow                  |
| Image Loading  | Coil (`rememberAsyncImagePainter`)        |
| Permissions    | Accompanist Permissions API               |
| Image Effects  | `ColorMatrix`, `PorterDuff`, `RadialGradient` (CPU); GLES fragment shaders (GPU) |
| 3D LUT         | Adobe `.cube` parser, trilinear interpolation, `GL_OES_texture_3D` |
| EXIF Handling  | Android `ExifInterface`                   |
| Persistence    | Jetpack DataStore Preferences             |
| Build          | Gradle with Kotlin DSL + Version Catalog  |

---

## 🚀 Getting Started

### Prerequisites
- **Android Studio** Ladybug (2024.2.1) or newer.
- **Android device/emulator** running API 24+ (Android 7.0).

### Setup

1. **Clone the repository:**
   ```bash
   git clone https://github.com/Indukto/Bhig.git
   cd Bhig
   ```

2. **Configure environment (optional):**
   The project supports a `.env` file at the project root for signing configs.
   *(See `.env.example` for the template.)*

3. **Open in Android Studio:**
   - **File → Open** and select the project directory.
   - Let Gradle sync complete.

4. **Build and run:**
   - Select your target device.
   - Click **Run** (▶).

---

## 🏗 Project Structure

```
app/
└── src/main/java/com/example/
    ├── MainActivity.kt                  # Single-activity entry point
    ├── CameraUi.kt                      # All Compose screens & components
    ├── CameraViewModel.kt               # Business logic, capture pipeline, EXIF
    ├── CameraPreviewView.kt             # CameraX lifecycle + preview bindings
    ├── SafeHapticFeedback.kt            # Haptic feedback wrapper (catches platform crashes)
    ├── UserPreferencesRepository.kt     # Jetpack DataStore persistence layer
    │
    ├── ui/theme/                        # Material 3 theme (Retro Slate)
    │
    ├── zoom/
    │   ├── LensProfile.kt               # Lens metadata model
    │   ├── LensCatalog.kt               # Runtime lens enumeration (Camera2)
    │   ├── FovMapper.kt                 # Field-of-view → box scale math
    │   ├── ZoomBoxCalculator.kt         # Pure-math zoom-box rect computation
    │   ├── AspectRatio.kt               # Photo aspect ratio enum (4:3, 3:2, 1:1)
    │   ├── PreviewSessionManager.kt     # CameraX preview-session lifecycle + lens switching
    │   ├── CaptureController.kt         # Image capture + file handling
    │   ├── RawCapture.kt                # RAW DNG capture via Camera2 API
    │   └── CaptureExtension.kt          # OEM extension modes (HDR, Night, Bokeh)
    │
    └── color/
        ├── CubeLutParser.kt             # Adobe .cube 3D LUT file parser
        ├── LutColorFilter.kt            # CPU-based trilinear LUT interpolation
        ├── LutPreviewRenderer.kt        # GLSurfaceView renderer (fragment shader LUT)
        └── LutPreviewView.kt            # GLSurfaceView wrapper for camera preview

app/src/test/java/com/example/zoom/
    └── FovMapperTest.kt                 # Unit tests for FOV mapping
```

---

## 🧠 How the Zoom Box Works

1. The viewfinder shows the **full sensor field of view**.
2. When `boxScale < 1.0`, a semi-transparent black mask is drawn over the viewfinder with a **rounded-rect cutout** — this is the zoom box.
3. The zoom box aspect ratio is configurable (4:3, 3:2, or 1:1).
4. On capture, `cropBitmapToZoomBox()` computes the pixel region matching the box and crops the full-resolution image accordingly.
5. The crop coordinates are derived from screen dimensions, the box fraction, and the sensor-to-screen scale, so the saved image pixel-for-pixel matches what was visible inside the box.

---

## 🎨 Color Pipeline

The app features a dual-path color pipeline:

### GPU Path (Viewfinder)
- `LutPreviewView` (a `GLSurfaceView`) hosts the camera preview.
- `LutPreviewRenderer` applies white balance, exposure, film effects, and 3D LUT color grading in a fragment shader.
- Falls back to a simplified shader on devices without `GL_OES_texture_3D`.

### CPU Path (Post-Capture)
- `LutColorFilter` applies the same 3D LUT via trilinear interpolation on the captured JPEG bitmap.
- An optimized `applyInPlace()` variant halves memory copies for faster processing.

### LUT Format
- Adobe `.cube` 3D LUT files are bundled in `assets/luts/`.
- Parsed by `CubeLutParser` which supports `LUT_3D_SIZE`, `DOMAIN_MIN`, `DOMAIN_MAX`, and `TITLE` headers.

---

## 🧪 Testing

Run the unit tests from the terminal:

```bash
./gradlew testDebugUnitTest
```

Or in Android Studio: right-click `app/src/test/` → **Run Tests**.

---

## 🎨 Design Philosophy

- **Tactile, minimal UI** — high-contrast slate theme with amber accents inspired by vintage rangefinder cameras.
- **No viewfinder chrome** — the camera preview fills the screen edge-to-edge (with rounded corners) for maximum immersion.
- **Slider controls** — custom `SpectrumSlider` with haptic feedback, gradient tracks, and double-tap-to-reset.
- **Morphing control surface** — compact bubble row expands into full color/exposure panels.
- **Film-card gallery** — each photo is presented inside a card resembling a print from a vintage film roll, complete with camera model and exposure data.

---

## 📜 License

Distributed under the MIT License. See `LICENSE` for more information.

---

> [!TIP]
> For the best results, shoot in well-lit environments to make the film-grain effect and color grading really pop. Try framing subjects with the zoom box to create intentional, precisely composed shots.