#!/usr/bin/env python3
"""
Recover a 3D LUT from a *degraded* Hald CLUT "after" image.

When Dazz Cam exports a processed photo it often:
  - centre-crops the square 4096x4096 Hald to 3:2,
  - downscales ~6.45x (4096 -> ~635 px),
  - and writes baseline JPEG (4:2:0 chroma subsampling).

That destroys the fine in-tile R/G ramps but the *blue* axis (tile index) is
low-frequency and survives, as does the overall tone/colour mapping. This tool
exploits the known Hald layout to rebuild a usable cubic LUT:

  1. Fit the geometry: scale s = W/side and the horizontal/vertical crop
     offsets, using the ~10 px tile periodicity (boundary edge energy).
  2. For every output pixel, compute the input coordinates (x/s, y0 + y/s),
     then the exact box-averaged input (R, G) inside the tile and the tile's
     B value.  Pixels whose footprint crosses a tile boundary are dropped.
  3. Average the output colours into a NxNxN lattice (N=17 by default).
     Averaging over hundreds of samples kills JPEG noise and film grain.
  4. Trilinearly sample the lattice and write an Adobe .cube file in the
     exact layout CubeLutParser expects (LUT_3D_SIZE, R-fastest).

Usage:
  python tools/recover_hald.py <after.jpg> -o out.cube [--grid 17] [--size 33]
  python tools/recover_hald.py <after.jpg> --validate master_test_chart.png \
      --pairs "cpm35_a.jpg:cpm35_b.jpg:..."  (optional)

The --validate mode applies the recovered LUT to the source test chart(s),
crops+downscales to the photo size and reports RMSE against the real
after-photos, so you can judge how much of the look was captured.
"""

import argparse
import math
import os
import struct
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from reverse_engineer import read_png_rgb  # noqa: E402


# --------------------------------------------------------------------------
# JPEG decoding (delegates to ffmpeg; falls back to a PNG next to the JPG)
# --------------------------------------------------------------------------

def read_image(path):
    """Return (w, h, rgb) for a JPEG or PNG."""
    with open(path, "rb") as f:
        head = f.read(8)
    if head.startswith(b"\x89PNG"):
        return read_png_rgb(path)
    if not head.startswith(b"\xff\xd8"):
        raise SystemExit(f"{path}: not a PNG or JPEG")

    try:
        proc = subprocess.run(
            ["ffmpeg", "-y", "-loglevel", "error", "-i", path,
             "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
            capture_output=True, check=True, timeout=300)
    except FileNotFoundError:
        raise SystemExit("ffmpeg not found - needed to decode JPEG (see tools/README.md)")
    except subprocess.CalledProcessError as e:
        raise SystemExit(f"ffmpeg failed on {path}: {e.stderr.decode(errors='replace')}")

    data = proc.stdout
    # width/height from ffprobe
    probe = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0",
         "-show_entries", "stream=width,height", "-of", "csv=p=0:s=x", path],
        capture_output=True, check=True)
    w, h = (int(v) for v in probe.stdout.decode().strip().split("x"))
    if len(data) != w * h * 3:
        raise SystemExit(f"{path}: unexpected decoded size {len(data)} != {w}x{h}x3")
    return w, h, data


# --------------------------------------------------------------------------
# Geometry fitting
# --------------------------------------------------------------------------

