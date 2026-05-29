package com.readablesoftware.mhntracker.detection

object RewardsScreenConstants {

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

    // Confirm button region
    const val CONFIRM_X1      = 50
    const val CONFIRM_Y1      = 2090
    const val CONFIRM_X2      = 1028
    const val CONFIRM_Y2      = 2217

    // Confirm button colour sample (BGR, left side of button)
    val CONFIRM_BGR           = intArrayOf(63, 185, 234)
    const val CONFIRM_COLOUR_TOLERANCE = 20
}