"""
analyse_break.py

Analyses orange pixel fraction and column coverage in the BREAK text region
across a tree of frame directories. Produces per-directory contact sheets and
a CSV of all scores, to support threshold selection.

Two scores are computed per frame:
  orange_frac   — fraction of pixels in the crop region matching the orange
                  colour of the BREAK text. High for fully rendered BREAK,
                  near-zero for most combat frames.
  col_coverage  — fraction of columns in the crop region that contain at
                  least one orange pixel. Discriminates wide BREAK text from
                  narrow combat effects (explosions, beams) that may contain
                  orange pixels but only in a small horizontal band.

Orange pixel definition (RGB):
  R > 150, G > 80, B < 120, R > G + 40
  Empirically derived from Radobaan and Tzitzi BREAK frames.
  Adjust --r-min, --g-min, --b-max, --rg-gap if needed.

Usage:
    python analyse_break.py --frames path/to/frames/root
                            --out    path/to/output/dir

    --frames     Root directory. All PNGs found recursively are processed.
    --out        Output directory for contact sheets and CSV.
                 Defaults to ./break_analysis

    --x1 --y1 --x2 --y2   Crop region. Defaults match FightScreenConstants:
                           x1=370 y1=520 x2=712 y2=600

    --frac-threshold    Orange fraction threshold for green/red border (default 0.20)
    --cov-threshold     Column coverage threshold for green/red border (default 0.70)

    Both thresholds must be exceeded for a cell to show a green border.

    --r-min   Minimum R for orange pixel (default 150)
    --g-min   Minimum G for orange pixel (default 80)
    --b-max   Maximum B for orange pixel (default 120)
    --rg-gap  Minimum R-G gap for orange pixel (default 40)

Contact sheet layout:
    One image per source directory, 4 crops wide.
    Each cell shows the raw crop with filename and both scores below it.
    Cell border: green if both scores meet thresholds, red otherwise.

CSV output:
    One row per frame: path, directory, filename, orange_frac, col_coverage
"""

import argparse
import csv
import os
import sys
from pathlib import Path
from collections import defaultdict

import numpy as np
from PIL import Image, ImageDraw, ImageFont


# ---------------------------------------------------------------------------
# Crop constants — match FightScreenConstants.kt
# ---------------------------------------------------------------------------

DEFAULT_X1 = 370
DEFAULT_Y1 = 520
DEFAULT_X2 = 712
DEFAULT_Y2 = 600

CROP_W = DEFAULT_X2 - DEFAULT_X1   # 342
CROP_H = DEFAULT_Y2 - DEFAULT_Y1   # 80

# ---------------------------------------------------------------------------
# Default orange pixel thresholds
# ---------------------------------------------------------------------------

DEFAULT_R_MIN  = 150
DEFAULT_G_MIN  = 80
DEFAULT_B_MAX  = 120
DEFAULT_RG_GAP = 40

# ---------------------------------------------------------------------------
# Contact sheet layout
# ---------------------------------------------------------------------------

SHEET_COLS    = 4
LABEL_H       = 14
SCORE_H       = 14
CELL_PAD      = 4
CELL_GAP      = 6
BORDER_W      = 2

BG_COLOUR     = (30,  30,  30)
CELL_BG       = (50,  50,  50)
LABEL_COLOUR  = (180, 180, 180)
SCORE_COLOUR  = (220, 220, 120)
PASS_BORDER   = (60,  160,  60)
FAIL_BORDER   = (160,  60,  60)
HEADER_COLOUR = (160, 200, 255)

CELL_W = BORDER_W + CELL_PAD + CROP_W + CELL_PAD + BORDER_W
CELL_H = BORDER_W + CELL_PAD + LABEL_H + CROP_H + SCORE_H + CELL_PAD + BORDER_W


# ---------------------------------------------------------------------------
# Orange pixel scoring
# ---------------------------------------------------------------------------

def orange_mask(crop_rgb: np.ndarray,
                r_min:  int,
                g_min:  int,
                b_max:  int,
                rg_gap: int) -> np.ndarray:
    """
    Return boolean mask of orange pixels in an RGB crop.
    Orange: high R, medium G, low B, R clearly greater than G.
    """
    r = crop_rgb[:, :, 0].astype(np.int16)
    g = crop_rgb[:, :, 1].astype(np.int16)
    b = crop_rgb[:, :, 2].astype(np.int16)
    return (r > r_min) & (g > g_min) & (b < b_max) & ((r - g) > rg_gap)


