package com.airgesture

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val NAME = "air_gesture"
    const val KEY_MODE = "mode"
    const val KEY_SENS = "sensitivity"
    const val KEY_TAP = "tap_enabled"

    const val MODE_PAGING = 0
    const val MODE_POINTER = 1

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
