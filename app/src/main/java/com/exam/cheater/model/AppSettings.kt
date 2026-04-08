package com.exam.cheater.model

data class AppSettings(
    val intervalSeconds: Int = 5,
    val apiKey: String = "",
    val baseUrl: String = "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
    val model: String = "doubao-1.5-vision-pro-32k-250115",
    val overlayX: Int = 0,
    val overlayY: Int = 0,
    val overlayWidth: Int = 1080,
    val overlayHeight: Int = 220,
    val fontSizeSp: Float = 20f,
    val textColorHex: String = "#00FF00",
    val textAlpha: Float = 1f,
    val borderEnabled: Boolean = true,
    val borderColorHex: String = "#00FF00",
    val borderWidthDp: Float = 2f,
    val borderAlpha: Float = 0.9f
)
