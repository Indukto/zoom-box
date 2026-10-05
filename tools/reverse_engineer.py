#!/usr/bin/env python3
"""
Reverse-engineering toolkit for Dazz Cam-style image pipelines.

Three tools, one file, Python stdlib only (no numpy / PIL):

  python tools/reverse_engineer.py hald    --level 64 -o identity_hald.png
  python tools/reverse_engineer.py chart   --size 4096 --exposures -2,-1,0,1,2 -o chart.png
  python tools/reverse_engineer.py extract identity.png processed.png -o out.cube --size 33

Workflow
--------
1. `hald`    -> an identity Hald CLUT image. Import it into Dazz, apply ONE
                film profile, export it back out unchanged (PNG if possible).
2. `extract` -> turns (identity, processed) into an Adobe .cube 3D LUT that
                ZoomBox Camera loads from app/src/main/assets/luts/.
3. `chart`   -> master test chart + optional EV variants for measuring grain,
                sharpening, vignette, chromatic aberration and tone curve.

Hald CLUT layout (the de-facto standard, level L, image side = L*L):
  pixel (x, y) encodes the input colour
      R = (x mod L) / (L - 1)
      G = (y mod L) / (L - 1)
      B = (floor(x/L) + L * floor(y/L)) / (L*L - 1)
i.e. R and G are the in-tile ramps and B is the tile index. This matches the
layout used by ffmpeg's haldclut / haldclutsrc, so the same identity image can
be reused with other LUT tooling.
"""

import argparse
import math
import os
import struct
import sys
import zlib

PNG_SIG = b"\x89PNG\r\n\x1a\n"


# --------------------------------------------------------------------------
# PNG encoding / decoding (stdlib only)
# --------------------------------------------------------------------------

def _chunk(tag: bytes, data: bytes) -> bytes:
    return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)


def write_png_rgb(path: str, w: int, h: int, rgb: bytearray) -> None:
    """Write an 8-bit RGB image (colour type 2) from an interleaved buffer."""
    stride = w * 3
    if len(rgb) != w * h * 3:
        raise ValueError("rgb buffer size mismatch")
    comp = zlib.compressobj(6)
    idat = bytearray()
    for y in range(h):
        start = y * stride
        idat += comp.compress(b"\x00" + bytes(rgb[start:start + stride]))
    idat += comp.flush()
    ihdr = struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "wb") as f:
        f.write(PNG_SIG)
        f.write(_chunk(b"IHDR", ihdr))
        f.write(_chunk(b"IDAT", bytes(idat)))
        f.write(_chunk(b"IEND", b""))


def _paeth(a: int, b: int, c: int) -> int:
    p = a + b - c
    pa = abs(p - a)
    pb = abs(p - b)
    pc = abs(p - c)
    if pa <= pb and pa <= pc:
        return a
    return b if pb <= pc else c


def read_png_rgb(path: str):
    """Read a PNG, returning (width, height, rgb) with rgb interleaved 8-bit."""
    with open(path, "rb") as f:
        data = f.read()
    if data[:8] != PNG_SIG:
        raise ValueError(f"{path}: not a PNG file")

    pos = 8
    w = h = bit_depth = color_type = interlace = None
    idat = bytearray()
    while pos + 8 <= len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        ctype = data[pos + 4:pos + 8]
        payload = data[pos + 8:pos + 8 + length]
        if ctype == b"IHDR":
            w, h, bit_depth, color_type, _comp, _filt, interlace = struct.unpack(">IIBBBBB", payload)
        elif ctype == b"IDAT":
            idat += payload
        elif ctype == b"IEND":
            break
        pos += 12 + length

    if w is None or bit_depth != 8 or interlace != 0:
        raise ValueError(f"{path}: only 8-bit, non-interlaced PNGs are supported")
    bpp = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}.get(color_type)
    if bpp is None:
        raise ValueError(f"{path}: unsupported colour type {color_type}")

    raw = zlib.decompress(bytes(idat))
    stride = w * bpp
    img = bytearray(h * stride)
    prev = bytearray(stride)
    p = 0
    for y in range(h):
        ftype = raw[p]
        p += 1
        line = bytearray(raw[p:p + stride])
        p += stride
        if ftype == 1:
            for i in range(bpp, stride):
                line[i] = (line[i] + line[i - bpp]) & 0xFF
        elif ftype == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif ftype == 3:
            for i in range(stride):
                left = line[i - bpp] if i >= bpp else 0
                line[i] = (line[i] + ((left + prev[i]) >> 1)) & 0xFF
        elif ftype == 4:
            for i in range(stride):
                left = line[i - bpp] if i >= bpp else 0
                up = prev[i]
                upleft = prev[i - bpp] if i >= bpp else 0
                line[i] = (line[i] + _paeth(left, up, upleft)) & 0xFF
        # ftype == 0 (None) needs no change
        img[y * stride:(y + 1) * stride] = line
        prev = line

    if color_type == 2:
        return w, h, img

    # Expand gray / drop alpha to a uniform RGB buffer.
    rgb = bytearray(w * h * 3)
    if color_type == 0:
        for y in range(h):
            for x in range(w):
                v = img[y * w + x]
                o = (y * w + x) * 3
                rgb[o] = rgb[o + 1] = rgb[o + 2] = v
        return w, h, rgb
    if color_type == 4:
        for y in range(h):
            for x in range(w):
                v = img[(y * w + x) * 2]
                o = (y * w + x) * 3
                rgb[o] = rgb[o + 1] = rgb[o + 2] = v
        return w, h, rgb
    if color_type == 6:
        for i in range(w * h):
            rgb[i * 3] = img[i * 4]
            rgb[i * 3 + 1] = img[i * 4 + 1]
            rgb[i * 3 + 2] = img[i * 4 + 2]
        return w, h, rgb
    raise ValueError(f"{path}: palette PNGs are not supported")


