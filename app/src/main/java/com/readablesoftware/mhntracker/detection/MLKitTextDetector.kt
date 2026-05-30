package com.readablesoftware.mhntracker.detection

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class MlKitTextDetector : TextDetector {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override fun detectText(bitmap: Bitmap): String {
        val image = InputImage.fromBitmap(bitmap, 0)
        return Tasks.await(recognizer.process(image)).text
    }
}