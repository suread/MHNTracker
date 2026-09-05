package com.readablesoftware.mhntracker.bubble

internal fun clampBubbleCoord(
    pos: Int, bubbleSize: Int, screenSize: Int,
): Int {
    val maxPos = (screenSize - bubbleSize).coerceAtLeast(0)
    return pos.coerceIn(0, maxPos)
}