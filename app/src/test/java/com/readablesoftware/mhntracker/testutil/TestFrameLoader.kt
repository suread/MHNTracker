package com.readablesoftware.mhntracker.testutil

import android.graphics.Bitmap
import android.graphics.BitmapFactory

object TestFrameLoader {

    fun loadTestFrame(directoryName: String, frameIndex: Int): Bitmap =
        loadTestFrame(directoryName, "frame_${frameIndex.toString().padStart(4, '0')}.png")

    fun loadTestFrame(directoryName: String, frameName: String): Bitmap {
        val path = "frames/$directoryName/$frameName"
        val stream = resourceStream(path)
        return BitmapFactory.decodeStream(stream)
            ?: error("Failed to decode bitmap from: $path")
    }

    private fun resourceStream(path: String): java.io.InputStream {
        val contextStream = Thread.currentThread().contextClassLoader?.getResourceAsStream(path)
        if (contextStream != null) {
            println("TestFrameLoader: resolved via contextClassLoader — $path")
            return contextStream
        }

        val classStream = javaClass.classLoader?.getResourceAsStream(path)
        if (classStream != null) {
            println("TestFrameLoader: resolved via javaClass.classLoader (contextClassLoader missed) — $path")
            return classStream
        }

        error("Test resource not found via either classloader: $path")

        // use if do not want to check which is being used through console
//        return Thread.currentThread().contextClassLoader?.getResourceAsStream(path)
//            ?: javaClass.classLoader?.getResourceAsStream(path)
//            ?: error("Test resource not found via either classloader: $path")
    }
}