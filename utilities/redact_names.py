#!/usr/bin/env python3
"""
redact_names.py — Remove identifying text (player names, handles) from a
screenshot using content-aware inpainting, so the fill blends into the
surrounding background instead of leaving an obvious solid box.

Usage:
    python redact_names.py <input_image> <x1> <y1> <x2> <y2> [<x1> <y1> <x2> <y2> ...] [--output <output_path>]

Each x1/y1/x2/y2 group is a pixel-coordinate box (top-left, bottom-right) to
remove. Pass multiple groups to redact several regions in one pass.

Examples:
    python redact_names.py frame_0003.png 20 695 230 755
    python redact_names.py frame_0003.png 20 695 230 755 275 695 555 755 --output frame_0003_redacted.png
"""

import argparse
import sys
from pathlib import Path

try:
    import cv2
    import numpy as np
except ImportError:
    sys.exit("opencv-python-headless and numpy are required. Install with:\n"
              "    pip install opencv-python-headless numpy")


def parse_args():
    parser = argparse.ArgumentParser(
        description="Remove identifying text from an image via content-aware inpainting.",
    )
    parser.add_argument("input_image", type=Path, help="Source image file (PNG or JPG)")
    parser.add_argument(
        "coords", type=int, nargs="+",
        help="One or more x1 y1 x2 y2 groups (must be a multiple of 4 integers)",
    )
    parser.add_argument(
        "--output", "-o",
        type=Path,
        default=None,
        help="Output file path (default: overwrite the input file)",
    )
    parser.add_argument(
        "--radius", type=int, default=7,
        help="Inpaint radius passed to cv2.inpaint (default: 7)",
    )
    return parser.parse_args()


def boxes_from_coords(coords):
    if len(coords) % 4 != 0:
        sys.exit(f"Error: coords must come in groups of 4 (x1 y1 x2 y2) — got {len(coords)} values.")
    return [tuple(coords[i:i + 4]) for i in range(0, len(coords), 4)]


def redact(input_path: Path, boxes, output_path: Path, radius: int):
    img = cv2.imread(str(input_path), cv2.IMREAD_COLOR)
    if img is None:
        sys.exit(f"Error: could not read image — {input_path}")

    h, w = img.shape[:2]
    mask = np.zeros((h, w), dtype=np.uint8)
    for (x1, y1, x2, y2) in boxes:
        if not (0 <= x1 < x2 <= w and 0 <= y1 < y2 <= h):
            sys.exit(f"Error: box ({x1},{y1})-({x2},{y2}) is out of bounds for {w}x{h} image.")
        mask[y1:y2, x1:x2] = 255

    result = cv2.inpaint(img, mask, inpaintRadius=radius, flags=cv2.INPAINT_TELEA)
    cv2.imwrite(str(output_path), result)
    print(f"Redacted {len(boxes)} region(s) in {input_path.name} -> {output_path}")


def main():
    args = parse_args()

    if not args.input_image.exists():
        sys.exit(f"Error: file not found — {args.input_image}")

    boxes = boxes_from_coords(args.coords)
    output_path = args.output or args.input_image
    redact(args.input_image, boxes, output_path, args.radius)


if __name__ == "__main__":
    main()