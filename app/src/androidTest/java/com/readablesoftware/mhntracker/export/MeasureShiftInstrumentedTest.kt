package com.readablesoftware.mhntracker.export

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.readablesoftware.mhntracker.testutil.TestFrameLoader
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.opencv.android.OpenCVLoader


@RunWith(AndroidJUnit4::class)
class MeasureShiftInstrumentedTest {

    @Before
    fun setUp() {
        OpenCVLoader.initLocal()
    }

    @Test
    fun measureShift_against_real_consecutive_frame_pairs() {

        val frameA = TestFrameLoader.loadTestFrame("measureShift-frames/20260730-122843", 23)
        val frameB = TestFrameLoader.loadTestFrame("measureShift-frames/20260730-122843", 24)
        val frameC = TestFrameLoader.loadTestFrame("measureShift-frames/20260730-122843", 25)
        val frameD = TestFrameLoader.loadTestFrame("measureShift-frames/20260730-122843", 26)

        val composer = HuntReportComposer()

        val result1 = composer.measureShift(frameA, frameB, x1 = 150, x2 = 200, yTop = 850, height = 300, maxShift = 1200)
        val result2 = composer.measureShift(frameB, frameC, x1 = 150, x2 = 200, yTop = 450, height = 300, maxShift = 1200)
        val result3 = composer.measureShift(frameC, frameD, x1 = 150, x2 = 200, yTop = 450, height = 300, maxShift = 1200)

        println("offset=${result1.offset} confidence=${result1.confidence}")
        assertEquals(674, result1.offset)
        assertEquals(0.9433, result1.confidence, 1e-4)

        assertEquals(0, result2.offset)
        assertEquals(1.0, result2.confidence, 1e-4)

        assertEquals(0, result3.offset)
        assertEquals(0.9998, result3.confidence, 1e-4)

    }

}