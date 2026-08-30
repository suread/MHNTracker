package com.readablesoftware.mhntracker.bubble

import android.app.Application
import android.graphics.Point
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BubblePreferencesTest {

    private lateinit var context: Application
    private lateinit var prefs: BubblePreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = BubblePreferences(context)
    }

    @Test
    fun `isEnabled is false by default`() {
        assertFalse(prefs.isEnabled)
    }

    @Test
    fun `isEnabled returns true after being set true`() {
        prefs.isEnabled = true
        assertTrue(prefs.isEnabled)
    }

    @Test
    fun `isEnabled returns false after being set false`() {
        prefs.isEnabled = false
        assertFalse(prefs.isEnabled)
    }

    @Test
    fun `isEnabled persists across instances`() {
        prefs.isEnabled = true
        assertTrue(BubblePreferences(context).isEnabled)
    }


    @Test
    fun `position is null when unset`() {
        assertNull(prefs.position)
    }

    @Test
    fun `position returns the correct point after it is set`() {
        val point = Point(3, 7)
        prefs.position = point
        assertEquals(point, prefs.position)
    }

    @Test
    fun `position persists across instances`() {
        val point = Point(3, 7)
        prefs.position = point
        assertEquals(point, BubblePreferences(context).position)
    }

    @Test
    fun `position accepts and returns negative coordinate values`() {
        val point = Point(-5, -10)
        prefs.position = point
        assertEquals(point, prefs.position)
        val point2 = Point(-1, -1)
        prefs.position = point2
        assertEquals(point2, prefs.position)
    }

    @Test
    fun `setting position to null resets the position`() {
        val point = Point(3, 7)
        prefs.position = point
        assertEquals(point, prefs.position)

        prefs.position = null
        assertNull(prefs.position)
    }

    @Test
    fun `enabled and position don't clobber each other`() {
        val point = Point(3, 7)
        prefs.isEnabled = true
        prefs.position = point
        assertTrue(prefs.isEnabled)
        assertEquals(point, prefs.position)

        prefs.isEnabled = false
        assertFalse(prefs.isEnabled)
        assertEquals(point, prefs.position)
    }
}