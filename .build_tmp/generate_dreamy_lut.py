#!/usr/bin/env python3
"""
Generates a 25-cube `dreamy.cube` LUT for the new DREAMY film preset.

The transform is intentionally simple — a single coherent pass per sample
so the LUT stays small (~150 KB) and the dreamy look is predictable across
exposure. Each output channel is computed from the input (r, g, b) by:

  1. Tone: a soft S-curve biased toward shadow lift and gentle highlight
     rolloff (no clipping at either end).
  2. Desaturation: pull every channel ~14% toward the Rec.709 luma.
  3. Pastel tint: cool the shadows toward blue-violet, warm the highlights
     toward peach. The tint is luma-weighted so it transitions smoothly.
  4. Final 0.95 gain keeps the brightest highlight just under 1.0 so the
     LUT-stacked bloom / film-curve uniforms never clip together.

The output is laid out as ((b * SIZE) + g) * SIZE + r — R varies fastest,
matching the G'MIC / Adobe convention used by the rest of the app's LUTs.
"""
from pathlib import Path
import struct

SIZE = 25
OUT_PATH = Path("app/src/main/assets/luts/dreamy.cube")
OUT_PATH.parent.mkdir(parents=True, exist_ok=True)

LUMA = (0.299, 0.587, 0.114)


def tone(x: float) -> float:
    """Gentle S-curve biased toward shadow lift + smooth highlight rolloff.

    Math notes:
    - toe (1 - exp(-x * 4)) lifts the floor of the darkest input; strength
      0.18 keeps it subtle — too much collapses the photo into a wash.
    - shoulder (1 - exp(-(1 - x) * 5)) * 0.22 compresses the brightest
      output without flattening it; without it, blown-out skies clip.
    - The midtone bias (x - 0.5) * 0.10 nudges overall midtones brighter
      so the image reads softer than neutral.
    """
    toe = (1.0 - pow(2.718281828, -x * 4.0)) * 0.18
    shoulder = (1.0 - pow(2.718281828, -(1.0 - x) * 5.0)) * 0.22
    mid = (x - 0.5) * 0.10
    return max(0.0, min(1.0, x + toe - shoulder + mid))


def dreamy_channel(r: float, g: float, b: float) -> tuple[float, float, float]:
    """Apply tone + desaturation + pastel tint per channel."""
    # Step 1: per-channel tone.
    rt = tone(r)
    gt = tone(g)
    bt = tone(b)

    # Step 2: stronger desaturation (~20% pull toward luma). Pairs with the
    # heavy on-GPU bloom uniform so the brightest pastel highlights gloss
    # into a creamy haze rather than smear into muddy yellows.
    luma = 0.299 * rt + 0.587 * gt + 0.114 * bt
    desat = 0.80
    r2 = luma + (rt - luma) * desat
    g2 = luma + (gt - luma) * desat
    b2 = luma + (bt - luma) * desat

    # Step 3: pastel tint weighted by luma.
    # Shadows (low luma) get a faint cool blue-violet bias; highlights
    # (high luma) get a warm peach bias. The per-row blue push is kept
    # small (~+0.03) so the deepest blacks read as soft cream-mauve
    # rather than saturated blue — matching the per-preset `shadowTintB`
    # the CPU/GL post-processing adds on top. Without this the two
    # blue sources stack into a heavy cool haze at luma=0.
    shadow_w = max(0.0, 1.0 - luma * 2.2)
    highlight_w = max(0.0, (luma - 0.4) * 1.6)
    r2 += -0.01 * shadow_w + 0.04 * highlight_w
    g2 += -0.005 * shadow_w + 0.02 * highlight_w
    b2 += +0.03 * shadow_w - 0.02 * highlight_w

    # Step 4: final gain + clamp — keep brightest output just under 1.0 so
    # the bloom + film-curve uniforms don't stack-clip on bright skies.
    r2 *= 0.95
    g2 *= 0.95
    b2 *= 0.95
    return max(0.0, min(1.0, r2)), max(0.0, min(1.0, g2)), max(0.0, min(1.0, b2))


def fmt(v: float) -> str:
    """Match the precision of the existing Polaroid / Kodak LUTs (~6 dp)."""
    return f"{v:.6f}"


def main() -> None:
    lines: list[str] = [
        "# Dreamy preset — soft, lifted-shadow, pastel-tinted grade",
        "TITLE \"Dreamy\"",
        f"LUT_3D_SIZE {SIZE}",
        "DOMAIN_MIN 0.0 0.0 0.0",
        "DOMAIN_MAX 1.0 1.0 1.0",
    ]
    # Walk R fastest: ((b * SIZE) + g) * SIZE + r.
    inv = 1.0 / (SIZE - 1)
    for bi in range(SIZE):
        b_in = bi * inv
        for gi in range(SIZE):
            g_in = gi * inv
            for ri in range(SIZE):
                r_in = ri * inv
                ro, go, bo = dreamy_channel(r_in, g_in, b_in)
                lines.append(f"{fmt(ro)} {fmt(go)} {fmt(bo)}")

    expected = SIZE * SIZE * SIZE
    data_lines = expected  # header is 5 lines above (including comments/title).
    OUT_PATH.write_text("\n".join(lines) + "\n", encoding="utf-8")
    # Sanity check.
    actual = OUT_PATH.read_text(encoding="utf-8").splitlines()
    data_only = [l for l in actual if l and not l.startswith("#")
                 and not l.startswith("TITLE") and not l.startswith("LUT_3D_SIZE")
                 and not l.startswith("DOMAIN_")]
    assert len(data_only) == data_lines, (
        f"Expected {data_lines} data rows, got {len(data_only)}"
    )
    print(f"wrote {OUT_PATH} ({len(actual)} lines, {data_lines} data rows)")


if __name__ == "__main__":
    main()
