#!/usr/bin/env python3
"""
classify_map_frames.py — Interactive triage tool for the unreviewed
map_detection brute-force frames (app/src/test/resources/frames/map_detection).

The frames under negative/_No_surplus and negative/_Extra_frames were pulled
from footage assumed not to show the map screen, but that assumption was
never verified per-frame -- some bursts may actually contain a map screen.
negative/"map negative" and negative/"map positive" are the result of an
earlier, already-verified pass and are copied straight through, unreviewed.

Frames arrive in bursts (one subfolder per short recording clip) where the
map is almost always either visible for the whole burst or not visible at
all, so review happens in three tiers across two passes:

  Pass 1 -- Subfolder triage: for every burst, a handful of sample frames
     (every --stride frames, always including the last) are shown together.
     If they're all clearly one class, classify the whole burst in one
     keypress. If they look mixed, flag it and move straight on to the next
     burst -- flagged bursts are queued for pass 2, not reviewed immediately.
     Tip: check the monster name text on the first and last sample frame
     match -- if they don't, the burst isn't one continuous recording and
     a single bulk decision may not hold for all of it.
  Pass 2 -- Once every burst has been through pass 1, whatever got flagged
     mixed is reviewed in two further tiers:
       a) Group review: the burst's frames are shown --group-size at a
          time (all of them, not sampled). A clearly uniform group is
          classified in one keypress, same as burst triage.
       b) Single-frame review: only for groups that don't look uniform --
          steps through just those few frames one at a time. Also the only
          place a frame can be marked "compass obscured" (see below), so
          any group containing one must be sent here rather than bulk-
          classified, even if the rest of the group looks uniform.

Classified frames are COPIED (originals are left untouched) into:
    frames/map_detection/brute_force/positive/
    frames/map_detection/brute_force/negative/
    frames/map_detection/brute_force/map_compass_obscured/
with the source burst folded into the filename so provenance is traceable
and nothing collides. The third bucket is for frames that do show the map
but with the compass not visible -- the current detector is expected to
(correctly) call these negative, so they're kept apart from clean
positive/negative in case that changes if detection logic changes later.
It's a single-frame-only call, too situational to bulk-flag a whole burst
or group as this.

map_detection/positive and the two top-level files in map_detection/negative
already back the routinely-run detector tests and are not touched by this
script.

Progress is saved after every decision to classification_progress.json in
the output folder, so a session can be closed and resumed at any time --
including mid-burst, and including part-way through pass 1 (resuming picks
up in pass 1 first, then pass 2, same as a fresh run).

Usage:
    python classify_map_frames.py [--stride 25] [--group-size 5]

Keybindings (pass 1 -- subfolder triage):
    N   whole burst is Negative      P   whole burst is Positive
    M   burst looks Mixed -> queue it for group review in pass 2
    U   undo the last action         Q   save progress and quit

Keybindings (pass 2a -- group review, --group-size frames at a time):
    N   this group is Negative       P   this group is Positive
    M   group looks Mixed (or contains anything that needs C below) ->
        review this group's frames one at a time
    T   back to triage for this burst (discards this burst's frame decisions)
    U   undo the last action         Q   save progress and quit

Keybindings (pass 2b -- single-frame review, within a flagged group):
    N   this frame is Negative       P   this frame is Positive
    C   map screen, compass obscured (expected to fail current detection)
    SPACE   same as the previous frame's decision
    T   back to triage for this burst (discards this burst's frame decisions)
    U   undo the last decision       Q   save progress and quit
"""

import argparse
import json
import shutil
import sys
from pathlib import Path

try:
    from PIL import Image, ImageTk
except ImportError:
    sys.exit("Pillow is required. Install it with:  pip install Pillow")

try:
    import tkinter as tk
except ImportError:
    sys.exit("tkinter is required (usually bundled with Python).")


REPO_ROOT = Path(__file__).resolve().parent.parent
MAP_DETECTION_ROOT = REPO_ROOT / "app/src/test/resources/frames/map_detection"
NEGATIVE_ROOT = MAP_DETECTION_ROOT / "negative"
OUTPUT_ROOT = MAP_DETECTION_ROOT / "brute_force"
PROGRESS_FILE = OUTPUT_ROOT / "classification_progress.json"

SOURCE_SETS = ["_No_surplus", "_Extra_frames"]  # need per-frame review
PRECLASSIFIED = {"map negative": "negative", "map positive": "positive"}  # already verified