def _detect_resets(vals, length, period):
    """Sub-pixel positions of the periodic ramp resets in a 1-D signal.

    The Hald layout puts an in-tile ramp (R horizontally, G vertically) that
    resets sharply at every tile boundary.  After downscaling + JPEG the reset
    is a steep negative gradient; we locate each one to sub-pixel precision
    with a parabolic fit on the gradient.
    """
    sm = list(vals)
    if length > 30:                     # light smoothing for JPEG noise
        sm = [sm[0]] + [(sm[i - 1] + 2 * sm[i] + sm[i + 1]) / 4.0
                        for i in range(1, length - 1)] + [sm[-1]]
    grad = [sm[i] - sm[i - 1] for i in range(1, length)]
    gmin = min(grad)
    if gmin > -4.0:
        return []
    thresh = -max(4.0, 0.25 * abs(gmin))
    out = []
    for i in range(1, len(grad) - 1):
        if grad[i] < thresh and grad[i] < grad[i - 1] and grad[i] < grad[i + 1]:
            a, b, c = grad[i - 1], grad[i], grad[i + 1]
            denom = a - 2 * b + c
            sub = 0.5 * (a - c) / denom if denom != 0 else 0.0
            out.append(i + 1 + sub)
    return out


def _median(xs):
    if not xs:
        return None
    s = sorted(xs)
    return s[len(s) // 2]


def fit_offsets(rgb, w, h, side):
    """Find the tile-grid alignment using the ramp-reset positions.

    Returns (xoff, ycands, s, y0_center) where
      - xoff is the horizontal offset in OUTPUT pixels: input x =
        (out_x + 0.5 - xoff) / s   (the full 4096 width is assumed visible,
        so xoff is unambiguous)
      - ycands is a list of candidate vertical offsets in INPUT pixels:
        input y = yoff + (out_y + 0.5) / s.  The ramp resets pin yoff down to
        multiples of one tile row (64 input px) apart; the exact multiple is
        chosen from the candidates (smallest = crop near the top of the
        square, the common behaviour, or via the validation panels).
      - s is the input->output scale, y0_center the centred-crop yoff.
    """
    s = w / float(side)
    P = 64.0 * s
    y0_center = (side - h / s) / 2.0
    max_yoff = side - h / s
    luma = bytearray(w * h)
    for y in range(h):
        base = y * w * 3
        for x in range(w):
            o = base + x * 3
            luma[y * w + x] = (299 * rgb[o] + 587 * rgb[o + 1] + 114 * rgb[o + 2]) // 1000
    colmean = [sum(luma[y * w + x] for y in range(h)) / h for x in range(w)]
    rowmean = [sum(luma[y * w + x] for x in range(w)) / w for y in range(h)]

    rx = _detect_resets(colmean, w, P)
    ry = _detect_resets(rowmean, h, P)
    print(f"  ramp resets: {len(rx)} horizontal, {len(ry)} vertical")

    xoff = 0.5                          # fallback: grid starts at pixel 0
    if len(rx) >= 8:
        offs = [p + 0.5 - max(1, round(p / P)) * P for p in rx]
        med = _median(offs)
        offs = [p + 0.5 - max(1, round((p + 0.5 - med) / P)) * P for p in rx]
        xoff = _median(offs)

    ycands = []
    if len(ry) >= 8:
        base = (ry[0] + 0.5) / s        # = 64 * m_first - yoff
        for m_first in range(1, 64):
            y0 = 64.0 * m_first - base
            if -8.0 <= y0 <= max_yoff + 8.0:
                vals = [64.0 * (m_first + k) - (p + 0.5) / s for k, p in enumerate(ry)]
                ycands.append(_median(vals))
        ycands = sorted(ycands)
    return xoff, ycands, s, y0_center


# --------------------------------------------------------------------------
# Uniform-panel matching (chooses the right yoff among the candidates)
# --------------------------------------------------------------------------

# (input x0, x1, y0, y1, source colour) of the four uniform panels of the
# master test chart's solid row (row 2 of the 4x4 grid).
CHART_SOLIDS = [
    (0, 1024, 2048, 3072, (0, 0, 0)),
    (1024, 2048, 2048, 3072, (46, 46, 46)),
    (2048, 3072, 2048, 3072, (128, 128, 128)),
    (3072, 4096, 2048, 3072, (255, 255, 255)),
]


def region_mean(img, w, h, s, xoff, yoff, x0i, x1i, y0i, y1i):
    """Mean RGB of a decoded image over an input-space rect (mapped to output)."""
    acc = [0.0] * 3
    cnt = 0
    y0o = max(0, (y0i - yoff) * s)
    y1o = min(h, (y1i - yoff) * s)
    x0o = max(0, (x0i + xoff) * s - 0.5)
    x1o = min(w, (x1i + xoff) * s - 0.5)
    for yy in range(math.floor(y0o), math.ceil(y1o)):
        for xx in range(math.floor(x0o), math.ceil(x1o)):
            o = (yy * w + xx) * 3
            acc[0] += img[o]
            acc[1] += img[o + 1]
            acc[2] += img[o + 2]
            cnt += 1
    if not cnt:
        return None
    return [a / cnt for a in acc]


def panel_score(n, bmin, bmax, lat, w, h, s, xoff, yoff, photos):
    """Squared error of the recovered LUT against the chart's uniform panels.

    Uses the chart's black / 18% / 50% / white solids: the LUT applied to each
    known source colour must reproduce the real photo's panel means.  Works for
    any exposure (the panels are clipped-safe sources), so every photo votes.
    """
    total = 0.0
    votes = 0
    for ph in photos:
        pw, phh, prgb = read_image(ph)
        for x0, x1, y0, y1, src in CHART_SOLIDS:
            rm = region_mean(prgb, pw, phh, s, xoff, yoff, x0, x1, y0, y1)
            if rm is None:
                continue
            lr = sample_lattice(n, bmin, bmax, lat, src[0] / 255.0, src[1] / 255.0,
                                src[2] / 255.0, n)
            for i in range(3):
                total += (rm[i] - lr[i] * 255.0) ** 2
            votes += 1
    return total / max(votes, 1)


# --------------------------------------------------------------------------
# Lattice extraction
# --------------------------------------------------------------------------

def build_lattice(rgb, w, h, side, grid, xoff, yoff, s=None):
    """Return (n, bmin, bmax, lat) where lat is a flat n^3 float array (RGB)."""
    s = s or (w / float(side))
    span = 1.0 / s                      # input px covered by one output pixel
    n = grid
    sums = [0.0] * (n * n * n * 3)
    cnt = [0] * (n * n * n)
    rvals = [round(i * 255.0 / 63.0) for i in range(64)]
    gvals = [round(j * 255.0 / 63.0) for j in range(64)]

    def ramp_avg(c, vals):
        lo = c - span / 2.0
        hi = c + span / 2.0
        total = 0.0
        weight = 0.0
        u = math.floor(lo)
        while u < hi:
            a = max(lo, float(u))
            b = min(hi, float(u + 1))
            total += vals[u % 64] * (b - a)
            weight += b - a
            u += 1
        return total / weight

    bmin = 1.0
    bmax = 0.0
    for y in range(h):
        yi = yoff + (y + 0.5) / s
        g_in = ramp_avg(yi, gvals)
        ty = math.floor(yi / 64.0)
        for x in range(w):
            xi = (x + 0.5 - xoff) / s
            tx = math.floor(xi / 64.0)
            b = (tx + 64 * ty) / (side - 1.0)
            if b < bmin:
                bmin = b
            if b > bmax:
                bmax = b
            r_in = ramp_avg(xi, rvals)
            ri = int(round(r_in / 255.0 * (n - 1)))
            gi = int(round(g_in / 255.0 * (n - 1)))
            bi = int(round(b * (n - 1)))
            o = (bi * n + gi) * n + ri
            c3 = o * 3
            p = (y * w + x) * 3
            sums[c3] += rgb[p]
            sums[c3 + 1] += rgb[p + 1]
            sums[c3 + 2] += rgb[p + 2]
            cnt[o] += 1

    lat = [0.0] * (n * n * n * 3)
    total_px = 0
    for i in range(n * n * n):
        c = cnt[i]
        if c:
            total_px += c
            o = i * 3
            lat[o] = sums[o] / c
            lat[o + 1] = sums[o + 1] / c
            lat[o + 2] = sums[o + 2] / c
    filled = sum(1 for c in cnt if c)
    print(f"  lattice {n}x{n}x{n}: {filled}/{n**3} bins filled "
          f"({total_px} pixels, ~{total_px // max(filled,1)} px/bin)")

    # Nearest-neighbour fill for interior holes (dilate a few passes).
    passes = 0
    while filled < n ** 3 and passes < 8:
        new_cells = []
        for bi in range(n):
            for gi in range(n):
                for ri in range(n):
                    if cnt[(bi * n + gi) * n + ri]:
                        continue
                    acc = [0.0, 0.0, 0.0]
                    k = 0
                    for db in (-1, 1):
                        for dg in (-1, 1):
                            for dr in (-1, 1):
                                bb, gg, rr = bi + db, gi + dg, ri + dr
                                if 0 <= bb < n and 0 <= gg < n and 0 <= rr < n and \
                                        cnt[(bb * n + gg) * n + rr]:
                                    o = ((bb * n + gg) * n + rr) * 3
                                    acc[0] += lat[o]
                                    acc[1] += lat[o + 1]
                                    acc[2] += lat[o + 2]
                                    k += 1
                    if k:
                        o = ((bi * n + gi) * n + ri) * 3
                        lat[o] = acc[0] / k
                        lat[o + 1] = acc[1] / k
                        lat[o + 2] = acc[2] / k
                        cnt[(bi * n + gi) * n + ri] = 1
                        new_cells.append((bi, gi, ri))
        filled += len(new_cells)
        passes += 1
    print(f"  after fill: {filled}/{n**3} bins (b in [{bmin:.3f},{bmax:.3f}])")
    return n, bmin, bmax, lat


def sample_lattice(n, bmin, bmax, lat, r, g, b, size):
    """Trilinear sample of the lattice at normalised (r, g, b), clamped to the
    visible b-range (extrapolation = clamp), returning 0..1 RGB for .cube."""
    b = max(bmin, min(bmax, b))
    ri = r * (n - 1)
    gi = g * (n - 1)
    bi = b * (n - 1)
    r0, g0, b0 = int(ri), int(gi), int(bi)
    dr, dg, db = ri - r0, gi - g0, bi - b0
    r1, g1, b1 = min(r0 + 1, n - 1), min(g0 + 1, n - 1), min(b0 + 1, n - 1)

    def px(rr, gg, bb):
        o = ((bb * n + gg) * n + rr) * 3
        return lat[o], lat[o + 1], lat[o + 2]

    c = px(r0, g0, b0), px(r1, g0, b0), px(r0, g1, b0), px(r1, g1, b0), \
        px(r0, g0, b1), px(r1, g0, b1), px(r0, g1, b1), px(r1, g1, b1)
    out = []
    for ch in range(3):
        lo = (c[0][ch] * (1 - dr) + c[1][ch] * dr) * (1 - dg) + \
             (c[2][ch] * (1 - dr) + c[3][ch] * dr) * dg
        hi = (c[4][ch] * (1 - dr) + c[5][ch] * dr) * (1 - dg) + \
             (c[6][ch] * (1 - dr) + c[7][ch] * dr) * dg
        out.append((lo * (1 - db) + hi * db) / 255.0)
    return out


def write_cube(path, n, bmin, bmax, lat, size):
    lines = [
        "# Recovered from a JPEG-cropped Hald after-image by tools/recover_hald.py",
        f'TITLE "recovered_{size}"',
        "",
        f"LUT_3D_SIZE {size}",
        "",
        "DOMAIN_MIN 0.0 0.0 0.0",
        "DOMAIN_MAX 1.0 1.0 1.0",
        "",
    ]
    s1 = size - 1
    for bb in range(size):
        for gg in range(size):
            for rr in range(size):
                cr, cg, cb = sample_lattice(n, bmin, bmax, lat,
                                            rr / s1, gg / s1, bb / s1, size)
                lines.append("%.6f %.6f %.6f" % (cr, cg, cb))
    with open(path, "w") as f:
        f.write("\n".join(lines) + "\n")
    print(f"Wrote {size}x{size}x{size} .cube -> {path}")


# --------------------------------------------------------------------------
# Diagnostics
# --------------------------------------------------------------------------

def diagnostics(n, bmin, bmax, lat, size=21):
    print("\n  gray diagonal tone curve (R=G=B):")
    for i in range(size):
        v = i / (size - 1.0)
        cr, cg, cb = sample_lattice(n, bmin, bmax, lat, v, v, v, size)
        print("    in %4.2f -> out R %5.2f G %5.2f B %5.2f" % (v, cr * 255, cg * 255, cb * 255))
    print("\n  blue axis at R=G=0.5 (per-tile average):")
    for i in range(size):
        v = i / (size - 1.0)
        cr, cg, cb = sample_lattice(n, bmin, bmax, lat, 0.5, 0.5, v, size)
        print("    B %4.2f -> out R %5.2f G %5.2f B %5.2f" % (v, cr * 255, cg * 255, cb * 255))


# --------------------------------------------------------------------------
# Validation: apply LUT to a chart, compare with a real after-photo
# --------------------------------------------------------------------------

def apply_lut_to_chart(src_path, lat_n, bmin, bmax, lat, w, h, side, xoff, yoff):
    """Box-downscale src to (w,h) with the fitted geometry and apply the LUT."""
    sw, sh, srgb = read_png_rgb(src_path)
    s = w / float(side)
    out = bytearray(w * h * 3)
    rvals = [round(i * 255.0 / 63.0) for i in range(64)]
    gvals = [round(j * 255.0 / 63.0) for j in range(64)]
    # simple box filter: output pixel = average of overlapping input pixels
    for y in range(h):
        yi0 = yoff + y / s
        yi1 = yoff + (y + 1) / s
        for x in range(w):
            xi0 = (x - xoff) / s
            xi1 = (x + 1.0 - xoff) / s
            acc = [0.0, 0.0, 0.0]
            k = 0
            for yy in range(math.floor(yi0), math.ceil(yi1)):
                wy = min(yi1, yy + 1) - max(yi0, yy)
                if wy <= 0 or yy < 0 or yy >= sh:
                    continue
                for xx in range(math.floor(xi0), math.ceil(xi1)):
                    wx = min(xi1, xx + 1) - max(xi0, xx)
                    if wx <= 0 or xx < 0 or xx >= sw:
                        continue
                    o = (yy * sw + xx) * 3
                    acc[0] += srgb[o] * wx * wy
                    acc[1] += srgb[o + 1] * wx * wy
                    acc[2] += srgb[o + 2] * wx * wy
                    k += wx * wy
            if k:
                r = acc[0] / k / 255.0
                g = acc[1] / k / 255.0
                b = acc[2] / k / 255.0
                cr, cg, cb = sample_lattice(lat_n, bmin, bmax, lat, r, g, b, lat_n)
                o = (y * w + x) * 3
                out[o] = min(255, max(0, round(cr * 255)))
                out[o + 1] = min(255, max(0, round(cg * 255)))
                out[o + 2] = min(255, max(0, round(cb * 255)))
    return out


def rmse(a, b):
    n = len(a)
    d = 0.0
    for i in range(0, n, 3):
        d += (a[i] - b[i]) ** 2 + (a[i + 1] - b[i + 1]) ** 2 + (a[i + 2] - b[i + 2]) ** 2
    return math.sqrt(d / (n // 3))


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------

def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("after", help="processed Hald after-image (JPEG or PNG)")
    p.add_argument("-o", "--output", default="recovered.cube")
    p.add_argument("--side", type=int, default=4096, help="identity Hald side (4096)")
    p.add_argument("--grid", type=int, default=17, help="lattice size (17)")
    p.add_argument("--size", type=int, default=33, help="output .cube size (33)")
    p.add_argument("--chart", help="master test chart PNG to validate against")
    p.add_argument("--pairs", default="",
                   help="colon-separated after-photos to validate (must be >=1, "
                        "matched to --chart exposures in ascending brightness)")
    args = p.parse_args(argv)

    w, h, rgb = read_image(args.after)
    print(f"after image: {w}x{h}")

    xoff, ycands, s, y0_center = fit_offsets(rgb, w, h, args.side)
    if not ycands:
        ycands = [y0_center]
    print(f"geometry: scale {s:.6f} (tile {64*s:.3f} px), "
          f"xoff {xoff:+.2f} output px, yoff candidates " +
          ", ".join(f"{y:+.1f}" for y in ycands) +
          f" input px (centre-crop would be {y0_center:.2f})")

    photos = [f for f in args.pairs.split(":") if f]
    yoff = ycands[0]
    if args.chart and photos:
        # choose the yoff candidate whose LUT best reproduces the uniform panels
        best = None
        for cand in ycands:
            n0, bmin0, bmax0, lat0 = build_lattice(rgb, w, h, args.side,
                                                   args.grid, xoff, cand, s)
            sc = panel_score(n0, bmin0, bmax0, lat0, w, h, s, xoff, cand, photos)
            print(f"  yoff {cand:+.1f}: panel RMSE {math.sqrt(sc):.1f}")
            if best is None or sc < best[0]:
                best = (sc, cand)
        yoff = best[1]
        print(f"  -> chosen yoff {yoff:+.1f} input px")

    n, bmin, bmax, lat = build_lattice(rgb, w, h, args.side, args.grid, xoff, yoff, s)
    write_cube(args.output, n, bmin, bmax, lat, args.size)
    diagnostics(n, bmin, bmax, lat)

    if args.chart:
        if not photos:
            raise SystemExit("--validate needs --pairs (colon-separated after-photos)")
        import glob as _glob

        def mean_luma(path):
            pw, phh, prgb = read_image(path)
            n = len(prgb) // 3
            step = 1 if n < 2_000_000 else 8   # big charts: sample, don't scan
            m = [0.0, 0.0, 0.0]
            cnt = 0
            for i in range(0, n, step):
                m[0] += prgb[i * 3]
                m[1] += prgb[i * 3 + 1]
                m[2] += prgb[i * 3 + 2]
                cnt += 1
            return (0.299 * m[0] + 0.587 * m[1] + 0.114 * m[2]) / cnt

        photo_means = sorted(((ph, mean_luma(ph)) for ph in photos), key=lambda t: t[1])
        base, ext = os.path.splitext(args.chart)
        evs = sorted(_glob.glob(base + "_ev*.png"))
        charts = sorted(set([args.chart] + evs))
        # sort chart variants by their own mean luma (darkest = lowest EV first)
        chart_means = sorted(((ch, mean_luma(ch)) for ch in charts), key=lambda t: t[1])
        print("\nvalidation (LUT applied to chart, box-cropped to photo size):")
        for i, (ch, _cm) in enumerate(chart_means):
            # pair by brightness rank: darkest chart with darkest photo, etc.
            target = photo_means[i % len(photo_means)][0]
            sim = apply_lut_to_chart(ch, n, bmin, bmax, lat, w, h, args.side, xoff, yoff)
            tw, th, tref = read_image(target)
            if (tw, th) != (w, h):
                print(f"  {os.path.basename(ch)}: photo size mismatch {tw}x{th}")
                continue
            e = rmse(sim, tref)
            print(f"  {os.path.basename(ch)} -> {os.path.basename(target)}: "
                  f"RMSE {e:.1f} 8-bit levels")


if __name__ == "__main__":
    main()
