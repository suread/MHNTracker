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
    python scroll_shift_prototype.py --root path/to/scan/root --out results.csv --detail
"""

import argparse
import csv
import re
import time
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np

Y_TOP = 600
HEIGHT = 300

TEMPLATE_LEFT = 150
TEMPLATE_RIGHT = 200

# appears when scrolling is complete and space is made for Confirm button and associated UI Chrome (last frame added to
# composite only), obscures some of reward output.
BELOW_PREMIUM_ITEMS_PILL = 300

# Upper bound on plausible scroll distance between two *consecutive saved*
# frames. Generous starting guess to allow for dropped frames - tune once
# real results come back.
MAX_SHIFT = 1200

# gray band across top of every frame - occupies area of status bar on phone
STATUS_AREA_HEIGHT = 140

# Contact sheet layout - composites are scaled down for viewing/processing/file-size
# reasons only (zoom handles readability); rows are capped at 10 so a full run fits in
# one image, scrollable vertically rather than spread horizontally.
CONTACT_SHEET_SCALE = 0.25
CONTACT_SHEET_MAX_COLS = 10
# Label sits above its own composite, at the bottom of this strip, so it stays close
# regardless of how tall the composite is. The space above the text (unusually long
# composites can otherwise butt straight up against the row above) is the gap between
# a row's tallest composite and the label of the row below it.
CONTACT_SHEET_LABEL_HEIGHT = 60
CONTACT_SHEET_BG_COLOR = (255, 255, 255)


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

def load_ground_truth(session_dir: Path) -> dict[tuple[int, int], int]:
    """
    Load manually-measured ground truth from ground_truth.csv in session_dir, if present.

    Expected columns: frame_a, frame_b, y_a, y_b - the y-pixel position of the same
    landmark as measured by eye in each frame, not a pre-computed shift. y_a - y_b
    gives the shift stored against that (frame_a, frame_b) key. Returns an empty
    dict if the file doesn't exist, which is the normal case for most sessions.
    """
    ground_truth: dict[tuple[int, int], int] = {}
    path = session_dir / "ground_truth.csv"
    if not path.exists():
        return ground_truth
    with open(path) as file:
        reader = csv.DictReader(file)
        for row in reader:
            ground_truth[(int(row['frame_a']),int(row['frame_b']))] = int(row['y_a']) - int(row['y_b'])
    return ground_truth

def join_images(composite: np.ndarray, new_img: np.ndarray, scroll_distance: int) -> np.ndarray:
    composite = np.concatenate((composite[:len(composite) + scroll_distance - len(new_img)],new_img), axis=0)
    return composite


def find_frame_sets(root_dir: Path) -> list[Path]:
    """Recursively find every directory under root_dir containing a frame_*.png set, at any depth."""
    session_dirs = {path.parent for path in root_dir.rglob("frame_*.png")}
    return sorted(session_dirs)


def build_contact_sheet(composites: dict[Path, np.ndarray]) -> np.ndarray:
    """
    Arrange scaled-down composite thumbnails into a single grid image, each labelled
    with its source session directory, for quick visual comparison across a run.

    Assumes every composite shares the same width (true as long as all source frames
    come from the same device/resolution) - only height varies row to row.
    """
    thumbnails = []
    for session_dir in sorted(composites):
        composite = composites[session_dir]
        height, width = composite.shape[:2]
        thumb = cv2.resize(
            composite,
            (int(width * CONTACT_SHEET_SCALE), int(height * CONTACT_SHEET_SCALE)),
            interpolation=cv2.INTER_AREA,
        )
        thumbnails.append((session_dir.name, thumb))

    thumb_width = thumbnails[0][1].shape[1]

    rows = []
    for row_start in range(0, len(thumbnails), CONTACT_SHEET_MAX_COLS):
        row = thumbnails[row_start:row_start + CONTACT_SHEET_MAX_COLS]
        row_thumb_height = max(thumb.shape[0] for _, thumb in row)
        cell_height = row_thumb_height + CONTACT_SHEET_LABEL_HEIGHT

        cells = []
        for label, thumb in row:
            cell = np.full((cell_height, thumb_width, 3), CONTACT_SHEET_BG_COLOR, dtype=np.uint8)
            cv2.putText(cell, label, (4, CONTACT_SHEET_LABEL_HEIGHT - 8),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.4, (0, 0, 0), 1, cv2.LINE_AA)
            cell[CONTACT_SHEET_LABEL_HEIGHT:CONTACT_SHEET_LABEL_HEIGHT + thumb.shape[0]] = thumb
            cells.append(cell)

        blank_cell = np.full((cell_height, thumb_width, 3), CONTACT_SHEET_BG_COLOR, dtype=np.uint8)
        while len(cells) < CONTACT_SHEET_MAX_COLS:
            cells.append(blank_cell)

        rows.append(np.concatenate(cells, axis=1))

    return np.concatenate(rows, axis=0)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True,
                         help="Root directory to scan recursively for frame_NNNN.png sets")
    parser.add_argument("--out", default="scroll_shift_results.csv",
                         help="CSV output path (only written with --detail)")
    parser.add_argument("--detail", action="store_true",
                         help="Write an individual composite + CSV rows per session directory; "
                              "omit for a bulk run that only builds composites in memory")
    args = parser.parse_args()

    root_dir = Path(args.root)
    session_dirs = find_frame_sets(root_dir)
    print(f"Found {len(session_dirs)} frame set(s) under {root_dir}")

    rows = []
    composites: dict[Path, np.ndarray] = {}

    for session_dir in session_dirs:
        frames = load_frames(session_dir)
        if not frames:
            print(f"  WARNING: no loadable frames in {session_dir}, skipping")
            continue

        ground_truth = load_ground_truth(session_dir) if args.detail else {}

        composite = frames[0][1][STATUS_AREA_HEIGHT:]
        pending_image = frames[0][1][STATUS_AREA_HEIGHT:]
        pending_scroll = 0

        for (idx_a, frame_a), (idx_b, frame_b) in zip(frames, frames[1:]):
            t0 = time.perf_counter()
            result = measure_shift(frame_a, frame_b, TEMPLATE_LEFT, TEMPLATE_RIGHT, Y_TOP, HEIGHT, MAX_SHIFT)
            elapsed_ms = (time.perf_counter() - t0) * 1000
            result.elapsed_ms = elapsed_ms

            if result.offset_px == 0:
                # TODO look at timing of crop when coding in Kotlin - we only need to crop if this image is added to the composite
                pending_image = frame_b[STATUS_AREA_HEIGHT:]
            else:
                composite = join_images(composite, pending_image, pending_scroll)
                pending_image = frame_b[STATUS_AREA_HEIGHT:]
                pending_scroll = result.offset_px

            if args.detail:
                truth = ground_truth.get((idx_a, idx_b))
                rows.append({
                    "session_dir": session_dir.name,
                    "frame_a": idx_a,
                    "frame_b": idx_b,
                    "offset_px": result.offset_px,
                    "confidence": f"{result.confidence:.4f}",
                    "elapsed_ms": f"{result.elapsed_ms:.2f}",
                    "ground_truth_px": truth if truth is not None else "",
                })
                truth_note = f"  [truth={truth}]" if truth is not None else ""
                print(f"[{session_dir.name}] frame {idx_a:04d}->{idx_b:04d}: "
                      f"offset={result.offset_px}px conf={result.confidence:.4f} "
                      f"({result.elapsed_ms:.2f}ms){truth_note}")

        composite = join_images(composite, pending_image, pending_scroll)
        composites[session_dir] = composite

        if args.detail:
            cv2.imwrite(f"composite_{session_dir.name}.png", composite)

    if args.detail and rows:
        with open(args.out, "w", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
            writer.writeheader()
            writer.writerows(rows)
        print(f"\nCSV -> {args.out}")

    if composites:
        cv2.imwrite("contact_sheet.png", build_contact_sheet(composites))
        print("Contact sheet -> contact_sheet.png")


if __name__ == "__main__":
    main()
