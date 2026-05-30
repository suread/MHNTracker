package com.readablesoftware.mhntracker.detection

import android.graphics.Bitmap

interface TextDetector {
    fun detectText(bitmap: Bitmap): String
}