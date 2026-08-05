package com.readablesoftware.mhntracker.debug

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DebugFrameSaveTest {

    // enabled is a mutable singleton — restore it so this test doesn't leak
    // state into other tests sharing the same JVM.
    private val originalEnabled = DebugFrameSave.enabled

    @Before
    fun setUp() {
        DebugFrameSave.enabled = setOf(FrameSaveFlow.BREAK)
    }

    @After
    fun tearDown() {
        DebugFrameSave.enabled = originalEnabled
    }

    @Test
    fun `shouldSave returns true for a flow in the enabled set`() {
        assertTrue(DebugFrameSave.shouldSave(FrameSaveFlow.BREAK))
    }

    @Test
    fun `shouldSave returns false for a flow not in the enabled set`() {
        assertFalse(DebugFrameSave.shouldSave(FrameSaveFlow.REPORT))
    }
}