def score_frame(crop_rgb:  np.ndarray,
                r_min:     int,
                g_min:     int,
                b_max:     int,
                rg_gap:    int) -> tuple[float, float]:
    """
    Compute (orange_frac, col_coverage) for one crop.

    orange_frac  — fraction of all pixels that are orange
    col_coverage — fraction of columns containing at least one orange pixel
    """
    mask       = orange_mask(crop_rgb, r_min, g_min, b_max, rg_gap)
    total      = mask.size
    orange_frac  = float(mask.sum()) / total

    crop_w       = crop_rgb.shape[1]
    col_coverage = float((mask.sum(axis=0) > 0).sum()) / crop_w

    return orange_frac, col_coverage


# ---------------------------------------------------------------------------
# Frame processing
# ---------------------------------------------------------------------------

def process_frame(frame_path: str,
                  x1: int, y1: int, x2: int, y2: int,
                  r_min: int, g_min: int, b_max: int, rg_gap: int,
                  ) -> tuple[np.ndarray, float, float] | None:
    """
    Load frame, extract crop, compute scores.
    Returns (crop_rgb, orange_frac, col_coverage) or None on failure.
    """
    try:
        img = Image.open(frame_path).convert("RGB")
        arr = np.array(img)
    except Exception as e:
        print(f"  WARNING: could not load {frame_path}: {e}")
        return None

    if arr.shape[0] < y2 or arr.shape[1] < x2:
        print(f"  WARNING: {frame_path} is {arr.shape[1]}x{arr.shape[0]}, "
              f"too small for crop ({x2}x{y2}), skipping")
        return None

    crop_rgb               = arr[y1:y2, x1:x2]
    orange_frac, col_cov   = score_frame(crop_rgb, r_min, g_min, b_max, rg_gap)
    return crop_rgb, orange_frac, col_cov


# ---------------------------------------------------------------------------
# Orange overlay for contact sheet
# ---------------------------------------------------------------------------

def overlay_orange(crop_rgb: np.ndarray,
                   r_min: int, g_min: int, b_max: int, rg_gap: int,
                   ) -> np.ndarray:
    """
    Return a copy of crop_rgb with orange pixels highlighted in bright cyan,
    making it easy to see exactly which pixels are being counted.
    """
    out  = crop_rgb.copy()
    mask = orange_mask(crop_rgb, r_min, g_min, b_max, rg_gap)
    out[mask] = [0, 220, 220]   # cyan highlight
    return out


# ---------------------------------------------------------------------------
# Contact sheet
# ---------------------------------------------------------------------------

def _try_load_font(size: int = 11):
    try:
        return ImageFont.truetype("DejaVuSansMono.ttf", size)
    except Exception:
        try:
            return ImageFont.truetype(
                "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf", size)
        except Exception:
            return ImageFont.load_default()


