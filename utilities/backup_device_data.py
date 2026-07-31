"""
backup_device_data.py

Pulls the same device data that build.gradle.kts backs up before an
instrumented test run (instrumented tests wipe the app's files), then
deletes it off the device so old data doesn't pile up or get re-pulled
next time.

Pulls (and clears):
    /sdcard/Android/data/com.readablesoftware.mhntracker/files/sessions
    /sdcard/Android/data/com.readablesoftware.mhntracker/files/hunt_report_trigger_log.log

Output lands in the same place and format Gradle uses, so files from either
source end up in the same run folder structure:
    <repo root>/image-backups/run-<timestamp>/sessions/...
    <repo root>/image-backups/run-<timestamp>/hunt_report_trigger_log.log

Requirements: adb on PATH, phone connected (USB or wireless debugging) and
authorised. No other setup needed.

Usage:
    python backup_device_data.py
"""

import subprocess
import sys
import time
from pathlib import Path

PACKAGE = "com.readablesoftware.mhntracker"
DEVICE_SESSIONS_DIR = f"/sdcard/Android/data/{PACKAGE}/files/sessions"
DEVICE_TRIGGER_LOG = f"/sdcard/Android/data/{PACKAGE}/files/hunt_report_trigger_log.log"

REPO_ROOT = Path(__file__).resolve().parent.parent
BACKUP_ROOT = REPO_ROOT / "image-backups"


def require_device():
    try:
        result = subprocess.run(
            ["adb", "devices"], capture_output=True, text=True, check=True
        )
    except FileNotFoundError:
        print("adb not found on PATH. Install Android platform-tools and try again.")
        sys.exit(1)
    except subprocess.CalledProcessError as e:
        print(f"adb devices failed:\n{e.stderr}")
        sys.exit(1)

    device_lines = [
        line for line in result.stdout.splitlines()[1:] if line.strip()
    ]
    ready = [line for line in device_lines if line.split()[1] == "device"]
    if not ready:
        print(
            "No adb device found. Connect your phone via USB or wireless "
            "debugging (and accept the authorisation prompt on the phone) "
            "then try again."
        )
        sys.exit(1)

    print(f"Found device: {ready[0].split()[0]}")


def pull_and_clear(remote_path, dest_dir, label):
    print(f"Pulling {label}...")
    pulled = subprocess.run(["adb", "pull", remote_path, str(dest_dir)])
    if pulled.returncode != 0:
        # This data may simply not exist on the device yet (e.g. first run),
        # which isn't an error -- just means there's nothing to clear either.
        print(f"  (nothing pulled for {label} -- may not exist on device yet)")
        return

    print(f"Clearing {label} off the device...")
    cleared = subprocess.run(["adb", "shell", "rm", "-rf", remote_path])
    if cleared.returncode != 0:
        print(f"  Warning: failed to delete {remote_path} off the device")


def main():
    require_device()

    timestamp = str(int(time.time() * 1000))
    dest_dir = BACKUP_ROOT / f"run-{timestamp}"
    dest_dir.mkdir(parents=True, exist_ok=True)

    pull_and_clear(DEVICE_SESSIONS_DIR, dest_dir, "session images")
    pull_and_clear(DEVICE_TRIGGER_LOG, dest_dir, "hunt report trigger log")

    print(f"Done. Backup at {dest_dir}")


if __name__ == "__main__":
    main()