# Map screen with the compass not visible -- the current detector logic is
# expected to (correctly, for now) call this negative, so it's kept apart
# from clean positive/negative frames in case detection logic changes later
# and these become relevant. Single-frame review only: too situational to
# bulk-flag a whole burst or group as this.
MAP_COMPASS_OBSCURED = "map_compass_obscured"


def load_progress():
    if PROGRESS_FILE.exists():
        with open(PROGRESS_FILE, "r") as f:
            return json.load(f)
    return {"subdir_decisions": {}, "frame_decisions": {}, "preclassified_done": {}}


def save_progress(progress):
    OUTPUT_ROOT.mkdir(parents=True, exist_ok=True)
    with open(PROGRESS_FILE, "w") as f:
        json.dump(progress, f, indent=2)


def subdir_key(source_set, subdir_name):
    return f"{source_set}/{subdir_name}"


def frame_key(source_set, subdir_name, filename):
    return f"{source_set}/{subdir_name}/{filename}"


def list_subdirs(source_set):
    root = NEGATIVE_ROOT / source_set
    return sorted(d for d in root.iterdir() if d.is_dir())


def list_frames(subdir_path):
    return sorted(f for f in subdir_path.iterdir() if f.suffix.lower() == ".png")


def sample_frames(frames, stride):
    if not frames:
        return []
    indices = list(range(0, len(frames), stride))
    if indices[-1] != len(frames) - 1:
        indices.append(len(frames) - 1)
    return [frames[i] for i in indices]


def is_subdir_done(progress, source_set, subdir_name, all_frames):
    key = subdir_key(source_set, subdir_name)
    decision = progress["subdir_decisions"].get(key)
    if decision in ("negative", "positive"):
        return True
    if decision == "drilldown":
        return all(
            frame_key(source_set, subdir_name, f.name) in progress["frame_decisions"]
            for f in all_frames
        )
    return False


def output_dir_for(decision):
    return OUTPUT_ROOT / decision


def copy_frame(src_path, decision, output_name):
    output_dir_for(decision).mkdir(parents=True, exist_ok=True)
    dest = output_dir_for(decision) / output_name
    shutil.copy2(src_path, dest)
    return dest


