## Implementation Plan: Live LUT Preview via OpenGL ES

### Architecture

```
CameraX → SurfaceTexture (input) → GL_TEXTURE_EXTERNAL_OES →
  Fragment shader [WB → Exposure → 3D LUT] → GLSurfaceView → screen
```

CameraX's `Preview.SurfaceProvider` API gives us a hook to insert a custom surface: we implement `onSurfaceRequested()`, create a `SurfaceTexture` on the GL thread, hand its `Surface` to CameraX, then sample the texture through our LUT shader.

### Tech choices
- **GLES 2.0 context + ESSL1** with two universal extensions:
  - `GL_OES_EGL_image_external` — samples the camera SurfaceTexture (universal on Android)
  - `GL_OES_texture_3D` — samples the LUT as `sampler3D` with free trilinear interpolation (mandatory in GLES3, present on ~all modern devices)
- minSdk=24 → GLES3-class hardware is universal, no fallback needed.

---

### Step 1: Create `LutPreviewRenderer.kt` — the GL renderer
**File:** `app/src/main/java/com/example/color/LutPreviewRenderer.kt` (~250 lines)

Implements `GLSurfaceView.Renderer`. Owns:
- GL program (vertex + fragment shaders compiled once)
- Input texture (`GL_TEXTURE_EXTERNAL_OES`) + backing `SurfaceTexture`
- LUT texture (`GL_TEXTURE_3D`, uploaded from `CubeLut.data`)
- Uniforms: `uTemperature`, `uTint`, `uExposure`, `uLutEnabled`, `uFlipH`
- Aspect-ratio math for FILL_CENTER crop

Key responsibilities:
- `onSurfaceCreated()`: compile shaders, link program, generate input texture, create `SurfaceTexture`, signal "ready" so the SurfaceProvider can hand it to CameraX.
- `onSurfaceChanged(w, h)`: recompute FILL_CENTER crop rect for the new view size.
- `onDrawFrame()`: `surfaceTexture.updateTexImage()`, set uniforms, draw fullscreen triangle pair.
- `onFrameAvailable()` (fired from CameraX thread): calls `glSurfaceView.requestRender()`.
- Public setters: `setLut(cubeLut: CubeLut?)`, `setWhiteBalance(temp, tint, exposure)`, `setFlipH(boolean)` — all marshal onto GL thread via `queueEvent`.

**Fragment shader (ESSL1):**
```glsl
#extension GL_OES_EGL_image_external : require
#extension GL_OES_texture_3D : enable
precision mediump float;
uniform samplerExternalOES uTexture;
uniform sampler3D uLut;
uniform float uTemperature, uTint, uExposure;
uniform bool uLutEnabled;
varying vec2 vTexCoord;

void main() {
    vec3 c = texture2D(uTexture, vTexCoord).rgb;
    // WB (warm/cool via R/B; magenta/green via G)
    c.r += uTemperature * 0.04;
    c.b -= uTemperature * 0.04;
    c.g += uTint * 0.04;
    c.r -= uTint * 0.02;
    c.b -= uTint * 0.02;
    c = clamp(c, 0.0, 1.0);
    // Exposure
    c *= pow(2.0, uExposure * 0.4);
    c = clamp(c, 0.0, 1.0);
    // LUT
    vec3 out = uLutEnabled ? texture3D(uLut, c).rgb : c;
    gl_FragColor = vec4(out, 1.0);
}
```

### Step 2: Create `LutPreviewView.kt` — GLSurfaceView + SurfaceProvider
**File:** `app/src/main/java/com/example/color/LutPreviewView.kt` (~100 lines)

- Subclasses `GLSurfaceView`. Sets `EGL_CONTEXT_CLIENT_VERSION = 2`, `RENDERMODE_WHEN_DIRTY`.
- Owns a `Preview.SurfaceProvider` that calls into the renderer:
  - `onSurfaceRequested(req)`: queue GL-thread work → create SurfaceTexture with `req.resolution`, provide `Surface` to CameraX, attach release listener for teardown.
- Exposes public pass-through methods: `setLut(...)`, `setWhiteBalance(...)`, `setFlipH(...)`, `getSurfaceProvider()`.

### Step 3: Wire `LutPreviewView` into `CameraPreviewView.kt`
**File:** `app/src/main/java/com/example/CameraPreviewView.kt`

Replace lines 217–222 (the `PreviewView` creation):
```kotlin
// BEFORE
val previewView = remember { PreviewView(context).apply { ... } }

// AFTER
val lutPreviewView = remember { LutPreviewView(context) }
```
- Change `surfaceProvider` references (lines 301, 321, 335) from `previewView.surfaceProvider` to `lutPreviewView.surfaceProvider`.
- The `AndroidView` factory (line 390) now wraps `lutPreviewView`.
- All camera binding logic in `PreviewSessionManager` stays unchanged — it just receives our SurfaceProvider instead of PreviewView's.

### Step 4: Plumb WB/exposure/LUT state into the preview
**File:** `app/src/main/java/com/example/CameraPreviewView.kt`

Add new parameters to the Composable signature: `temperature: Float`, `tint: Float`, `exposure: Float`, `activePreset: FilmPreset`, `context` for LUT loading. Add `LaunchedEffect` blocks that call `lutPreviewView.setWhiteBalance(...)`, `lutPreviewView.setLut(...)` whenever these change. The LUT is loaded once per preset using the existing `CubeLutParser` (cached in ViewModel).

### Step 5: Delete the Compose tint overlays
**File:** `app/src/main/java/com/example/CameraUi.kt` (lines 1333–1361)

Remove the two translucent Compose `Box` overlays (the temperature amber/blue wash and the magenta/green wash). The GL shader now handles WB properly. Pass `temperature`, `tint`, `exposure`, `activePreset` into the `CameraPreviewView(...)` call site (lines 1314–1331).

---

### Files changed (summary)
| File | Action |
|------|--------|
| `app/.../color/LutPreviewRenderer.kt` | **New** — GL renderer + shaders (~250 lines) |
| `app/.../color/LutPreviewView.kt` | **New** — GLSurfaceView + SurfaceProvider (~100 lines) |
| `app/.../CameraPreviewView.kt` | **Edit** — swap PreviewView → LutPreviewView, add WB/LUT plumbing |
| `app/.../CameraUi.kt` | **Edit** — delete tint overlay Boxes, plumb new params |

### Risks & mitigations
1. **CameraX rebind churn** (lens switch, extension toggle, RAW handoff) tears down the surface and re-requests. → Renderer's SurfaceProvider handles `provideSurface`'s release future cleanly by releasing the SurfaceTexture.
2. **Thread safety** — `SurfaceTexture` callbacks fire on CameraX threads. → All GL work marshalled via `GLSurfaceView.queueEvent{}`.
3. **Front camera mirror** — handled via `uFlipH` uniform flipping texture coords.
4. **FILL_CENTER aspect** — computed in `onSurfaceChanged` using surface buffer size vs view size.
5. **Extension availability** — both extensions are universal on minSdk 24. If `texture3D` fails at runtime (extremely rare), the shader sets `uLutEnabled = false` and falls back to WB-only preview.

No build.gradle changes needed — GLES is a platform API.