"""
analyse_hunt_report.py

Analyses how well a template matches the "Hunt Report" crop region across
a tree of frame directories. Produces per-directory contact sheets and a
CSV of all scores, to support threshold selection.

Two scores are computed per frame:
  grey_ncc   — NCC on greyscale crops. Sensitive to both shape and tone.
  binary_ncc — NCC on binarised crops (Otsu threshold). Sensitive to text
               shape only, ignoring background tone variation. More robust
               to background colour changes (e.g. dark loading screen).

Usage:
    python analyse_hunt_report.py --template path/to/template.png
                                  --frames   path/to/frames/root
                                  --out      path/to/output/dir

    --template   Crop of the Hunt Report text at native resolution (387x61).
                 Should come from a MediaProjection-saved frame, not ffmpeg.
    --frames     Root directory. All PNGs found recursively are processed.
    --out        Output directory for contact sheets and CSV.
                 Created if it does not exist. Defaults to ./hunt_report_analysis

    --x1  --y1  --x2  --y2   Crop region in full-frame pixel coordinates.
                              Defaults match RewardsScreenConstants:
                              x1=66 y1=225 x2=453 y2=286

Contact sheet layout:
    One image per source directory, 4 crops wide.
    Each cell shows the raw crop with filename and both scores below it.
    Cell border: green if grey_ncc >= threshold, red if below.
    Threshold line shown on the score distribution for reference.

CSV output:
    One row per frame: path, directory, filename, grey_ncc, binary_ncc

Run with --threshold to set the green/red border threshold (default 0.5).
This is a display aid only — it does not affect the scores.
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
# Crop constants — match RewardsScreenConstants.kt
# ---------------------------------------------------------------------------

DEFAULT_X1 = 66
DEFAULT_Y1 = 225
DEFAULT_X2 = 453
DEFAULT_Y2 = 286

CROP_W = DEFAULT_X2 - DEFAULT_X1   # 387
CROP_H = DEFAULT_Y2 - DEFAULT_Y1   # 61

# ---------------------------------------------------------------------------
# Contact sheet layout
# ---------------------------------------------------------------------------

SHEET_COLS      = 4
LABEL_H         = 14    # height of filename label above crop
SCORE_H         = 14    # height of score line below crop
CELL_PAD        = 4     # padding inside cell border
CELL_GAP        = 6     # gap between cells
BORDER_W        = 2     # cell border thickness

FONT_SCALE      = 0.30  # notional; we use PIL default font

BG_COLOUR       = (30,  30,  30)
CELL_BG         = (50,  50,  50)
LABEL_COLOUR    = (180, 180, 180)
SCORE_COLOUR    = (220, 220, 120)
PASS_BORDER     = (60,  160,  60)
MID_BORDER      = (60,  60,  160)
FAIL_BORDER     = (160,  60,  60)
HEADER_COLOUR   = (160, 200, 255)

CELL_W = BORDER_W + CELL_PAD + CROP_W + CELL_PAD + BORDER_W
CELL_H = BORDER_W + CELL_PAD + LABEL_H + CROP_H + SCORE_H + CELL_PAD + BORDER_W


# ---------------------------------------------------------------------------
# NCC helpers
# ---------------------------------------------------------------------------

def _ncc(a: np.ndarray, b: np.ndarray) -> float:
    """
    Normalised cross-correlation of two same-shape float arrays.
    Returns 0.0 if either array has zero variance (flat region).
    """
    a = a.astype(np.float32)
    b = b.astype(np.float32)
    a_n = a - a.mean()
    b_n = b - b.mean()
    denom = np.linalg.norm(a_n) * np.linalg.norm(b_n)
    if denom < 1e-6:
        return 0.0
    return float(np.dot(a_n.ravel(), b_n.ravel()) / denom)


def greyscale_ncc(template_grey: np.ndarray, crop_grey: np.ndarray) -> float:
    """NCC on greyscale arrays. Sensitive to both shape and tone."""
    return _ncc(template_grey, crop_grey)


def binary_ncc(template_bin: np.ndarray, crop_grey: np.ndarray) -> float:
    """
    NCC between the pre-binarised template and an Otsu-binarised crop.
    Sensitive to text shape only — ignores background tone.
    Returns 0.0 if the crop is flat (no variance after binarisation).
    """
    crop_bin = _otsu_binarise(crop_grey)
    return _ncc(template_bin.astype(np.float32), crop_bin.astype(np.float32))


def _otsu_binarise(grey: np.ndarray) -> np.ndarray:
    """
    Simple Otsu threshold on a greyscale array.
    Returns boolean array: True = dark (text), False = light (background).
    Falls back to mean threshold if the image is very flat.
    """
    grey_u8 = np.clip(grey, 0, 255).astype(np.uint8)
    hist, _ = np.histogram(grey_u8, bins=256, range=(0, 256))
    total = grey_u8.size

    best_thresh = grey_u8.mean()
    best_var    = 0.0

    w0 = 0
    sum0 = 0
    total_sum = float(np.dot(np.arange(256), hist))

    for t in range(256):
        w0 += hist[t]
        if w0 == 0:
            continue
        w1 = total - w0
        if w1 == 0:
            break
        sum0 += t * hist[t]
        mu0 = sum0 / w0
        mu1 = (total_sum - sum0) / w1
        var = w0 * w1 * (mu0 - mu1) ** 2
        if var > best_var:
            best_var    = var
            best_thresh = t

    return grey_u8 < best_thresh   # True = dark = text


# ---------------------------------------------------------------------------
# Frame processing
# ---------------------------------------------------------------------------

def process_frame(
    frame_path:    str,
    template_grey: np.ndarray,
    template_bin:  np.ndarray,
    x1: int, y1: int, x2: int, y2: int,
) -> tuple[np.ndarray, float, float] | None:
    """
    Load a frame, extract the crop, compute both scores.
    Returns (crop_rgb, grey_ncc, binary_ncc) or None on load failure.
    """
    try:
        img  = Image.open(frame_path).convert("RGB")
        arr  = np.array(img)
    except Exception as e:
        print(f"  WARNING: could not load {frame_path}: {e}")
        return None

    if arr.shape[0] < y2 or arr.shape[1] < x2:
        print(f"  WARNING: {frame_path} is {arr.shape[1]}x{arr.shape[0]}, "
              f"too small for crop region ({x2}x{y2}), skipping")
        return None

    crop_rgb  = arr[y1:y2, x1:x2]
    crop_grey = crop_rgb.mean(axis=2)

    g_ncc = greyscale_ncc(template_grey, crop_grey)
    b_ncc = binary_ncc(template_bin, crop_grey)

    return crop_rgb, g_ncc, b_ncc


# ---------------------------------------------------------------------------
# Contact sheet builder
# ---------------------------------------------------------------------------

def _try_load_font(size: int = 11):
    """Load a small monospace font if available, fall back to default."""
    try:
        return ImageFont.truetype("DejaVuSansMono.ttf", size)
    except Exception:
        try:
            return ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf", size)
        except Exception:
            return ImageFont.load_default()


def build_contact_sheet(
    results:    list[tuple[str, np.ndarray, float, float]],
    dir_label:  str,
    threshold:  float,
) -> Image.Image:
    """
    Build a contact sheet for one directory.

    results: list of (filename, crop_rgb, grey_ncc, binary_ncc)
    """
    font       = _try_load_font(11)
    n          = len(results)
    n_cols     = SHEET_COLS
    n_rows     = (n + n_cols - 1) // n_cols

    header_h   = 20
    total_w    = n_cols * CELL_W + (n_cols - 1) * CELL_GAP
    total_h    = header_h + n_rows * CELL_H + (n_rows - 1) * CELL_GAP

    sheet = Image.new("RGB", (total_w, total_h), BG_COLOUR)
    draw  = ImageDraw.Draw(sheet)

    # Header: directory label
    draw.text((4, 4), dir_label, fill=HEADER_COLOUR, font=font)

    for i, (fname, crop_rgb, g_ncc, b_ncc) in enumerate(results):
        col = i % n_cols
        row = i // n_cols

        cx = col * (CELL_W + CELL_GAP)
        cy = header_h + row * (CELL_H + CELL_GAP)

        # Cell border colour based on threshold
        border_col = PASS_BORDER if g_ncc >= threshold else (FAIL_BORDER if g_ncc<= threshold - 0.2 else MID_BORDER)
        draw.rectangle(
            [cx, cy, cx + CELL_W - 1, cy + CELL_H - 1],
            outline=border_col, width=BORDER_W
        )

        # Cell background
        inner_x0 = cx + BORDER_W
        inner_y0 = cy + BORDER_W
        inner_x1 = cx + CELL_W - BORDER_W - 1
        inner_y1 = cy + CELL_H - BORDER_W - 1
        draw.rectangle([inner_x0, inner_y0, inner_x1, inner_y1], fill=CELL_BG)

        # Filename label
        label_y = inner_y0 + CELL_PAD
        short   = os.path.basename(fname)
        draw.text((inner_x0 + CELL_PAD, label_y), short,
                  fill=LABEL_COLOUR, font=font)

        # Crop image
        crop_img = Image.fromarray(crop_rgb.astype(np.uint8))
        crop_x   = inner_x0 + CELL_PAD
        crop_y   = label_y + LABEL_H
        sheet.paste(crop_img, (crop_x, crop_y))

        # Score line
        score_text = f"grey={g_ncc:+.3f}  bin={b_ncc:+.3f}"
        score_y    = crop_y + CROP_H
        draw.text((inner_x0 + CELL_PAD, score_y), score_text,
                  fill=SCORE_COLOUR, font=font)

    return sheet


# ---------------------------------------------------------------------------
# Directory traversal
# ---------------------------------------------------------------------------

def collect_frames(root: str) -> dict[str, list[str]]:
    """
    Recursively find all PNG files under root.
    Returns dict: directory_path -> sorted list of PNG file paths.
    """
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
        description="Analyse Hunt Report NCC scores across frame directories.")
    parser.add_argument("--template", required=True,
                        help="Template crop PNG (387x61, from MediaProjection frame)")
    parser.add_argument("--frames",   required=True,
                        help="Root directory to search recursively for PNGs")
    parser.add_argument("--out",      default="hunt_report_analysis",
                        help="Output directory for contact sheets and CSV")
    parser.add_argument("--x1",  type=int, default=DEFAULT_X1)
    parser.add_argument("--y1",  type=int, default=DEFAULT_Y1)
    parser.add_argument("--x2",  type=int, default=DEFAULT_X2)
    parser.add_argument("--y2",  type=int, default=DEFAULT_Y2)
    parser.add_argument("--threshold", type=float, default=0.8,
                        help="Display threshold for green/red cell borders (default 0.5)")
    args = parser.parse_args()

    # ------------------------------------------------------------------
    # Load and prepare template
    # ------------------------------------------------------------------
    try:
        tmpl_img  = Image.open(args.template).convert("RGB")
        tmpl_arr  = np.array(tmpl_img)
    except Exception as e:
        print(f"ERROR: could not load template: {e}")
        sys.exit(1)

    expected_w = args.x2 - args.x1
    expected_h = args.y2 - args.y1

    if tmpl_arr.shape[:2] != (expected_h, expected_w):
        print(f"WARNING: template is {tmpl_arr.shape[1]}x{tmpl_arr.shape[0]}, "
              f"expected {expected_w}x{expected_h}. "
              f"Scores may be unreliable if dimensions do not match the crop region.")

    tmpl_grey = tmpl_arr.mean(axis=2)
    tmpl_bin  = _otsu_binarise(tmpl_grey)

    print(f"Template loaded: {args.template}  "
          f"({tmpl_arr.shape[1]}x{tmpl_arr.shape[0]})")
    print(f"Crop region: ({args.x1},{args.y1}) -> ({args.x2},{args.y2})  "
          f"{expected_w}x{expected_h}px")
    print(f"Display threshold: {args.threshold}")

    # ------------------------------------------------------------------
    # Collect frames
    # ------------------------------------------------------------------
    by_dir = collect_frames(args.frames)
    total_frames = sum(len(v) for v in by_dir.values())

    if total_frames == 0:
        print(f"No PNG files found under {args.frames}")
        sys.exit(1)

    print(f"\nFound {total_frames} PNG(s) across {len(by_dir)} director(ies)")

    # ------------------------------------------------------------------
    # Output directory
    # ------------------------------------------------------------------
    os.makedirs(args.out, exist_ok=True)

    # ------------------------------------------------------------------
    # Process
    # ------------------------------------------------------------------
    csv_rows = []
    all_grey_scores = []

    for dir_path, frame_paths in by_dir.items():
        rel_dir  = os.path.relpath(dir_path, args.frames)
        dir_label = rel_dir if rel_dir != "." else os.path.basename(dir_path)

        print(f"\n{dir_label}  ({len(frame_paths)} frame(s))")

        results = []
        for fpath in frame_paths:
            out = process_frame(
                fpath, tmpl_grey, tmpl_bin,
                args.x1, args.y1, args.x2, args.y2
            )
            if out is None:
                continue
            crop_rgb, g_ncc, b_ncc = out
            fname = os.path.basename(fpath)
            results.append((fname, crop_rgb, g_ncc, b_ncc))
            csv_rows.append({
                "path":       fpath,
                "directory":  dir_label,
                "filename":   fname,
                "grey_ncc":   f"{g_ncc:.4f}",
                "binary_ncc": f"{b_ncc:.4f}",
            })
            all_grey_scores.append(g_ncc)

        if not results:
            print("  No processable frames.")
            continue

        # Per-directory stats
        grey_scores = [r[2] for r in results]
        print(f"  grey_ncc:   min={min(grey_scores):+.3f}  "
              f"max={max(grey_scores):+.3f}  "
              f"mean={sum(grey_scores)/len(grey_scores):+.3f}")
        above = sum(1 for s in grey_scores if s >= args.threshold)
        print(f"  >= threshold ({args.threshold}): {above}/{len(grey_scores)}")

        # Contact sheet
        sheet     = build_contact_sheet(results, dir_label, args.threshold)
        safe_name = dir_label.replace(os.sep, "_").replace("/", "_").replace("\\", "_")
        sheet_path = os.path.join(args.out, f"{safe_name}_sheet.png")
        sheet.save(sheet_path)
        print(f"  Contact sheet -> {sheet_path}")

    # ------------------------------------------------------------------
    # CSV
    # ------------------------------------------------------------------
    csv_path = os.path.join(args.out, "scores.csv")
    with open(csv_path, "w", newline="") as f:
        writer = csv.DictWriter(
            f, fieldnames=["path", "directory", "filename", "grey_ncc", "binary_ncc"])
        writer.writeheader()
        writer.writerows(csv_rows)
    print(f"\nCSV -> {csv_path}")

    # ------------------------------------------------------------------
    # Overall summary
    # ------------------------------------------------------------------
    if all_grey_scores:
        print(f"\n{'='*60}")
        print(f"Overall grey_ncc across {len(all_grey_scores)} frame(s):")
        print(f"  min={min(all_grey_scores):+.4f}  "
              f"max={max(all_grey_scores):+.4f}  "
              f"mean={sum(all_grey_scores)/len(all_grey_scores):+.4f}")

        # Histogram of scores in 0.1 buckets from -1 to +1
        buckets = defaultdict(int)
        for s in all_grey_scores:
            bucket = round(int(s * 10) / 10, 1)
            buckets[bucket] += 1
        print("\n  Score distribution (grey_ncc, bucket width 0.1):")
        for bucket in sorted(buckets):
            bar = "█" * buckets[bucket]
            print(f"  {bucket:+.1f}  {bar}  ({buckets[bucket]})")
        print(f"{'='*60}")


if __name__ == "__main__":
    main()
