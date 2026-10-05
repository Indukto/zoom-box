# Reverse-engineering Dazz Cam profiles

`reverse_engineer.py` is a stdlib-only toolkit for measuring a Dazz Cam (or any
photo-filter) pipeline and turning its colour transform into an Adobe `.cube`
3D LUT that ZoomBox Camera loads from `app/src/main/assets/luts/`.

No numpy / PIL needed — just Python 3.8+.

## Tools

```bash
# 1. Identity Hald CLUT (the "input LUT" image)
python reverse_engineer.py hald --level 64 -o generated/identity_hald_64.png

# 2. Master test chart + EV variants for grain / sharpening / vignette / CA
python reverse_engineer.py chart --size 4096 --exposures=-2,-1,0,1,2 \
    -o generated/master_test_chart.png

# 3. Recover a .cube LUT from a processed Hald image
python reverse_engineer.py extract \
    generated/identity_hald_64.png path/to/dazz_export.png \
    -o generated/dazz_profile.cube --size 33
```

## How to capture a Dazz profile as a LUT

1. Generate `identity_hald_64.png` (level 64 → 4096×4096). For a smaller,
   compression-robust variant use `--level 16` (256×256).
2. Get the identity image onto the device and import it into Dazz.
3. Apply **one** film profile, with no other adjustments (no exposure, HSL,
   grain, borders, timestamps, crops, or resizing).
4. Export it back out **at original size and losslessly** (PNG). JPEG export
   will corrupt the high-frequency R/G ramps inside the 64×64 tiles — the
   recovered LUT will be noisy near tile edges. If only JPEG/HEIC is possible,
   use `--level 8` or `--level 16` (larger tiles survive compression better)
   and accept lower precision.
5. Run `extract` with the identity image and Dazz's output.

The identity image uses the standard Hald CLUT layout (same as ffmpeg's
`haldclut`), so you can also feed the pair through third-party LUT tools.

## Recovering a LUT from a *degraded* after-image

If Dazz won't export PNG and instead gives you a cropped, JPEG-compressed,
downscaled image (e.g. 635×423 from a 4096×4096 Hald — centre-cropped to 3:2,
~6.45× downscale, baseline JPEG), `recover_hald.py` can still recover the
per-pixel colour transform:

```bash
# needs ffmpeg on PATH to decode JPEGs
python recover_hald.py generated/identity_hald_64_after.jpg \
    -o generated/cpm35_recovered_33.cube \
    --chart generated/master_test_chart.png \
    --pairs "shot1.jpg:shot2.jpg:..."      # optional validation photos
```

What it does:

1. Fits the geometry (scale, crop offsets) from the Hald's own tile structure:
   the in-tile R/G ramps reset at every tile boundary, and those resets are
   found to sub-pixel precision in the column/row means. The horizontal fit is
   unambiguous; the vertical fit pins the crop down to one-tile-row multiples,
   and `--chart` + `--pairs` resolves the ambiguity by matching the chart's
   black/18%/50%/white panels (any exposure works — the panels are clip-safe).
2. Maps every output pixel back to its input (R, G) ramp values and tile B,
   averaging into a 17×17×17 lattice — the averaging kills JPEG noise and
   film grain. Pixels are no longer dropped at tile boundaries (the ramp is
   periodic, so the box-average stays valid).
3. Trilinearly samples the lattice into a `--size` (default 33) `.cube` in the
   same R-fastest layout the app loads.

What survives and what doesn't (honest limits):

- **Survives well:** the blue axis (tile index) is low-frequency and survives
  JPEG; the recovered blue curve is smooth and matches chart panels to within
  ~5 levels. Mid-tones match within ~10 levels.
- **Lost to the crop:** any input blue above the last visible tile row is
  clamped in the cube (the crop removed it). The blue extremes of the look are
  therefore extrapolated, not measured.
- **Lost to the 6.45× downscale:** the finest R/G ramp steps — the very dark
  and very bright faces of the cube are extrapolated from the neighbouring
  interior bins, so deep blacks and pure whites are approximate.
- **Not a LUT at all:** spatial, exposure-dependent effects (grain, vignette,
  halation/bloom, sharpening). The cpm35 test set shows this clearly: its
  black panel reads (17,28,29) in a −2 EV shot but (131,140,139) at +2 EV —
  a per-pixel LUT cannot produce that. Expect the LUT to nail colour and tone
  on mid-range scenes while bloom/grain/vignette behaviour won't transfer.

## What the master test chart contains

A 4×4 grid of 1024×1024 panels (at 4096):

| Row 1 | ColorChecker 24 | 21-step gray wedge | hue wheel | vertical gray gradient |
| Row 2 | R/G/B/gray ramps | primaries + pastels | Siemens star | saturation/value map |
| Row 3 | black | 18% gray | 50% gray | white |
| Row 4 | checkerboard | 1px lines | edge grid (64px) | skin-tone patches |

Run the same chart through every profile (and the `_ev-2 … _ev+2` variants)
to separate the tone curve, black lift, highlight rolloff, skin handling,
grain, sharpening, vignette and chromatic aberration from the colour LUT.

## Output format

`extract` writes a cubic `.cube` LUT (`LUT_3D_SIZE N`, `DOMAIN_MIN/MAX`, N³
data triples) with R varying fastest, then G, then B — exactly the layout
`CubeLutParser` expects. Drop the file into `app/src/main/assets/luts/` and
register it in `CameraViewModel.kt` to use it in-app.

Defaults: `--size 33` (recommended; the bundled LUTs use 13/25). `--size 64`
is fine for the GPU 3D-texture path but slower on the CPU path.
