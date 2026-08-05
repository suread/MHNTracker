package com.readablesoftware.mhntracker.detection

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test


@RunWith(AndroidJUnit4::class)
class MlKitTextDetectorInstrumentedTest {

    @Test
    fun readsTextFromCompositeBreakImage() = runBlocking {
        // Must be .context, not .targetContext — using targetContext here caused
        // assets to silently not be found, which cost a lot of debugging time.
        val context = InstrumentationRegistry.getInstrumentation().context

        val bitmap = context.assets.open("break_crops.png").use { stream ->
            BitmapFactory.decodeStream(stream)
        }

        val detector = MlKitTextDetector()
        val result = detector.detectText(bitmap)

        // Exploratory, not a pass/fail check — used to visually confirm MLKit
        // produces meaningful text output from the break crop composite before
        // trusting the image file it's built from.
        println("MLKit result:\n$result")
    }
}