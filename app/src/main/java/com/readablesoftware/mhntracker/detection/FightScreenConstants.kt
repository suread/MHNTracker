package com.readablesoftware.mhntracker.detection

object FightScreenConstants {

    // "BREAK" text region — Pixel 7, measured from full-resolution frames
    // Crop tightly around the word BREAK only; excludes the variable part label below.
    // The label text ("Body", "Head (1st Time)", etc.) is captured by saving the
    // full frame — it does not need to be within this detection crop.
    const val BREAK_X1 = 370
    const val BREAK_Y1 = 520
    const val BREAK_X2 = 712
    const val BREAK_Y2 = 600
}
