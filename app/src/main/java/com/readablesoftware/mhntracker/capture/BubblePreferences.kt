package com.readablesoftware.mhntracker.capture

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.graphics.Point
import androidx.core.content.edit

class BubblePreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "bubble_prefs"
        private const val PREF_X = "bubble_x"
        private const val PREF_Y = "bubble_y"
        private const val PREF_ENABLED = "bubble_enabled"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    var isEnabled: Boolean
        get() = prefs.getBoolean(PREF_ENABLED, false)
        set(value) {
            prefs.edit { putBoolean(PREF_ENABLED, value) }
        }

    var position: Point?
        get() =
            if (prefs.contains(PREF_X) && prefs.contains(PREF_Y))
                Point(prefs.getInt(PREF_X, 0), prefs.getInt(PREF_Y, 0))
            else null
        set(value) {
            prefs.edit {
                if (value == null) {
                    remove(PREF_X)
                    remove(PREF_Y)
                } else {
                    putInt(PREF_X, value.x)
                    putInt(PREF_Y, value.y)
                }
            }
        }
}