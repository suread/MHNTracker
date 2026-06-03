#!/usr/bin/env python3
"""
crop_image.py — Crop a PNG or JPG image using top-left and bottom-right coordinates.

Usage:
    python crop_image.py <input_image> <x1> <y1> <x2> <y2> [--output <output_path>]

Examples:
    python crop_image.py photo.jpg 100 50 400 300
    python crop_image.py photo.png 0 0 200 200 --output thumbnail.png
"""

import argparse
import sys
from pathlib import Path

try:
    from PIL import Image
except ImportError:
    sys.exit("Pillow is required. Install it with:  pip install Pillow")


SUPPORTED_EXTENSIONS = {".png", ".jpg", ".jpeg"}


def parse_args():
    parser = argparse.ArgumentParser(
        description="Crop an image using top-left and bottom-right pixel coordinates.",
    )
    parser.add_argument("input_image", type=Path, help="Source image file (PNG or JPG)")
    parser.add_argument("x1", type=int, help="Top-left X coordinate")
    parser.add_argument("y1", type=int, help="Top-left Y coordinate")
    parser.add_argument("x2", type=int, help="Bottom-right X coordinate")
    parser.add_argument("y2", type=int, help="Bottom-right Y coordinate")
    parser.add_argument(
        "--output", "-o",
        type=Path,
        default=None,
        help="Output file path (default: <input_stem>_cropped.<ext>)",
    )
    return parser.parse_args()


def validate_inputs(image_path: Path, x1: int, y1: int, x2: int, y2: int, img: Image.Image):
    errors = []

    if image_path.suffix.lower() not in SUPPORTED_EXTENSIONS:
        errors.append(f"Unsupported file type '{image_path.suffix}'. Use PNG or JPG.")

    width, height = img.size

    if x1 >= x2:
        errors.append(f"x1 ({x1}) must be less than x2 ({x2}).")
    if y1 >= y2:
        errors.append(f"y1 ({y1}) must be less than y2 ({y2}).")
    if x1 < 0 or y1 < 0:
        errors.append(f"Coordinates must be non-negative (got x1={x1}, y1={y1}).")
    if x2 > width or y2 > height:
        errors.append(
            f"Crop region ({x1},{y1})->({x2},{y2}) exceeds image size {width}x{height}."
        )

    if errors:
        for err in errors:
            print(f"Error: {err}", file=sys.stderr)
        sys.exit(1)


def default_output_path(input_path: Path) -> Path:
    return input_path.with_name(f"{input_path.stem}_cropped{input_path.suffix}")


def crop(input_path: Path, x1: int, y1: int, x2: int, y2: int, output_path: Path):
    with Image.open(input_path) as img:
        print(f"Opened: {input_path}  ({img.width}x{img.height}, mode={img.mode})")
        validate_inputs(input_path, x1, y1, x2, y2, img)

        cropped = img.crop((x1, y1, x2, y2))
        cropped.save(output_path)

    print(f"Cropped region: ({x1},{y1}) -> ({x2},{y2})  [{x2-x1}x{y2-y1} px]")
    print(f"Saved to: {output_path}")


def main():
    args = parse_args()

    if not args.input_image.exists():
        sys.exit(f"Error: File not found -- {args.input_image}")

    output_path = args.output or default_output_path(args.input_image)
    crop(args.input_image, args.x1, args.y1, args.x2, args.y2, output_path)


if __name__ == "__main__":
    main()