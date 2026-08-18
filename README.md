<div align="center">

  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="Zoom Box Camera logo" width="128"/>

  <h1>Zoom Box Camera</h1>

  <p><em>Retro film looks, modern camera engineering.</em></p>

  <p>
    <img alt="Android API 29+" src="https://img.shields.io/badge/Android-API%2029%2B-3DDC84?style=flat-square&logo=android&logoColor=white"/>
    <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.2-7F52FF?style=flat-square&logo=kotlin&logoColor=white"/>
    <img alt="Jetpack Compose" src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white"/>
    <img alt="CameraX + Camera2" src="https://img.shields.io/badge/Camera-CameraX%20%2B%20Camera2-34A853?style=flat-square"/>
    <img alt="License: GPL-3.0" src="https://img.shields.io/badge/License-GPL--3.0-blue?style=flat-square"/>
  </p>

</div>

---

**Zoom Box Camera** is a retro-themed Android camera app built with **Jetpack Compose** and **CameraX**. A live *Zoom Box* framing overlay shows the exact crop area before you shoot, paired with vintage film color processing, 3D LUT grading, and full manual controls — the darkroom, back in your pocket.

> 💡 **Have a feature request?** Open an [issue](https://github.com/Indukto/Bhig/issues) 

##  Screenshots

<div align="center">

[![Screenshot-20260730-231458.png](https://i.postimg.cc/Kc38gxQY/Screenshot-20260730-231458.png)](https://postimg.cc/K4F26X6X) [![Screenshot-20260730-231920.png](https://i.postimg.cc/YSfMxCZz/Screenshot-20260730-231920.png)](https://postimg.cc/HJxqYdpr)

</div>

---

##  Features

### Frame 

- **Zoom Box** — A live 4:3 overlay on the viewfinder that previews the final crop. Digital zoom (1×–3×) scales the box, so you frame *exactly* what gets saved.
- **Multi-lens** — Ultra-wide, Primary (with digital zoom), and Tele. Digital zoom uses a center crop from the full sensor.
- **Manual controls** — Exposure compensation (−3 to +3 EV in ⅒ stops), a color temperature/tint plot, flash modes, grid lines, and a self-timer (3/5/10 s).

### Film 

- **14 film looks** — Warm Portrait, Monochrome 400, Instant Classic, Cross Process, Instant Vintage, Moody, Muted Meadow, Sunlit Spill, Golden 200, Street Mono 400, Vivid Cool 400, CCD Digicam, Pastel Instant, and Normal (pass-through). Each is defined by a 3D LUT plus per-look tonal parameters.
- **JSON look profiles** — Every look is fully described by an editable `assets/cameras/*.json` file; the capture pipeline prefers the JSON and falls back to the built-in enum. Adding or tuning a look is an *asset change, not a code change*.
- **Film processing pipeline** — One shared `RetroRenderParams` snapshot (LUT + film curve, contrast/saturation, split toning, bloom, fringing, soft focus, milky haze, grain, artifacts) drives all three render back-ends with a single signal chain:
  - **Live preview** — GLES fragment shader (grain + vignette shown in the viewfinder; dust/scratches/light-leak are capture-only).
  - **CPU capture** — Pixel filter applied to the saved JPEG.
  - **GPU capture** — Offscreen EGL processor reusing the exact same preview shader, on by default with automatic CPU fallback on any failure.
- **Film artifacts** — Highlight roll-off (filmic shoulder), black-point fade, parametric vignette, procedural film grain (value-noise, midtone-weighted, optional chroma for dye-cloud/CCD speckle), plus procedurally generated dust specks, vertical scratches, and warm corner light leaks — **no texture assets required**.

###  Capture & manage

- **RAW (DNG)** — Full-resolution RAW via the Camera2 API, captured in parallel to JPEG, with per-lens detection.
- **In-app gallery** — A film-card layout with EXIF metadata, plus share and delete.

---

##  Film Looks

Each look combines a `.cube` 3D LUT (in `app/src/main/assets/luts/`) with per-look defaults — grain strength/chroma, film S-curve, contrast, saturation, bloom, split toning, fringing, soft focus, milky haze, highlight roll-off, fade, vignette, dust, scratches, and light leak — all expressed in the look's JSON profile.

| Look | Vibe |
|---|---|
| 🌸 Warm Portrait | Soft, flattering skin tones |
| ⚫ Monochrome 400 | Classic black & white |
| 🧊 Instant Classic | Clean instant-film neutrality |
| 🎞️ Cross Process | Wild, shifted color chemistry |
| 📸 Instant Vintage | Aged instant-film warmth |
| 🌧️ Moody | Low-key, cinematic shadows |
| 🍃 Muted Meadow | Desaturated, earthy calm |
| ☀️ Sunlit Spill | Golden-hour glow |
| 🌇 Golden 200 | Warm 200-speed color negative |
| 🏙️ Street Mono 400 | Gritty monochrome for the city |
| 💠 Vivid Cool 400 | Punchy, cool-toned saturation |
| 📀 CCD Digicam | Early digital-sensor nostalgia |
| 🍬 Pastel Instant | Soft, dreamy pastels |
| ✨ Normal | Pure pass-through, no grade |

The five newest looks (Golden 200, Street Mono 400, Vivid Cool 400, CCD Digicam, Pastel Instant) use LUTs adapted from the MIT-licensed DAZZ Retro Camera reference and renamed to avoid third-party trademarks — see `app/src/main/assets/luts/NOTICE.txt` for provenance.

---

##  Tech Stack

| Layer | Tech |
|---|---|
| UI | Jetpack Compose + Material 3 |
| Camera | CameraX (Preview + ImageCapture) + Camera2 (RAW) |
| Arch | MVVM (ViewModel + StateFlow) |
| Async | Kotlin Coroutines & Flow |
| Effects | GLES fragment shaders, CPU pixel filter, EGL offscreen capture, Adobe `.cube` 3D LUT |
| Look profiles | JSON in `assets/cameras/` (model + loader + registry in `com.example.color`) |
| Persistence | Jetpack DataStore |
| Build | Gradle + Kotlin DSL (Kotlin 2.2, AGP 9) |

---

##  Getting Started

**Prerequisites:** [Android Studio Ladybug (2024.2.1)+](https://developer.android.com/studio), a device or emulator on **API 29+**.

```bash
git clone https://github.com/Indukto/Bhig.git
cd Bhig
```

Open the project in Android Studio, sync Gradle, and hit **Run**. That's it — no API keys, no configuration.

---

##  Testing

Compile the app:

```bash
./gradlew :app:compileDebugKotlin
```

Run the JVM tests for the color pipeline (preset → params mapping, JSON profile parsing, JSON ↔ enum parity, artifact fields), which live in `app/src/test/java/com/example/color/`:

```bash
./gradlew :app:testDebugUnitTest --tests "com.example.color.*"
```

The full suite compiles and passes with `./gradlew :app:testDebugUnitTest`.

---

##  License

This project is licensed under the [GNU General Public License v3.0](LICENSE).
