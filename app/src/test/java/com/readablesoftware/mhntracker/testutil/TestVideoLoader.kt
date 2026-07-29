package com.readablesoftware.mhntracker.testutil

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.net.URL

object TestVideoLoader {

    private val videoCache = mutableMapOf<String, List<Bitmap>>()

    fun loadTestVideo(videoName: String): List<Bitmap> {
        return videoCache.getOrPut(videoName) {
            val prefix = "frames/$videoName/"
            val resourceUrl = resourceUrlFor(prefix)
                ?: return@getOrPut emptyList()

            val options = BitmapFactory.Options().apply { inSampleSize = 4 }
            File(resourceUrl.toURI())
                .listFiles { f -> f.extension == "png" }
                ?.sortedBy { it.name }
                ?.mapNotNull { file ->
                    file.inputStream().use { stream ->
                        BitmapFactory.decodeStream(stream, null, options)
                    }
                }
                ?: emptyList()
        }
    }

    private fun resourceUrlFor(prefix: String): URL? {
        val contextUrl = Thread.currentThread().contextClassLoader?.getResource(prefix)
        if (contextUrl != null) {
            println("TestVideoLoader: resolved via contextClassLoader — $prefix")
            return contextUrl
        }

        val classUrl = javaClass.classLoader?.getResource(prefix)
        if (classUrl != null) {
            println("TestVideoLoader: resolved via javaClass.classLoader (contextClassLoader missed) — $prefix")
        }
        return classUrl
    }
}