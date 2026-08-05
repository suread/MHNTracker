package com.readablesoftware.mhntracker.detection

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class MlKitTextDetector : TextDetector {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun detectText(bitmap: Bitmap): String = suspendCoroutine { cont ->
        val t0 = System.currentTimeMillis()
        Log.d("MHN-timing", " MLKit.detectText start: ${System.currentTimeMillis() - t0}ms")
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                Log.d(
                    "MHN-timing",
                    "MLKit success callback after ${System.currentTimeMillis() - t0}ms"
                )
                cont.resume(result.text) }
            .addOnFailureListener { e -> cont.resumeWithException(e) }
        Log.d("MHN-timing", " MLKit.detectText end: ${System.currentTimeMillis() - t0}ms")

    }
}