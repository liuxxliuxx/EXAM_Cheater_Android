package com.exam.cheater.data

import android.content.Context
import com.exam.cheater.model.AppSettings

object SettingsStore {
    private const val PREF_NAME = "exam_cheater_settings"

    private const val KEY_INTERVAL = "interval"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_MODEL = "model"
    private const val KEY_OVERLAY_X = "overlay_x"
    private const val KEY_OVERLAY_Y = "overlay_y"
    private const val KEY_OVERLAY_WIDTH = "overlay_width"
    private const val KEY_OVERLAY_HEIGHT = "overlay_height"
    private const val KEY_FONT_SIZE = "font_size"
    private const val KEY_TEXT_COLOR = "text_color"
    private const val KEY_TEXT_ALPHA = "text_alpha"
    private const val KEY_BORDER_ENABLED = "border_enabled"
    private const val KEY_BORDER_COLOR = "border_color"
    private const val KEY_BORDER_WIDTH = "border_width"
    private const val KEY_BORDER_ALPHA = "border_alpha"

    fun load(context: Context): AppSettings {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return AppSettings(
            intervalSeconds = prefs.getInt(KEY_INTERVAL, 5).coerceIn(1, 120),
            apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
            baseUrl = prefs.getString(KEY_BASE_URL, AppSettings().baseUrl) ?: AppSettings().baseUrl,
            model = prefs.getString(KEY_MODEL, AppSettings().model) ?: AppSettings().model,
            overlayX = prefs.getInt(KEY_OVERLAY_X, 0),
            overlayY = prefs.getInt(KEY_OVERLAY_Y, 0),
            overlayWidth = prefs.getInt(KEY_OVERLAY_WIDTH, 1080).coerceAtLeast(100),
            overlayHeight = prefs.getInt(KEY_OVERLAY_HEIGHT, 220).coerceAtLeast(80),
            fontSizeSp = prefs.getFloat(KEY_FONT_SIZE, 20f).coerceIn(8f, 96f),
            textColorHex = prefs.getString(KEY_TEXT_COLOR, "#00FF00") ?: "#00FF00",
            textAlpha = prefs.getFloat(KEY_TEXT_ALPHA, 1f).coerceIn(0f, 1f),
            borderEnabled = prefs.getBoolean(KEY_BORDER_ENABLED, true),
            borderColorHex = prefs.getString(KEY_BORDER_COLOR, "#00FF00") ?: "#00FF00",
            borderWidthDp = prefs.getFloat(KEY_BORDER_WIDTH, 2f).coerceIn(0f, 20f),
            borderAlpha = prefs.getFloat(KEY_BORDER_ALPHA, 0.9f).coerceIn(0f, 1f)
        )
    }

    fun save(context: Context, settings: AppSettings) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_INTERVAL, settings.intervalSeconds.coerceIn(1, 120))
            .putString(KEY_API_KEY, settings.apiKey)
            .putString(KEY_BASE_URL, settings.baseUrl)
            .putString(KEY_MODEL, settings.model)
            .putInt(KEY_OVERLAY_X, settings.overlayX)
            .putInt(KEY_OVERLAY_Y, settings.overlayY)
            .putInt(KEY_OVERLAY_WIDTH, settings.overlayWidth.coerceAtLeast(100))
            .putInt(KEY_OVERLAY_HEIGHT, settings.overlayHeight.coerceAtLeast(80))
            .putFloat(KEY_FONT_SIZE, settings.fontSizeSp.coerceIn(8f, 96f))
            .putString(KEY_TEXT_COLOR, settings.textColorHex)
            .putFloat(KEY_TEXT_ALPHA, settings.textAlpha.coerceIn(0f, 1f))
            .putBoolean(KEY_BORDER_ENABLED, settings.borderEnabled)
            .putString(KEY_BORDER_COLOR, settings.borderColorHex)
            .putFloat(KEY_BORDER_WIDTH, settings.borderWidthDp.coerceIn(0f, 20f))
            .putFloat(KEY_BORDER_ALPHA, settings.borderAlpha.coerceIn(0f, 1f))
            .apply()
    }
}
