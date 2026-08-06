package com.readablesoftware.mhntracker.detection

object RewardsScreenConstants {

    // Hunt report scroll stitching (screen position/size measurements all based on Pixel 7)
    const val STATUS_AREA_HEIGHT = 140  // status bar band cropped off the top of every frame

    // Scroll-shift detection (ported from scroll_shift_prototype.py, Pixel 7 gesture-nav)
    const val SCROLL_TEMPLATE_X1 = 150
    const val SCROLL_TEMPLATE_X2 = 200
    const val SCROLL_TEMPLATE_Y_TOP = 450
    const val SCROLL_TEMPLATE_HEIGHT = 300
    const val SCROLL_MAX_SHIFT = 1200
    const val SCROLL_TEMPLATE_STEP = 100
    const val SCROLL_BOTTOM_MARGIN = 63     // gesture-nav only; on-screen nav needs more
    const val SCROLL_CONFIDENCE_THRESHOLD = 0.9   // not device-specific

    // Grid layout
    const val COLUMN_X_FIRST  = 30
    const val FRAME_WIDTH     = 240
    const val FRAME_HEIGHT    = 240
    const val COLUMN_SPACING  = 260     // 240 + 20 gap
    const val MAX_COLUMNS     = 4
    const val BORDER_SIDE     = 8       // left / right / top
    const val BORDER_BOTTOM   = 64

    // Column left edges (derived, listed explicitly for clarity)
    // Col 0: 30, Col 1: 290, Col 2: 550, Col 3: 810

    // Count pill (relative to icon top-left)
    const val PILL_TOP        = 140
    const val PILL_BOTTOM     = 180
    const val PILL_LEFT_MIN   = 150     // max 2 digits
    const val PILL_RIGHT      = 215
    const val PILL_H          = 40      // PILL_BOTTOM - PILL_TOP
    const val PILL_W          = 65      // PILL_RIGHT - PILL_LEFT_MIN

    // Screen classifier region — "Hunt Report" text crop
    const val HUNT_REPORT_X1  = 66
    const val HUNT_REPORT_Y1  = 225
    const val HUNT_REPORT_X2  = 453
    const val HUNT_REPORT_Y2  = 286

    // Screen classifier region — "Rewards" section-divider text crop.
    // Alternative trigger for the Hunt Report screen: the "Hunt Report" title
    // above can be obscured by stacked pop-ups (quest/event toasts) for long
    // enough that it scrolls off before ever being seen unobscured. "Rewards"
    // sits lower on the same static screen and is unaffected by that overlap.
    // Coordinates confirmed pixel-identical across three independent hunt
    // reports (different monsters/star ratings/completion times).
    const val REWARDS_X1      = 436
    const val REWARDS_Y1      = 787
    const val REWARDS_X2      = 646
    const val REWARDS_Y2      = 834

    // Confirm button region
    const val CONFIRM_X1      = 50
    const val CONFIRM_Y1      = 2090
    const val CONFIRM_X2      = 1028
    const val CONFIRM_Y2      = 2217

    // Confirm button colour sample (BGR, left side of button)
    val CONFIRM_BGR = intArrayOf(23, 177, 253)
    const val CONFIRM_COLOUR_TOLERANCE = 20
    const val CONFIRM_SAMPLE_X1 = 65
    const val CONFIRM_SAMPLE_Y1 = 2110
    const val CONFIRM_SAMPLE_X2 = 110
    const val CONFIRM_SAMPLE_Y2 = 2190
}