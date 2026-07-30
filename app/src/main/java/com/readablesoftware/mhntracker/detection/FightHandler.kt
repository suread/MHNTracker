package com.readablesoftware.mhntracker.detection

import android.graphics.Bitmap
import android.util.Log
import com.readablesoftware.mhntracker.capture.AppState
import com.readablesoftware.mhntracker.capture.CaptureStatus
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import com.readablesoftware.mhntracker.export.BreakCropComposer
import java.util.Locale


/**
 * Session handler for a complete fight — from fight start through to the
 * hunt report confirm button.
 *
 * Internal sub-states:
 *
 *   WATCHING   — fight is in progress. Checks for breaks (crops buffered in
 *                memory via BreakCropComposer - not written to disk until the
 *                session ends) and the hunt report screen. Transitions to
 *                CAPTURING when the hunt report appears.
 *
 *   CAPTURING  — hunt report screen is visible. Saves every frame until the
 *                confirm button is detected or the frame cap is reached.
 *
 * Session directory:
 *   Created lazily on the first save (either a break frame or the first hunt
 *   report frame). All frames from both sub-states go into the same directory
 *   so break context is available alongside the hunt report frames.
 *   Directory name: sessions/<yyyyMMdd-HHmmss>/
 *   Break crops:    sessions/<timestamp>/break_crops-<export_timestamp>.png
 *                   — one composite image per session (all buffered break
 *                   crops stitched together), written on reset.
 *   Report frames:  sessions/<timestamp>/frame_0001.png, frame_0002.png, …
 *
 * Trigger:
 *   [recognisesTrigger] delegates to [FightStartDetector]. The router calls
 *   this at the slow-check rate (~600ms) when no handler is active.
 *
 * Re-entry:
 *   If the fight-start banner is somehow re-detected while WATCHING, it is
 *   ignored — the handler is already active and the banner may persist across
 *   multiple frames at the start of a fight. This is not an error condition.
 *
 * Cleanup:
 *   On natural completion ([HandlerStatus.DONE] from [onFrame]): session is
 *   kept on disk for later processing.
 *   On forced termination ([onTerminate]): session directory is kept — partial
 *   sessions may still be useful. A marker file is written to indicate the
 *   session did not complete normally.
 */
