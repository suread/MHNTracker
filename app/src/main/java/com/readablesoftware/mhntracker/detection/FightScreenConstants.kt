package com.readablesoftware.mhntracker.detection

object FightScreenConstants {

    // "BREAK" graphic/stylised text region — used to recognise a break has occurred
    // based on Pixel 7, measured from full-resolution frames
    // Crop tightly around the word BREAK only; excludes the variable part label below.
    const val BREAK_GRAPHIC_X1 = 370
    const val BREAK_GRAPHIC_Y1 = 520
    const val BREAK_GRAPHIC_X2 = 712
    const val BREAK_GRAPHIC_Y2 = 600

    // Break part name region - the important bit for storage/OCR
    // y values are tight to the coloured background top and bottom
    // x values have generous margin around "Special Part" text (this text fits inside 400->700) - TODO watch out for longer text/consider using full screen width!
    const val BREAK_TEXT_X1 = 300
    const val BREAK_TEXT_Y1 = 605
    const val BREAK_TEXT_X2 = 800
    const val BREAK_TEXT_Y2 = 665

}
