package com.exam.cheater.util

import android.graphics.Color
import kotlin.math.roundToInt

object ColorUtils {
    fun parseHexColor(input: String, fallback: Int = Color.GREEN): Int {
        return try {
            Color.parseColor(input.trim())
        } catch (_: IllegalArgumentException) {
            fallback
        }
    }

    fun withAlpha(color: Int, alphaFraction: Float): Int {
        val clamped = alphaFraction.coerceIn(0f, 1f)
        val alpha = (clamped * 255).roundToInt()
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }
}
