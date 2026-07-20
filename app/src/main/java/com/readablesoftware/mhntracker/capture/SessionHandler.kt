package com.readablesoftware.mhntracker.detection

import android.graphics.Bitmap

/**
 * Contract for a session handler — a self-contained unit that owns detection
 * logic, session state, and save/cleanup behaviour for one type of capture
 * activity (fight, inventory, etc.).
 *
 * Lifecycle managed by ScreenCaptureService (the router):
 *
 *   1. When no handler is active, the router calls [recognisesTrigger] on each
 *      registered handler at the slow-check rate (every ~600ms). The first
 *      handler to return true becomes the active handler. That frame is
 *      considered consumed by the trigger check — [onFrame] is not called
 *      with it.
 *
 *   2. While a handler is active, the router calls [onFrame] for every
 *      subsequent frame. The handler decides internally whether to process
 *      or skip each frame. When the handler has finished it returns
 *      [HandlerStatus.DONE] and performs its own cleanup before returning.
 *      The router will not call [onFrame] again after DONE is returned.
 *
 *   3. If the router detects an external termination signal (map screen, or a
 *      higher-priority handler's trigger firing), it calls [onTerminate]. The
 *      handler must clean up — flush, delete partial data, write a marker, or
 *      do nothing — before returning. The router discards the handler reference
 *      immediately after [onTerminate] returns.
 *
 * Priority:
 *   Handlers are registered in priority order. If [recognisesTrigger] returns
 *   true for more than one handler on the same frame, the first by registration
 *   order wins and an error is logged. Handlers that are time-critical or
 *   irreversible (fight) should be registered before recoverable ones (inventory).
 */
interface SessionHandler {

    /**
     * Called at the slow-check rate (~600ms) when no handler is currently
     * active. The handler performs its trigger detection on this frame.
     *
     * Returns true if the handler recognises its activation condition and
     * wants to take over frame processing. This frame is considered consumed
     * — [onFrame] will not be called with it.
     *
     * Must be fast: runs on the consumer coroutine and shares the slow-check
     * budget with map detection and all other registered handlers.
     */
    fun recognisesTrigger(frame: Bitmap): Boolean

    /**
     * Called for every frame while this handler is active. The handler
     * decides internally whether to process or discard each frame.
     *
     * Returns [HandlerStatus.CONTINUE] to keep receiving frames, or
     * [HandlerStatus.DONE] when finished. On DONE, the handler must have
     * already performed any necessary cleanup — the router will not call
     * [onTerminate] after a DONE return.
     */
    fun onFrame(frame: Bitmap): HandlerStatus

    /**
     * Called by the router when this handler is forcibly terminated — either
     * because a map/escape signal fired, or a higher-priority handler's
     * trigger was recognised.
     *
     * The handler must clean up before returning. The router discards the
     * handler reference immediately after this call returns.
     */
    fun onTerminate()
}

enum class HandlerStatus {
    CONTINUE,   // handler is still working; keep sending frames
    DONE,       // handler finished (naturally or self-aborted); already cleaned up
}