import cv2
import os

# VIDEO_PATH = r"../videos/screen-20260502-060424-1777698198162.mp4"
VIDEO_PATH = r"C:\Development\MHNTracker\app\src\test\resources\frames\map_detection\positive"
VIDEO_PATH = r"../app/src/test/resources/frames/map_detection/positive/screen-20260720-125337-1784548378360.mp4"
VIDEO_PATH = r"C:\Development\MHNTracker\app\src\test\resources\frames\map_detection\positive\screen-20260720-125337-1784548378360.mp4"
FRAME_PATH = r"C:/Development/MHNTracker/app/src/test/resources/frames/map_detection/positive/frames"


# C:\Development\MHNTracker\utilities\extract_frames.py
SAMPLE_INTERVAL_SECONDS = 0.5


def load_video(path):
    cap = cv2.VideoCapture(path)
    if not cap.isOpened():
        print(f"Error: Could not open video {path}")
        return None

    fps = cap.get(cv2.CAP_PROP_FPS)
    total_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    duration = total_frames / fps

    print("Video loaded successfully!")
    print(f"Resolution: {width}x{height}")
    print(f"FPS: {fps:.1f}")
    print(f"Total frames: {total_frames}")
    print(f"Duration: {duration:.1f} seconds")

    return cap, fps, width, height


def extract_frames(cap, fps, sample_interval_seconds=0.5):
    sample_interval = int(fps * sample_interval_seconds)
    os.makedirs(FRAME_PATH, exist_ok=True)

    frames = []
    frame_count = 0
    saved_count = 0

    while True:
        ret, frame = cap.read()
        if not ret:
            break

        if frame_count % sample_interval == 0:
            output_path = FRAME_PATH + f"/frame_{saved_count:04d}.png"
            cv2.imwrite(output_path, frame)
            frames.append(output_path)
            saved_count += 1

        frame_count += 1

    print(f"Extracted {saved_count} frames")
    return frames


cap, fps, width, height = load_video(VIDEO_PATH)
if cap:
    frames = extract_frames(cap, fps, SAMPLE_INTERVAL_SECONDS)
    cap.release()