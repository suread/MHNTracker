package com.readablesoftware.mhntracker.detection

import android.graphics.Bitmap

interface TextDetector {
    suspend fun detectText(bitmap: Bitmap): String
}