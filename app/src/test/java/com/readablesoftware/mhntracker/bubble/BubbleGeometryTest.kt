package com.readablesoftware.mhntracker.bubble

import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleGeometryTest {

    @Test
    fun `coord already in bounds, returned unchanged`() {
        assertEquals(100, clampBubbleCoord(100, 150, 1080))
    }

    @Test
    fun `when coord is negative it is adjusted to 0`() {
        assertEquals(0, clampBubbleCoord(-30, 150, 1080))
    }

    @Test
    fun `when coord is past edge of screen, it is adjusted to screenSize - bubbleSize`() {
        assertEquals(930, clampBubbleCoord(2000, 150, 1080))
    }

    @Test
    fun `when coord is exactly on the edge, it is not changed`() {
        assertEquals(0, clampBubbleCoord(0, 150, 1080))
        assertEquals(930, clampBubbleCoord(930, 150, 1080))
    }

    @Test
    fun `if bubbleSize larger than screenSize it clamps to 0` () {
        assertEquals(0, clampBubbleCoord(50, 1200, 1080))
    }
}