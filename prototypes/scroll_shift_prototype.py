"""
scroll_shift_prototype.py

Throwaway diagnostic for the hunt-report frame-stitching investigation (see
docs/local/project-notes.md). Compares candidate horizontal regions for
vertical-shift detection between consecutive hunt-report frames, using
cv2.matchTemplate.

This is a prototype, not a utility - it exists to answer "which region is a
reliable place to measure scroll from, and at what processing cost", not to
be kept around afterwards.

Usage:
    python scroll_shift_prototype.py --session path/to/session_dir --out results.csv
"""

import argparse
import csv
import re
import time
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np


# ---------------------------------------------------------------------------
# Candidate regions under test - (label, x_left, x_right).
# y_top/height are shared across regions for this round of comparison.
# ---------------------------------------------------------------------------

REGIONS = [
    ("col1_150_200", 150, 200),
    ("col2_450_500", 450, 500),
]

Y_TOP = 600
HEIGHT = 300

# Upper bound on plausible scroll distance between two *consecutive saved*
# frames. Generous starting guess to allow for dropped frames - tune once
# real results come back.
MAX_SHIFT = 1200

# ---------------------------------------------------------------------------
# Manually-verified ground truth: fill in as frame sequences are checked by
# eye. Keys are (frame_index_a, frame_index_b); value is the known scroll
# distance in px. Absent entries just print/report with no comparison.
# ---------------------------------------------------------------------------

@dataclass
class ShiftResult:
    offset_px: int      # estimated vertical shift, frame_a -> frame_b (positive = scrolled up)
    confidence: float   # matchTemplate score at the chosen offset
    elapsed_ms: float = 0.0   # overwritten by main() - ignore this in your implementation


def measure_shift(
    frame_a: np.ndarray,
    frame_b: np.ndarray,
    x_left: int, x_right: int,
    y_top: int, height: int,
    max_shift: int,
) -> ShiftResult:
    """
    Estimate the vertical scroll shift between frame_a (older) and frame_b
    (newer), using the column [x_left:x_right] as the comparison region.

    Crop a template of the given height from frame_a at y_top, then search
    for it in frame_b within a taller region starting further up - content
    that has scrolled up appears higher (smaller y) in frame_b than it was
    in frame_a.

    TODO(suread): implement using cv2.matchTemplate + cv2.minMaxLoc.
    offset_px should be positive when content scrolled up between the two
    frames (i.e. frame_b's matching content is higher up than y_top).
    """
    template_crop = frame_a[y_top:y_top+height, x_left:x_right]
    search_crop = frame_b[max(0, y_top - max_shift):y_top+height, x_left:x_right]

    # other comparison methods available: 'cv2.TM_CCOEFF', 'cv2.TM_CCOEFF_NORMED', 'cv2.TM_CCORR','cv2.TM_CCORR_NORMED', 'cv2.TM_SQDIFF', 'cv2.TM_SQDIFF_NORMED'
    res = cv2.matchTemplate(search_crop, template_crop, cv2.TM_CCOEFF_NORMED)
    min_val, max_val, min_loc, max_loc = cv2.minMaxLoc(res)
    # top left is min_loc for TM_SQDIFF or TM_SQDIFF_NORMED, otherwise max_loc - remember this if tweak matching algorithm

    if not max_loc[0] == 0:
        raise RuntimeError("template not found at left side of search crop")

    search_start = max(0, y_top - max_shift)
    return ShiftResult((y_top - search_start) - max_loc[1], max_val)


def load_frames(session_dir: Path) -> list[tuple[int, np.ndarray]]:
    """Load frame_NNNN.png files from a session dir, sorted by index."""
    pattern = re.compile(r"frame_(\d+)\.png$")
    frames = []
    for path in sorted(session_dir.glob("frame_*.png")):
        m = pattern.search(path.name)
        if not m:
            continue
        index = int(m.group(1))
        img = cv2.imread(str(path), cv2.IMREAD_COLOR)
        if img is None:
            print(f"  WARNING: could not load {path}")
            continue
        frames.append((index, img))
    return frames

def load_ground_truth(session_dir: Path) -> list[tuple[int, np.ndarray]]:
    """Load measured scroll values from ground_truth.csv in session dir."""
    ground_truth: dict[tuple[int, int], int] = {
        # (0, 1): 0,
    }
    with open(session_dir / "ground_truth.csv") as file:
        reader = csv.DictReader(file)
        for row in reader:
            ground_truth[(int(row['frame_a']),int(row['frame_b']))] = int(row['y_a']) - int(row['y_b'])
    return ground_truth


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--session", required=True,
                         help="Session directory containing frame_NNNN.png files")
    parser.add_argument("--out", default="scroll_shift_results.csv",
                         help="CSV output path")
    args = parser.parse_args()

    session_dir = Path(args.session)
    frames = load_frames(session_dir)
    ground_truth = load_ground_truth(session_dir)

    print(f"Loaded {len(frames)} frames from {session_dir}")

    rows = []
    for label, x_left, x_right in REGIONS:
        for (idx_a, frame_a), (idx_b, frame_b) in zip(frames, frames[1:]):
            t0 = time.perf_counter()
            result = measure_shift(frame_a, frame_b, x_left, x_right, Y_TOP, HEIGHT, MAX_SHIFT)
            elapsed_ms = (time.perf_counter() - t0) * 1000
            result.elapsed_ms = elapsed_ms

            truth = ground_truth.get((idx_a, idx_b))
            rows.append({
                "region": label,
                "frame_a": idx_a,
                "frame_b": idx_b,
                "offset_px": result.offset_px,
                "confidence": f"{result.confidence:.4f}",
                "elapsed_ms": f"{result.elapsed_ms:.2f}",
                "ground_truth_px": truth if truth is not None else "",
            })
            truth_note = f"  [truth={truth}]" if truth is not None else ""
            print(f"[{label}] frame {idx_a:04d}->{idx_b:04d}: "
                  f"offset={result.offset_px}px conf={result.confidence:.4f} "
                  f"({result.elapsed_ms:.2f}ms){truth_note}")

    with open(args.out, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)
    print(f"\nCSV -> {args.out}")


if __name__ == "__main__":
    main()
