package com.readablesoftware.mhntracker.detection

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
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
        val context = InstrumentationRegistry.getInstrumentation().context  // not .targetContext
        //val context = InstrumentationRegistry.getInstrumentation().targetContext

        val assetList = context.assets.list("")?.joinToString()
        Log.d("MHNDebug", "Assets visible at runtime: $assetList")

        val bitmap = context.assets.open("break_crops.png").use { stream ->
            BitmapFactory.decodeStream(stream)
        }

        val detector = MlKitTextDetector()
        val result = detector.detectText(bitmap)

        println("MLKit result:\n$result")
    }
}