def build_contact_sheet(
    results:       list[tuple[str, np.ndarray, float, float]],
    dir_label:     str,
    frac_thresh:   float,
    cov_thresh:    float,
    r_min: int, g_min: int, b_max: int, rg_gap: int,
) -> Image.Image:
    """
    Build contact sheet for one directory.
    Each cell shows the crop with orange pixels highlighted in cyan.
    Green border = both thresholds met. Red = either threshold missed.

    results: list of (filename, crop_rgb, orange_frac, col_coverage)
    """
    font   = _try_load_font(11)
    n      = len(results)
    n_cols = SHEET_COLS
    n_rows = (n + n_cols - 1) // n_cols

    # Two rows of crop per cell: raw on top, highlighted below
    cell_h_two = (BORDER_W + CELL_PAD + LABEL_H +
                  CROP_H + 2 +          # raw crop + gap
                  CROP_H +              # highlighted crop
                  SCORE_H + CELL_PAD + BORDER_W)

    header_h = 20
    total_w  = n_cols * CELL_W + (n_cols - 1) * CELL_GAP
    total_h  = header_h + n_rows * cell_h_two + (n_rows - 1) * CELL_GAP

    sheet = Image.new("RGB", (total_w, total_h), BG_COLOUR)
    draw  = ImageDraw.Draw(sheet)
    draw.text((4, 4), dir_label, fill=HEADER_COLOUR, font=font)

    for i, (fname, crop_rgb, o_frac, c_cov) in enumerate(results):
        col = i % n_cols
        row = i // n_cols

        cx = col * (CELL_W + CELL_GAP)
        cy = header_h + row * (cell_h_two + CELL_GAP)

        passes      = (o_frac >= frac_thresh) and (c_cov >= cov_thresh)
        border_col  = PASS_BORDER if passes else FAIL_BORDER

        draw.rectangle(
            [cx, cy, cx + CELL_W - 1, cy + cell_h_two - 1],
            outline=border_col, width=BORDER_W,
        )

        inner_x = cx + BORDER_W + CELL_PAD
        inner_y = cy + BORDER_W + CELL_PAD

        # Filename label
        draw.text((inner_x, inner_y), os.path.basename(fname),
                  fill=LABEL_COLOUR, font=font)
        inner_y += LABEL_H

        # Raw crop
        sheet.paste(Image.fromarray(crop_rgb.astype(np.uint8)), (inner_x, inner_y))
        inner_y += CROP_H + 2

        # Highlighted crop (orange pixels → cyan)
        highlighted = overlay_orange(crop_rgb, r_min, g_min, b_max, rg_gap)
        sheet.paste(Image.fromarray(highlighted.astype(np.uint8)), (inner_x, inner_y))
        inner_y += CROP_H

        # Score line
        frac_flag = "✓" if o_frac >= frac_thresh else "✗"
        cov_flag  = "✓" if c_cov  >= cov_thresh  else "✗"
        score_txt = f"frac={o_frac:.3f}{frac_flag}  cov={c_cov:.3f}{cov_flag}"
        draw.text((inner_x, inner_y), score_txt, fill=SCORE_COLOUR, font=font)

    return sheet


# ---------------------------------------------------------------------------
# Directory traversal
# ---------------------------------------------------------------------------