class ClassifierApp:
    def __init__(self, progress, stride, group_size):
        self.progress = progress
        self.stride = stride
        self.group_size = group_size
        self.history = []  # undo stack: committed actions, most recent last

        self.root = tk.Tk()
        self.root.title("Map detection frame classifier")
        try:
            self.root.state("zoomed")  # start maximized so there's room to scale images up
        except tk.TclError:
            pass

        screen_height = self.root.winfo_screenheight()
        self.thumb_height = int(screen_height * 0.55)
        self.fullsize_max_height = int(screen_height * 0.85)

        self.status_label = tk.Label(self.root, font=("Segoe UI", 13), justify="left", anchor="w")
        self.status_label.pack(fill="x", padx=8, pady=(8, 0))

        self.hint_label = tk.Label(self.root, font=("Segoe UI", 11), fg="#555", justify="left", anchor="w")
        self.hint_label.pack(fill="x", padx=8, pady=(0, 8))

        self.image_frame = tk.Frame(self.root)
        self.image_frame.pack(padx=8, pady=8)

        self._thumb_photoimages = []  # keep references so Tk doesn't garbage-collect them
        self._full_photoimage = None

        self.root.bind("<Key>", self.on_key)

        self.mode = None  # "triage" | "group" | "drilldown" | "done"
        self.current_source_set = None
        self.current_subdir = None
        self.current_frames = []
        self.current_frame_index = 0
        self.current_group_start = 0
        self.current_group_end = 0
        self.last_decision = None

        self._copy_preclassified()

        self.triage_queue, self.drilldown_queue = self._build_queues()
        self.total_triage_subdirs = len(self.triage_queue)
        self.total_drilldown_subdirs = None  # set once pass 2 actually starts

        self.advance_to_next_subdir()

    # ------------------------------------------------------------------
    # Setup
    # ------------------------------------------------------------------

    def _copy_preclassified(self):
        for folder_name, decision in PRECLASSIFIED.items():
            if self.progress["preclassified_done"].get(folder_name):
                continue
            src_dir = NEGATIVE_ROOT / folder_name
            if not src_dir.exists():
                continue
            count = 0
            for f in sorted(src_dir.iterdir()):
                if f.suffix.lower() != ".png":
                    continue
                output_name = f"{folder_name.replace(' ', '_')}__{f.name}"
                copy_frame(f, decision, output_name)
                count += 1
            self.progress["preclassified_done"][folder_name] = True
            save_progress(self.progress)
            print(f"Copied {count} pre-classified frames from '{folder_name}' -> {decision}/")

    def _build_queues(self):
        """Split not-yet-done subdirs into pass 1 (never touched) and
        pass 2 (already flagged mixed, in this or an earlier session)."""
        triage_queue = []
        drilldown_queue = []
        for ss in SOURCE_SETS:
            for subdir in list_subdirs(ss):
                frames = list_frames(subdir)
                if not frames:
                    continue
                if is_subdir_done(self.progress, ss, subdir.name, frames):
                    continue
                key = subdir_key(ss, subdir.name)
                if self.progress["subdir_decisions"].get(key) == "drilldown":
                    drilldown_queue.append((ss, subdir))
                else:
                    triage_queue.append((ss, subdir))
        return triage_queue, drilldown_queue

    # ------------------------------------------------------------------
    # Navigation
    # ------------------------------------------------------------------

    def _set_current(self, source_set, subdir):
        self.current_source_set = source_set
        self.current_subdir = subdir
        self.current_frames = list_frames(subdir)

    def _requeue_displaced_current(self):
        """Put whatever was on screen (but not yet decided) back in the
        right queue before switching to something else, so it isn't
        silently dropped."""
        if self.current_subdir is None:
            return
        entry = (self.current_source_set, self.current_subdir)
        if self.mode in ("group", "drilldown"):
            self.drilldown_queue.insert(0, entry)
        else:
            self.triage_queue.insert(0, entry)

    def advance_to_next_subdir(self):
        if self.triage_queue:
            source_set, subdir = self.triage_queue.pop(0)
            self._set_current(source_set, subdir)
            self.show_triage()
            return

        if self.drilldown_queue:
            if self.total_drilldown_subdirs is None:
                self.total_drilldown_subdirs = len(self.drilldown_queue)
            source_set, subdir = self.drilldown_queue.pop(0)
            self._set_current(source_set, subdir)
            self._show_current_group()
            return

        # Nothing left in either queue -- whatever just finished to get us
        # here is fully committed already, not displaced/undecided, so it
        # must not be treated as re-queueable by a later undo.
        self.current_source_set = None
        self.current_subdir = None
        self.show_done()

    # ------------------------------------------------------------------
    # Pass 1 -- subfolder triage
    # ------------------------------------------------------------------

    def show_triage(self):
        self.mode = "triage"
        samples = sample_frames(self.current_frames, self.stride)
        self._render_thumbnails(samples)

        done = self.total_triage_subdirs - len(self.triage_queue) - 1
        self.status_label.config(text=(
            f"[{self.current_source_set}] {self.current_subdir.name}  "
            f"({len(self.current_frames)} frames, {len(samples)} sampled)   "
            f"—  pass 1: subfolder {done + 1}/{self.total_triage_subdirs}   "
            f"({len(self.drilldown_queue)} flagged mixed so far)"
        ))
        self.hint_label.config(text=(
            "N = whole burst Negative   P = whole burst Positive   "
            "M = Mixed, queue for pass 2   U = undo last action   Q = save & quit"
        ))

    def _render_thumbnails(self, samples):
        for child in self.image_frame.winfo_children():
            child.destroy()
        self._thumb_photoimages = []
        for path in samples:
            with Image.open(path) as img:
                img = img.copy()
            img.thumbnail((10000, self.thumb_height))
            photo = ImageTk.PhotoImage(img)
            self._thumb_photoimages.append(photo)
            col = tk.Frame(self.image_frame)
            col.pack(side="left", padx=4)
            tk.Label(col, image=photo).pack()
            tk.Label(col, text=path.name, font=("Segoe UI", 8)).pack()

    def commit_subdir_bulk(self, decision):
        source_set, subdir = self.current_source_set, self.current_subdir
        copied = []
        for f in self.current_frames:
            output_name = f"{source_set}__{subdir.name}__{f.name}"
            dest = copy_frame(f, decision, output_name)
            copied.append(dest)

        key = subdir_key(source_set, subdir.name)
        self.progress["subdir_decisions"][key] = decision
        save_progress(self.progress)

        self.history.append({
            "type": "subdir", "source_set": source_set, "subdir": subdir,
            "decision": decision, "files": copied,
        })
        print(f"Burst '{subdir.name}' ({len(copied)} frames) -> {decision}")
        self.advance_to_next_subdir()

    def flag_mixed(self):
        """Queue the current burst for pass 2 and move straight on to the
        next burst in pass 1 -- does not drop into group/frame review here."""
        source_set, subdir = self.current_source_set, self.current_subdir
        key = subdir_key(source_set, subdir.name)
        self.progress["subdir_decisions"][key] = "drilldown"
        save_progress(self.progress)

        self.drilldown_queue.append((source_set, subdir))
        self.history.append({
            "type": "flag_mixed", "source_set": source_set, "subdir": subdir,
        })
        print(f"Burst '{subdir.name}' flagged mixed -- queued for pass 2")
        self.advance_to_next_subdir()

    # ------------------------------------------------------------------
    # Pass 2a -- group review (--group-size frames at a time)
    # ------------------------------------------------------------------

    def _first_undecided_frame_index(self):
        for i, f in enumerate(self.current_frames):
            fkey = frame_key(self.current_source_set, self.current_subdir.name, f.name)
            if fkey not in self.progress["frame_decisions"]:
                return i
        return len(self.current_frames)

    def _group_bounds_for(self, frame_index):
        start = (frame_index // self.group_size) * self.group_size
        end = min(start + self.group_size, len(self.current_frames))
        return start, end

    def _show_current_group(self):
        frame_index = self._first_undecided_frame_index()
        if frame_index >= len(self.current_frames):
            self.advance_to_next_subdir()
            return

        self.mode = "group"
        self.current_group_start, self.current_group_end = self._group_bounds_for(frame_index)
        group_frames = self.current_frames[self.current_group_start:self.current_group_end]
        self._render_thumbnails(group_frames)

        total_groups = -(-len(self.current_frames) // self.group_size)  # ceil division
        group_number = self.current_group_start // self.group_size + 1
        done_bursts = self.total_drilldown_subdirs - len(self.drilldown_queue) - 1
        self.status_label.config(text=(
            f"[{self.current_source_set}] {self.current_subdir.name}  —  "
            f"frames {self.current_group_start + 1}-{self.current_group_end}/{len(self.current_frames)}   "
            f"group {group_number}/{total_groups}   "
            f"—  pass 2: burst {done_bursts + 1}/{self.total_drilldown_subdirs}"
        ))
        self.hint_label.config(text=(
            "N = this group Negative   P = this group Positive   "
            "M = Mixed (or ANY compass-obscured map here) -> review individually\n"
            "T = back to triage for this burst   U = undo last action   Q = save & quit"
        ))

    def commit_group_bulk(self, decision):
        source_set, subdir = self.current_source_set, self.current_subdir
        start, end = self.current_group_start, self.current_group_end
        group_frames = self.current_frames[start:end]
        copied = []
        for f in group_frames:
            output_name = f"{source_set}__{subdir.name}__{f.name}"
            dest = copy_frame(f, decision, output_name)
            copied.append(dest)
            fkey = frame_key(source_set, subdir.name, f.name)
            self.progress["frame_decisions"][fkey] = decision
        save_progress(self.progress)

        self.history.append({
            "type": "group", "source_set": source_set, "subdir": subdir,
            "decision": decision, "files": copied,
            "filenames": [f.name for f in group_frames],
        })
        print(f"Group [{start + 1}-{end}] of '{subdir.name}' ({len(copied)} frames) -> {decision}")
        self._show_current_group()

    def flag_group_mixed(self):
        """Drop into single-frame review of just the current group's frames."""
        self.history.append({
            "type": "enter_group_drilldown",
            "source_set": self.current_source_set, "subdir": self.current_subdir,
            "group_start": self.current_group_start, "group_end": self.current_group_end,
        })
        self.mode = "drilldown"
        self.current_frame_index = self._first_undecided_frame_index()
        self.last_decision = None
        self._show_current_drilldown_frame()

    # ------------------------------------------------------------------
    # Pass 2b -- single-frame review, within a flagged group
    # ------------------------------------------------------------------

    def _show_current_drilldown_frame(self):
        if self.current_frame_index >= self.current_group_end:
            self._show_current_group()
            return

        path = self.current_frames[self.current_frame_index]
        with Image.open(path) as img:
            img = img.copy()
        img.thumbnail((10000, self.fullsize_max_height))
        self._full_photoimage = ImageTk.PhotoImage(img)

        for child in self.image_frame.winfo_children():
            child.destroy()
        tk.Label(self.image_frame, image=self._full_photoimage).pack()

        self.status_label.config(text=(
            f"[{self.current_source_set}] {self.current_subdir.name}  —  "
            f"frame {self.current_frame_index + 1}/{len(self.current_frames)}: {path.name}   "
            f"(group {self.current_group_start + 1}-{self.current_group_end})"
        ))
        same_hint = f"  (SPACE = {self.last_decision})" if self.last_decision else ""
        self.hint_label.config(text=(
            f"N = Negative   P = Positive   C = Map, compass obscured{same_hint}\n"
            f"T = back to triage for this burst   U = undo last decision   Q = save & quit"
        ))

    def back_to_triage(self):
        """Bail out all the way back to the triage screen for the current
        subdir, discarding any frame decisions already made for it this
        pass. Exists because a subdir's "drilldown" state persists in the
        saved progress across sessions, but the undo history does not --
        so once you've relaunched, U alone can no longer walk back out of it."""
        source_set, subdir = self.current_source_set, self.current_subdir
        removed = 0
        for f in self.current_frames:
            fkey = frame_key(source_set, subdir.name, f.name)
            decision = self.progress["frame_decisions"].pop(fkey, None)
            if decision is not None:
                output_name = f"{source_set}__{subdir.name}__{f.name}"
                (output_dir_for(decision) / output_name).unlink(missing_ok=True)
                removed += 1

        key = subdir_key(source_set, subdir.name)
        self.progress["subdir_decisions"].pop(key, None)
        save_progress(self.progress)

        # Any history entries for this subdir now reference decisions that
        # no longer exist -- drop them so a later U doesn't try to undo
        # something already cleared here.
        self.history = [
            a for a in self.history
            if not (a.get("source_set") == source_set and a.get("subdir") == subdir)
        ]

        if removed:
            print(f"Back to triage: cleared {removed} frame decision(s) for '{subdir.name}'")
        self.show_triage()

    def commit_drilldown_frame(self, decision):
        source_set, subdir = self.current_source_set, self.current_subdir
        path = self.current_frames[self.current_frame_index]
        output_name = f"{source_set}__{subdir.name}__{path.name}"
        dest = copy_frame(path, decision, output_name)

        fkey = frame_key(source_set, subdir.name, path.name)
        self.progress["frame_decisions"][fkey] = decision
        save_progress(self.progress)

        self.history.append({
            "type": "frame", "source_set": source_set, "subdir": subdir,
            "filename": path.name, "decision": decision, "file": dest,
        })
        self.last_decision = decision
        self.current_frame_index += 1
        self._show_current_drilldown_frame()

    # ------------------------------------------------------------------
    # Undo — pops the last committed action of any kind, reverses its
    # effect, and re-queues whatever was displaced so nothing silently
    # falls out of either review queue.
    # ------------------------------------------------------------------

    def undo(self):
        if not self.history:
            self.status_label.config(text="Nothing to undo.")
            return
        action = self.history.pop()

        if action["type"] == "subdir":
            for f in action["files"]:
                f.unlink(missing_ok=True)
            key = subdir_key(action["source_set"], action["subdir"].name)
            self.progress["subdir_decisions"].pop(key, None)
            save_progress(self.progress)

            self._requeue_displaced_current()
            self.triage_queue.insert(0, (action["source_set"], action["subdir"]))

            print(f"Undone: burst '{action['subdir'].name}' ({action['decision']})")
            self.advance_to_next_subdir()

        elif action["type"] == "flag_mixed":
            key = subdir_key(action["source_set"], action["subdir"].name)
            self.progress["subdir_decisions"].pop(key, None)
            save_progress(self.progress)

            entry = (action["source_set"], action["subdir"])
            if entry in self.drilldown_queue:
                self.drilldown_queue.remove(entry)

            self._requeue_displaced_current()
            self.triage_queue.insert(0, entry)

            print(f"Undone: flagged mixed for burst '{action['subdir'].name}'")
            self.advance_to_next_subdir()

        elif action["type"] == "group":
            for f in action["files"]:
                f.unlink(missing_ok=True)
            for filename in action["filenames"]:
                fkey = frame_key(action["source_set"], action["subdir"].name, filename)
                self.progress["frame_decisions"].pop(fkey, None)
            save_progress(self.progress)

            if self.current_source_set != action["source_set"] or self.current_subdir != action["subdir"]:
                self._requeue_displaced_current()

            self._set_current(action["source_set"], action["subdir"])
            print(f"Undone: group in '{action['subdir'].name}' ({action['decision']})")
            self._show_current_group()

        elif action["type"] == "enter_group_drilldown":
            if self.current_source_set != action["source_set"] or self.current_subdir != action["subdir"]:
                self._requeue_displaced_current()

            self._set_current(action["source_set"], action["subdir"])
            self.current_group_start = action["group_start"]
            self.current_group_end = action["group_end"]
            print(f"Undone: entered single-frame review for group in '{action['subdir'].name}'")
            self._show_current_group()

        elif action["type"] == "frame":
            action["file"].unlink(missing_ok=True)
            fkey = frame_key(action["source_set"], action["subdir"].name, action["filename"])
            self.progress["frame_decisions"].pop(fkey, None)
            save_progress(self.progress)

            if self.current_source_set != action["source_set"] or self.current_subdir != action["subdir"]:
                self._requeue_displaced_current()

            self._set_current(action["source_set"], action["subdir"])
            self.mode = "drilldown"
            self.current_frame_index = self._first_undecided_frame_index()
            self.current_group_start, self.current_group_end = self._group_bounds_for(self.current_frame_index)
            self.last_decision = None
            print(f"Undone: frame '{action['filename']}' ({action['decision']})")
            self._show_current_drilldown_frame()

    # ------------------------------------------------------------------
    # Input / lifecycle
    # ------------------------------------------------------------------

    def on_key(self, event):
        key = event.keysym.lower()
        if key == "q":
            self.quit()
            return
        if key == "u":
            self.undo()
            return

        if self.mode == "triage":
            if key == "n":
                self.commit_subdir_bulk("negative")
            elif key == "p":
                self.commit_subdir_bulk("positive")
            elif key == "m":
                self.flag_mixed()
        elif self.mode == "group":
            if key == "n":
                self.commit_group_bulk("negative")
            elif key == "p":
                self.commit_group_bulk("positive")
            elif key == "m":
                self.flag_group_mixed()
            elif key == "t":
                self.back_to_triage()
        elif self.mode == "drilldown":
            if key == "n":
                self.commit_drilldown_frame("negative")
            elif key == "p":
                self.commit_drilldown_frame("positive")
            elif key == "c":
                self.commit_drilldown_frame(MAP_COMPASS_OBSCURED)
            elif key == "space" and self.last_decision:
                self.commit_drilldown_frame(self.last_decision)
            elif key == "t":
                self.back_to_triage()

    def show_done(self):
        self.mode = "done"
        for child in self.image_frame.winfo_children():
            child.destroy()
        self.status_label.config(text="All bursts classified.")
        self.hint_label.config(text="Q = save & quit")

    def quit(self):
        save_progress(self.progress)

        def count(decision):
            d = output_dir_for(decision)
            return len(list(d.glob("*.png"))) if d.exists() else 0

        remaining = len(self.triage_queue) + len(self.drilldown_queue)
        remaining += 1 if self.mode not in (None, "done") else 0
        print(f"\nProgress saved. brute_force/positive: {count('positive')} frames, "
              f"brute_force/negative: {count('negative')} frames, "
              f"brute_force/{MAP_COMPASS_OBSCURED}: {count(MAP_COMPASS_OBSCURED)} frames.")
        print(f"Bursts remaining to review: {remaining}")
        self.root.destroy()

    def run(self):
        self.root.mainloop()


def parse_args():
    parser = argparse.ArgumentParser(
        description="Interactively classify the unreviewed map_detection brute-force frames.",
    )
    parser.add_argument(
        "--stride", type=int, default=25,
        help="Sample one frame every N frames (plus the last frame) when previewing a burst in pass 1 (default: 25)",
    )
    parser.add_argument(
        "--group-size", type=int, default=5,
        help="Number of frames shown/classified together in pass 2 group review (default: 5)",
    )
    return parser.parse_args()


def main():
    args = parse_args()

    if not NEGATIVE_ROOT.exists():
        sys.exit(f"Error: expected source directory not found — {NEGATIVE_ROOT}")

    progress = load_progress()
    app = ClassifierApp(progress, stride=args.stride, group_size=args.group_size)
    app.run()


if __name__ == "__main__":
    main()