# --------------------------------------------------------------------------
# Identity Hald CLUT
# --------------------------------------------------------------------------

def generate_hald(level: int) -> tuple:
    """Return (side, rgb) for the identity Hald CLUT at the given level."""
    side = level * level
    rvals = bytes(round(i * 255 / (level - 1)) for i in range(level))
    gvals = bytes(round(j * 255 / (level - 1)) for j in range(level))
    bvals = bytes(round(k * 255 / (side - 1)) for k in range(side))

    # R row is the same for every scanline: the 64-level ramp repeated per tile.
    rrow = rvals * level
    # G row is constant per (y % level).
    grows = [bytes([g]) * side for g in gvals]
    # B row depends only on the tile band (y // level); precompute the 64 bands.
    brows = []
    for tr in range(level):
        br = bytearray(side)
        for t in range(level):
            br[t * level:(t + 1) * level] = bytes([bvals[tr * level + t]]) * level
        brows.append(bytes(br))

    rgb = bytearray(side * side * 3)
    for y in range(side):
        out = bytearray(side * 3)
        out[0::3] = rrow
        out[1::3] = grows[y % level]
        out[2::3] = brows[y // level]
        rgb[y * side * 3:(y + 1) * side * 3] = out
    return side, rgb


# --------------------------------------------------------------------------
# Master test chart
# --------------------------------------------------------------------------

COLORCHECKER = [
    (115, 82, 68), (194, 150, 130), (98, 122, 157), (87, 108, 67),
    (133, 128, 177), (103, 189, 170),
    (214, 126, 44), (80, 91, 166), (193, 90, 99), (94, 60, 108),
    (157, 188, 64), (224, 163, 46),
    (56, 61, 150), (70, 148, 73), (175, 54, 60), (231, 199, 31),
    (187, 86, 149), (8, 133, 161),
    (243, 243, 242), (200, 200, 200), (160, 160, 160), (122, 122, 121),
    (85, 85, 85), (52, 52, 52),
]

SATURATED = [
    (255, 0, 0), (0, 255, 0), (0, 0, 255), (255, 255, 0),
    (0, 255, 255), (255, 0, 255), (255, 128, 0), (128, 0, 255),
]
PASTELS = [
    (255, 180, 180), (180, 255, 180), (180, 180, 255), (255, 255, 180),
    (180, 255, 255), (255, 180, 255), (255, 214, 179), (214, 179, 255),
]
SKIN = [
    (115, 82, 68), (194, 150, 130), (198, 134, 66), (141, 85, 36),
    (246, 207, 180), (224, 172, 105), (112, 74, 58), (255, 224, 196),
]


def _hsv(h: float, s: float, v: float):
    h %= 360.0
    c = v * s
    hp = h / 60.0
    x = c * (1.0 - abs(hp % 2.0 - 1.0))
    if hp < 1:
        r, g, b = c, x, 0.0
    elif hp < 2:
        r, g, b = x, c, 0.0
    elif hp < 3:
        r, g, b = 0.0, c, x
    elif hp < 4:
        r, g, b = 0.0, x, c
    elif hp < 5:
        r, g, b = x, 0.0, c
    else:
        r, g, b = c, 0.0, x
    m = v - c
    return round((r + m) * 255), round((g + m) * 255), round((b + m) * 255)


def _rect(r, g, b, w, x0, y0, rw, rh, rgb):
    cr, cg, cb = rgb
    fill_r = bytes([cr]) * rw
    fill_g = bytes([cg]) * rw
    fill_b = bytes([cb]) * rw
    for y in range(y0, y0 + rh):
        i0 = y * w + x0
        r[i0:i0 + rw] = fill_r
        g[i0:i0 + rw] = fill_g
        b[i0:i0 + rw] = fill_b


def _patch_grid(r, g, b, w, x0, y0, rw, rh, cols, rows, colors):
    pw = rw // cols
    ph = rh // rows
    for idx, col in enumerate(colors):
        cx = idx % cols
        cy = idx // cols
        if cy >= rows:
            break
        _rect(r, g, b, w, x0 + cx * pw, y0 + cy * ph, pw, ph, col)


def generate_chart(size: int):
    """Return (size, rgb) for the master reverse-engineering test chart."""
    if size < 4 or size % 4 != 0:
        raise ValueError("chart size must be a positive multiple of 4 (use 4096)")
    P = size // 4  # panel size
    w = h = size
    r = bytearray(w * h)
    g = bytearray(w * h)
    b = bytearray(w * h)
    _rect(r, g, b, w, 0, 0, w, h, (255, 255, 255))  # white background

    # Row 0: ColorChecker | gray wedge | hue wheel | vertical gradient
    _patch_grid(r, g, b, w, 0, 0, P, P, 6, 4, COLORCHECKER)

    bar_w = P // 21
    for i in range(21):
        v = round(i * 255 / 20)
        _rect(r, g, b, w, P + i * bar_w, 0, bar_w, P, (v, v, v))

    cx = cy = P / 2.0
    for y in range(P):
        for x in range(P):
            dx = x - cx
            dy = y - cy
            rad = math.hypot(dx, dy)
            if rad > P / 2.0:
                cr = cg = cb = 128
            else:
                hue = (math.degrees(math.atan2(dy, dx)) + 360.0) % 360.0
                cr, cg, cb = _hsv(hue, min(1.0, rad / (P / 2.0)), 1.0)
            i = y * w + 2 * P + x
            r[i] = cr
            g[i] = cg
            b[i] = cb

    for y in range(P):
        v = round(y * 255 / (P - 1))
        i0 = y * w + 3 * P
        r[i0:i0 + P] = bytes([v]) * P
        g[i0:i0 + P] = bytes([v]) * P
        b[i0:i0 + P] = bytes([v]) * P

    # Row 1: RGB/gray ramps | primaries+pastels | Siemens star | sat/value map
    ramp_row = P  # y0 = P
    band = P // 4
    ramps = [
        lambda x, ww: (round(x * 255 / (ww - 1)), 0, 0),
        lambda x, ww: (0, round(x * 255 / (ww - 1)), 0),
        lambda x, ww: (0, 0, round(x * 255 / (ww - 1))),
        lambda x, ww: (v := round(x * 255 / (ww - 1)), v, v),
    ]
    for k, fn in enumerate(ramps):
        for y in range(band):
            yy = ramp_row + k * band + y
            i0 = yy * w
            rr = bytearray(P)
            gg = bytearray(P)
            bb = bytearray(P)
            for x in range(P):
                cr, cg, cb = fn(x, P)
                rr[x] = cr
                gg[x] = cg
                bb[x] = cb
            r[i0:i0 + P] = rr
            g[i0:i0 + P] = gg
            b[i0:i0 + P] = bb

    _patch_grid(r, g, b, w, P, P, P, P // 2, 4, 2, SATURATED)
    _patch_grid(r, g, b, w, P, P + P // 2, P, P // 2, 4, 2, PASTELS)

    spokes = 144
    scx = scy = P / 2.0
    for y in range(P):
        for x in range(P):
            dx = x - scx
            dy = y - scy
            ang = (math.degrees(math.atan2(dy, dx)) + 360.0) % 360.0
            spoke = int(ang * spokes / 360.0)
            v = 0 if spoke % 2 == 0 else 255
            i = (ramp_row + y) * w + 2 * P + x
            r[i] = g[i] = b[i] = v

    for y in range(P):
        val = 1.0 - y / (P - 1)
        i0 = (ramp_row + y) * w + 3 * P
        rr = bytearray(P)
        gg = bytearray(P)
        bb = bytearray(P)
        for x in range(P):
            sat = x / (P - 1)
            cr, cg, cb = _hsv(0.0, sat, val)
            rr[x] = cr
            gg[x] = cg
            bb[x] = cb
        r[i0:i0 + P] = rr
        g[i0:i0 + P] = gg
        b[i0:i0 + P] = bb

    # Row 2: uniform black / 18% gray / 50% gray / white
    _rect(r, g, b, w, 0, 2 * P, P, P, (0, 0, 0))
    _rect(r, g, b, w, P, 2 * P, P, P, (46, 46, 46))     # 18% gray
    _rect(r, g, b, w, 2 * P, 2 * P, P, P, (128, 128, 128))
    _rect(r, g, b, w, 3 * P, 2 * P, P, P, (255, 255, 255))

    # Row 3: checkerboard | 1px lines | edge grid | skin tones
    cell = 16
    for y in range(P):
        yy = 3 * P + y
        for x in range(P):
            v = 0 if ((x // cell) + (y // cell)) % 2 == 0 else 255
            i = yy * w + x
            r[i] = g[i] = b[i] = v

    for y in range(P):
        yy = 3 * P + y
        for x in range(P):
            if x < P // 2:
                v = 0 if x % 2 == 0 else 255   # 1px vertical stripes
            else:
                v = 0 if y % 2 == 0 else 255   # 1px horizontal stripes
            i = yy * w + P + x
            r[i] = g[i] = b[i] = v

    step = 64
    for y in range(P):
        yy = 3 * P + y
        for x in range(P):
            v = 0 if (x % step == 0 or y % step == 0) else 255
            i = yy * w + 2 * P + x
            r[i] = g[i] = b[i] = v

    _rect(r, g, b, w, 3 * P, 3 * P, P, P, (128, 128, 128))
    _patch_grid(r, g, b, w, 3 * P, 3 * P, P, P, 4, 2, SKIN)

    rgb = bytearray(w * h * 3)
    rgb[0::3] = bytes(r)
    rgb[1::3] = bytes(g)
    rgb[2::3] = bytes(b)
    return size, rgb


def apply_gain(rgb: bytearray, ev: float) -> bytearray:
    """Multiply an interleaved 8-bit image by 2**ev (clamped), C-speed via translate."""
    gain = 2.0 ** ev
    lut = bytes(min(255, max(0, round(i * gain))) for i in range(256))
    return rgb.translate(lut)


# --------------------------------------------------------------------------
# Hald -> .cube extraction
# --------------------------------------------------------------------------

def _lattice_sample(processed, level, ri, gi, bi):
    """Trilinear sample of the Hald lattice at continuous (ri, gi, bi)."""
    side = level * level
    r0 = int(ri)
    g0 = int(gi)
    b0 = int(bi)
    dr = ri - r0
    dg = gi - g0
    db = bi - b0
    r1 = min(r0 + 1, level - 1)
    g1 = min(g0 + 1, level - 1)
    b1 = min(b0 + 1, side - 1)

    def px(rr, gg, bb):
        x = rr + level * (bb % level)
        y = gg + level * (bb // level)
        i = (y * side + x) * 3
        return processed[i], processed[i + 1], processed[i + 2]

    c000 = px(r0, g0, b0)
    c100 = px(r1, g0, b0)
    c010 = px(r0, g1, b0)
    c110 = px(r1, g1, b0)
    c001 = px(r0, g0, b1)
    c101 = px(r1, g0, b1)
    c011 = px(r0, g1, b1)
    c111 = px(r1, g1, b1)

    out = []
    for ch in range(3):
        lo = (c000[ch] * (1 - dr) + c100[ch] * dr) * (1 - dg) + (c010[ch] * (1 - dr) + c110[ch] * dr) * dg
        hi = (c001[ch] * (1 - dr) + c101[ch] * dr) * (1 - dg) + (c011[ch] * (1 - dr) + c111[ch] * dr) * dg
        out.append((lo * (1 - db) + hi * db) / 255.0)
    return out


def extract_cube(processed, level: int, size: int):
    """Return the .cube file text (list of lines) from a processed Hald image."""
    side = level * level
    lines = [
        "# Extracted from a Hald CLUT by tools/reverse_engineer.py",
        f"TITLE \"dazz_extracted_{level}\"",
        "",
        "LUT_3D_SIZE %d" % size,
        "",
        "DOMAIN_MIN 0.0 0.0 0.0",
        "DOMAIN_MAX 1.0 1.0 1.0",
        "",
    ]
    n1 = size - 1
    for bb in range(size):
        bi = bb * (side - 1) / n1 if n1 else 0.0
        for gg in range(size):
            gi = gg * (level - 1) / n1 if n1 else 0.0
            for rr in range(size):
                ri = rr * (level - 1) / n1 if n1 else 0.0
                cr, cg, cb = _lattice_sample(processed, level, ri, gi, bi)
                lines.append("%.6f %.6f %.6f" % (cr, cg, cb))
    return lines


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------

def cmd_hald(args):
    side, rgb = generate_hald(args.level)
    write_png_rgb(args.output, side, side, rgb)
    print(f"Wrote {side}x{side} identity Hald CLUT (level {args.level}) -> {args.output}")


def cmd_chart(args):
    size, rgb = generate_chart(args.size)
    write_png_rgb(args.output, size, size, rgb)
    print(f"Wrote {size}x{size} master test chart -> {args.output}")

    evs = [float(v) for v in (args.exposures or "").split(",") if v.strip() != ""]
    for ev in evs:
        if abs(ev) < 1e-9:
            continue  # 0 EV is the base chart itself
        stem, ext = os.path.splitext(args.output)
        out = f"{stem}_ev{ev:+.0f}{ext}"
        write_png_rgb(out, size, size, apply_gain(rgb, ev))
        print(f"  EV {ev:+.0f} variant -> {out}")


def cmd_extract(args):
    iw, ih, _identity = read_png_rgb(args.identity)
    pw, ph, processed = read_png_rgb(args.processed)
    if (iw, ih) != (pw, ph):
        raise SystemExit("identity and processed images must have identical dimensions")
    level = math.isqrt(iw)
    if level * level != iw or iw != ih:
        raise SystemExit(f"image must be square with side == level^2 (e.g. 4096), got {iw}x{ih}")
    if args.size < 2 or args.size > 256:
        raise SystemExit("--size must be between 2 and 256 (33 or 64 recommended)")

    lines = extract_cube(processed, level, args.size)
    os.makedirs(os.path.dirname(os.path.abspath(args.output)), exist_ok=True)
    with open(args.output, "w") as f:
        f.write("\n".join(lines) + "\n")
    n = args.size
    print(f"Extracted {n}x{n}x{n} .cube LUT from {args.processed} (Hald level {level}) -> {args.output}")


def main(argv=None):
    p = argparse.ArgumentParser(description="Dazz Cam reverse-engineering toolkit")
    sub = p.add_subparsers(dest="cmd", required=True)

    ph = sub.add_parser("hald", help="generate an identity Hald CLUT image")
    ph.add_argument("--level", type=int, default=64, help="Hald level (side = level^2); 64 -> 4096x4096")
    ph.add_argument("-o", "--output", default="identity_hald.png")
    ph.set_defaults(fn=cmd_hald)

    pc = sub.add_parser("chart", help="generate the master reverse-engineering test chart")
    pc.add_argument("--size", type=int, default=4096, help="square canvas size (multiple of 4)")
    pc.add_argument("--exposures", default=None, help="comma list of EV offsets, e.g. -2,-1,1,2")
    pc.add_argument("-o", "--output", default="master_test_chart.png")
    pc.set_defaults(fn=cmd_chart)

    pe = sub.add_parser("extract", help="convert (identity, processed) Hald pair into a .cube LUT")
    pe.add_argument("identity", help="identity Hald CLUT PNG")
    pe.add_argument("processed", help="Hald CLUT PNG after a filter/profile was applied")
    pe.add_argument("-o", "--output", default="out.cube")
    pe.add_argument("--size", type=int, default=33, help="output cubic LUT size (33 or 64 recommended)")
    pe.set_defaults(fn=cmd_extract)

    args = p.parse_args(argv)
    args.fn(args)


if __name__ == "__main__":
    main()