class FightHandler(
    private val fightStartDetector:  FightStartDetector,
    private val huntReportDetector:  HuntReportDetector,
    private val fightEventDetector:  FightEventDetector,
    private val baseDir:             File,
) : SessionHandler {

    companion object {
        // Safety cap: stop capturing if confirm button is never detected.
        // 300 frames × 200ms = 60 seconds — enough for any hunt report screen.
        private const val MAX_REPORT_FRAMES = 300

        private const val TAG = "MHNFight"
    }

    // Break crops for analysis/storage
    private var breakCropComposer = BreakCropComposer()

    // ── Sub-state ─────────────────────────────────────────────────────────
    private enum class SubState { WATCHING, CAPTURING }
    private var subState = SubState.WATCHING

    // ── Session directory — created lazily on first save ──────────────────
    private var sessionDir: File? = null
    private var reportFrameIndex = 0

    // ── SessionHandler ────────────────────────────────────────────────────

    /**
     * Returns true if the fight-start banner is visible on this frame.
     * The router calls this at the slow-check rate when no handler is active.
     * This frame is consumed by the trigger check and not passed to [onFrame].
     */
    override fun recognisesTrigger(frame: Bitmap): Boolean {
        val result = fightStartDetector.isFightStartVisible(frame)
        breakCropComposer = BreakCropComposer()
        if (result) Log.d(TAG, "Fight start trigger recognised")
        return result
    }

    /**
     * Called for every frame while this handler is active.
     * Dispatches to [processWatching] or [processCapturing] based on sub-state.
     */
    override fun onFrame(frame: Bitmap): HandlerStatus {
        return when (subState) {
            SubState.WATCHING  -> processWatching(frame)
            SubState.CAPTURING -> processCapturing(frame)
        }
    }

    /**
     * Called by the router on forced external termination (map detected, or a
     * higher-priority handler's trigger fired).
     *
     * Keeps the session directory — partial data may still be useful.
     * Writes an "incomplete" marker file so the processing stage can identify
     * sessions that did not complete normally.
     */
    override fun onTerminate() {
        Log.d(TAG, "Handler terminated externally in sub-state=$subState")
        writeIncompleteMarker()
        resetSubState()
        AppState.setMediaProjectionActive(CaptureStatus.FIGHT_TERMINATED)
    }

    // ── Sub-state processing ──────────────────────────────────────────────

    /**
     * WATCHING: check for breaks and the hunt report screen.
     * Both run on every frame — breaks are brief, hunt report needs prompt
     * capture to avoid missing the first frame.
     */
    private fun processWatching(frame: Bitmap): HandlerStatus {
        val t1 = System.currentTimeMillis()
        val huntVisible = huntReportDetector.isHuntReportScreen(frame)
        Log.d("MHNTiming", "isHuntReportScreen: ${System.currentTimeMillis() - t1}ms  result=$huntVisible")

        if (huntVisible) {
            Log.d(TAG, "Hunt report detected — switching to CAPTURING")
            subState = SubState.CAPTURING
            reportFrameIndex = 0
            saveReportFrame(frame)
            return HandlerStatus.CONTINUE
        }

        val t2 = System.currentTimeMillis()
        val breakVisible = fightEventDetector.isBreakVisible(frame)
        Log.d("MHNTiming", "isBreakVisible: ${System.currentTimeMillis() - t2}ms  result=$breakVisible")

        if (breakVisible) {
            Log.d(TAG, "BREAK detected — storing break frame")
            breakCropComposer.addBreakFrame(frame)
        }

        return HandlerStatus.CONTINUE
    }

    /**
     * CAPTURING: save every frame until the confirm button is detected or the
     * frame cap is reached.
     */
    private fun processCapturing(frame: Bitmap): HandlerStatus {
        val t1 = System.currentTimeMillis()
        val confirmVisible = huntReportDetector.isConfirmButtonVisible(frame)
        Log.d("MHNTiming", "isConfirmButtonVisible: ${System.currentTimeMillis() - t1}ms  result=$confirmVisible")

        return when {
            confirmVisible -> {
                Log.d(TAG, "Confirm button detected — session complete, " +
                        "$reportFrameIndex report frames saved")
                saveReportFrame(frame)
                resetSubState()
                HandlerStatus.DONE
            }
            reportFrameIndex >= MAX_REPORT_FRAMES -> {
                Log.w(TAG, "Frame cap reached ($MAX_REPORT_FRAMES) — ending session")
                writeIncompleteMarker()
                resetSubState()
                HandlerStatus.DONE
            }
            else -> {
                saveReportFrame(frame)
                HandlerStatus.CONTINUE
            }
        }
    }

    // ── Save helpers ──────────────────────────────────────────────────────

    /**
     * Saves a hunt report frame to the session directory.
     * Creates the session directory lazily on first call.
     */
    private fun saveReportFrame(bitmap: Bitmap) {
        val dir = requireSessionDir()
        val file = File(dir, "frame_${reportFrameIndex.toString().padStart(4, '0')}.png")
        dir.mkdirs()   // defensive: recreate if deleted mid-session
        FileOutputStream(file).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        reportFrameIndex++
        Log.d(TAG, "Saved report frame ${file.name}")
    }

    /**
     * Writes a zero-byte marker file into the session directory (if one
     * exists) to flag that this session did not reach the confirm button.
     * The processing stage can use this to skip or flag the session.
     */
    private fun writeIncompleteMarker() {
        val dir = sessionDir ?: return   // no saves yet — nothing to mark
        dir.mkdirs()
        File(dir, "incomplete").createNewFile()
        Log.d(TAG, "Wrote incomplete marker to ${dir.name}")
    }

    /**
     * Returns the session directory, creating it if this is the first save.
     */
    private fun requireSessionDir(): File {
        return sessionDir ?: run {
            baseDir.mkdirs()
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.UK).format(Date())
            val dir = File(baseDir, "sessions/$timestamp")
            dir.mkdirs()
            sessionDir = dir
            Log.d(TAG, "Session directory created: ${dir.path}")
            dir
        }
    }

    /**
     * Resets sub-state and session tracking ready for the next activation.
     * Does not touch the session directory — it remains on disk.
     */
    private fun resetSubState() {
        Log.d(TAG, "resetSubState")
        val file = breakCropComposer.exportComposite(requireSessionDir())
        Log.d(TAG, "Saved break crops: ${file.name}")

        subState         = SubState.WATCHING
        sessionDir       = null
        reportFrameIndex = 0
    }

}