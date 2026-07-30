# MHN Tracker

Android app that watches your own Monster Hunter Now gameplay screen and automatically logs hunt results (monster, stars, materials) for personal analysis.

*Unofficial fan project. Not affiliated with, endorsed by, or sponsored by Scopely, Niantic, or Capcom.*

## How it works

- Runs as a background screen-capture service with a small floating status bubble
- Detects game states (map, fight, hunt report) frame-by-frame using on-device OCR (ML Kit) and lightweight image matching — no network access, no automation, no interaction with the game client
- Extracts structured hunt data (monster name, star rating, material drops) for later analysis in a companion tool

## Status

Early / personal-use stage — detection pipeline for fights and hunt reports is functional; not yet packaged for general use.

Screen detection uses fixed pixel coordinates calibrated for a Pixel 7 screen and has not been tested on other devices/resolutions.

## Stack

Kotlin, Android MediaProjection API, ML Kit Text Recognition, JUnit/Robolectric for detector tests.