def collect_frames(root: str) -> dict[str, list[str]]:
    by_dir: dict[str, list[str]] = defaultdict(list)
    for dirpath, _, filenames in os.walk(root):
        for fname in sorted(filenames):
            if fname.lower().endswith(".png"):
                by_dir[dirpath].append(os.path.join(dirpath, fname))
    return dict(sorted(by_dir.items()))


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(
        description="Analyse BREAK orange pixel scores across frame directories.")
    parser.add_argument("--frames",  required=True,
                        help="Root directory to search recursively for PNGs")
    parser.add_argument("--out",     default="break_analysis",
                        help="Output directory (default: ./break_analysis)")
    parser.add_argument("--x1",  type=int, default=DEFAULT_X1)
    parser.add_argument("--y1",  type=int, default=DEFAULT_Y1)
    parser.add_argument("--x2",  type=int, default=DEFAULT_X2)
    parser.add_argument("--y2",  type=int, default=DEFAULT_Y2)
    parser.add_argument("--frac-threshold", type=float, default=0.20,
                        help="Orange fraction threshold (default 0.20)")
    parser.add_argument("--cov-threshold",  type=float, default=0.70,
                        help="Column coverage threshold (default 0.70)")
    parser.add_argument("--r-min",  type=int, default=DEFAULT_R_MIN)
    parser.add_argument("--g-min",  type=int, default=DEFAULT_G_MIN)
    parser.add_argument("--b-max",  type=int, default=DEFAULT_B_MAX)
    parser.add_argument("--rg-gap", type=int, default=DEFAULT_RG_GAP)
    args = parser.parse_args()

    by_dir = collect_frames(args.frames)
    total_frames = sum(len(v) for v in by_dir.values())

    if total_frames == 0:
        print(f"No PNG files found under {args.frames}")
        sys.exit(1)

    print(f"Crop region: ({args.x1},{args.y1})->({args.x2},{args.y2})  "
          f"{args.x2-args.x1}x{args.y2-args.y1}px")
    print(f"Orange definition: R>{args.r_min} G>{args.g_min} "
          f"B<{args.b_max} R-G>{args.rg_gap}")
    print(f"Thresholds: frac>={args.frac_threshold}  cov>={args.cov_threshold}")
    print(f"\nFound {total_frames} PNG(s) across {len(by_dir)} director(ies)")

    os.makedirs(args.out, exist_ok=True)

    csv_rows        = []
    all_frac_scores = []
    all_cov_scores  = []

    for dir_path, frame_paths in by_dir.items():
        rel_dir   = os.path.relpath(dir_path, args.frames)
        dir_label = rel_dir if rel_dir != "." else os.path.basename(dir_path)

        print(f"\n{dir_label}  ({len(frame_paths)} frame(s))")

        results = []
        for fpath in frame_paths:
            out = process_frame(
                fpath,
                args.x1, args.y1, args.x2, args.y2,
                args.r_min, args.g_min, args.b_max, args.rg_gap,
            )
            if out is None:
                continue
            crop_rgb, o_frac, c_cov = out
            fname = os.path.basename(fpath)
            results.append((fname, crop_rgb, o_frac, c_cov))
            csv_rows.append({
                "path":         fpath,
                "directory":    dir_label,
                "filename":     fname,
                "orange_frac":  f"{o_frac:.4f}",
                "col_coverage": f"{c_cov:.4f}",
            })
            all_frac_scores.append(o_frac)
            all_cov_scores.append(c_cov)

        if not results:
            print("  No processable frames.")
            continue

        frac_scores = [r[2] for r in results]
        cov_scores  = [r[3] for r in results]
        passing     = sum(1 for f, c in zip(frac_scores, cov_scores)
                          if f >= args.frac_threshold and c >= args.cov_threshold)

        print(f"  orange_frac:  min={min(frac_scores):.3f}  "
              f"max={max(frac_scores):.3f}  "
              f"mean={sum(frac_scores)/len(frac_scores):.3f}")
        print(f"  col_coverage: min={min(cov_scores):.3f}  "
              f"max={max(cov_scores):.3f}  "
              f"mean={sum(cov_scores)/len(cov_scores):.3f}")
        print(f"  Both thresholds met: {passing}/{len(results)}")

        sheet      = build_contact_sheet(
            results, dir_label,
            args.frac_threshold, args.cov_threshold,
            args.r_min, args.g_min, args.b_max, args.rg_gap,
        )
        safe_name  = dir_label.replace(os.sep, "_").replace("/", "_").replace("\\", "_")
        sheet_path = os.path.join(args.out, f"{safe_name}_sheet.png")
        sheet.save(sheet_path)
        print(f"  Contact sheet -> {sheet_path}")

    # CSV
    csv_path = os.path.join(args.out, "scores.csv")
    with open(csv_path, "w", newline="") as f:
        writer = csv.DictWriter(
            f, fieldnames=["path", "directory", "filename",
                           "orange_frac", "col_coverage"])
        writer.writeheader()
        writer.writerows(csv_rows)
    print(f"\nCSV -> {csv_path}")

    # Overall summary with distribution histograms
    if all_frac_scores:
        print(f"\n{'='*60}")
        print(f"Overall across {len(all_frac_scores)} frame(s):")
        print(f"  orange_frac:  min={min(all_frac_scores):.4f}  "
              f"max={max(all_frac_scores):.4f}  "
              f"mean={sum(all_frac_scores)/len(all_frac_scores):.4f}")
        print(f"  col_coverage: min={min(all_cov_scores):.4f}  "
              f"max={max(all_cov_scores):.4f}  "
              f"mean={sum(all_cov_scores)/len(all_cov_scores):.4f}")

        print("\n  orange_frac distribution (bucket width 0.05):")
        buckets: dict[float, int] = defaultdict(int)
        for s in all_frac_scores:
            bucket = round(int(s * 20) / 20, 2)
            buckets[bucket] += 1
        for bucket in sorted(buckets):
            bar = "█" * buckets[bucket]
            marker = " <-- frac threshold" if abs(bucket - args.frac_threshold) < 0.025 else ""
            print(f"  {bucket:.2f}  {bar}  ({buckets[bucket]}){marker}")

        print("\n  col_coverage distribution (bucket width 0.05):")
        buckets = defaultdict(int)
        for s in all_cov_scores:
            bucket = round(int(s * 20) / 20, 2)
            buckets[bucket] += 1
        for bucket in sorted(buckets):
            bar = "█" * buckets[bucket]
            marker = " <-- cov threshold" if abs(bucket - args.cov_threshold) < 0.025 else ""
            print(f"  {bucket:.2f}  {bar}  ({buckets[bucket]}){marker}")

        print(f"{'='*60}")


if __name__ == "__main__":
    main()
