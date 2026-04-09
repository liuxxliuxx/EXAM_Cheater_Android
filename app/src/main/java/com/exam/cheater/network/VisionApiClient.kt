package com.exam.cheater.network

import android.graphics.Bitmap
import android.util.Base64
import com.exam.cheater.model.AppSettings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

object VisionApiClient {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(80, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun requestAnswers(bitmap: Bitmap, settings: AppSettings): String {
        if (settings.apiKey.isBlank()) {
            return "Please configure API Key first."
        }

        val base64 = bitmapToBase64(bitmap)
        val prompt = buildPrompt()
        val requestUrl = resolveChatCompletionsUrl(settings.baseUrl)
        val bodyJson = buildChatCompletionsBody(base64, prompt, settings.model)

        return try {
            val request = Request.Builder()
                .url(requestUrl)
                .addHeader("Authorization", "Bearer ${settings.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(bodyJson.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return formatHttpError(response.code, raw, requestUrl)
                }
                parseModelText(raw)
            }
        } catch (e: Exception) {
            "Model request exception: ${e.message ?: "unknown"}"
        }
    }

    private fun buildChatCompletionsBody(base64Image: String, prompt: String, model: String): JSONObject {
        return JSONObject().apply {
            put("model", model)
            put("temperature", 0.1)
            put(
                "messages",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("role", "system")
                            put("content", "You answer multiple-choice questions from screenshots. Output only questionNo:Answer.")
                        }
                    )
                    put(
                        JSONObject().apply {
                            put("role", "user")
                            put(
                                "content",
                                JSONArray().apply {
                                    put(
                                        JSONObject().apply {
                                            put("type", "text")
                                            put("text", prompt)
                                        }
                                    )
                                    put(
                                        JSONObject().apply {
                                            put("type", "image_url")
                                            put(
                                                "image_url",
                                                JSONObject().apply {
                                                    put("url", "data:image/jpeg;base64,$base64Image")
                                                }
                                            )
                                        }
                                    )
                                }
                            )
                        }
                    )
                }
            )
        }
    }

    private fun resolveChatCompletionsUrl(rawBaseUrl: String): String {
        val trimmed = rawBaseUrl.trim().trimEnd('/')
        if (trimmed.isBlank()) {
            return "https://ark.cn-beijing.volces.com/api/v3/chat/completions"
        }
        return when {
            trimmed.endsWith("/chat/completions", ignoreCase = true) -> trimmed
            trimmed.endsWith("/api/v3", ignoreCase = true) -> "$trimmed/chat/completions"
            trimmed.endsWith("/responses", ignoreCase = true) -> trimmed.removeSuffix("/responses") + "/chat/completions"
            else -> trimmed
        }
    }

    private fun formatHttpError(code: Int, rawBody: String, requestUrl: String): String {
        val serverMessage = extractErrorMessage(rawBody)
        val prefix = "Model request failed: HTTP $code"
        val detail = if (serverMessage.isNotBlank()) " - $serverMessage" else ""
        val tip = when {
            code == 400 && serverMessage.contains("image", ignoreCase = true) ->
                "\nTip: current model may not support image input. Use a vision model."
            code == 400 && serverMessage.contains("model", ignoreCase = true) ->
                "\nTip: check model name and whether your account has access."
            code == 404 ->
                "\nTip: check Base URL. Current request URL: $requestUrl"
            else -> ""
        }
        return (prefix + detail + tip).take(600)
    }

    private fun extractErrorMessage(rawJson: String): String {
        if (rawJson.isBlank()) return ""
        return try {
            val root = JSONObject(rawJson)
            val errorObj = root.optJSONObject("error")
            when {
                errorObj != null -> errorObj.optString("message").ifBlank { errorObj.toString() }
                root.optString("message").isNotBlank() -> root.optString("message")
                root.optString("error_msg").isNotBlank() -> root.optString("error_msg")
                else -> rawJson.take(240)
            }
        } catch (_: Exception) {
            rawJson.take(240)
        }
    }

    private fun buildPrompt(): String {
        return """
Analyze the screenshot and note that there are two types of questions: true/false questions and multiple-choice questions. Output only answers in `questionNo:Answer` format, one per line.
If question number/options are incomplete, skip that question.
Example:
3:A
4:B
5:√
        """.trimIndent()
    }

    private fun parseModelText(rawJson: String): String {
        return try {
            val root = JSONObject(rawJson)

            val choices = root.optJSONArray("choices")
            val choiceMessage = choices
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.opt("content")
            val chatText = extractContentText(choiceMessage)
            if (chatText.isNotBlank()) return sanitizeAnswer(chatText)

            val outputText = root.optString("output_text")
            if (outputText.isNotBlank()) return sanitizeAnswer(outputText)

            val outputArray = root.optJSONArray("output")
            val responseText = extractResponsesOutputText(outputArray)
            if (responseText.isNotBlank()) return sanitizeAnswer(responseText)

            "Model response parsed but no text content found."
        } catch (_: Exception) {
            "Failed to parse model response."
        }
    }

    private fun extractContentText(contentNode: Any?): String {
        return when (contentNode) {
            is String -> contentNode
            is JSONArray -> {
                val texts = mutableListOf<String>()
                for (i in 0 until contentNode.length()) {
                    val item = contentNode.optJSONObject(i) ?: continue
                    val text = item.optString("text")
                    if (text.isNotBlank()) texts += text
                }
                texts.joinToString("\n")
            }
            else -> ""
        }
    }

    private fun extractResponsesOutputText(outputArray: JSONArray?): String {
        if (outputArray == null) return ""
        val texts = mutableListOf<String>()
        for (i in 0 until outputArray.length()) {
            val item = outputArray.optJSONObject(i) ?: continue
            val contentArray = item.optJSONArray("content") ?: continue
            for (j in 0 until contentArray.length()) {
                val content = contentArray.optJSONObject(j) ?: continue
                val text = content.optString("text")
                if (text.isNotBlank()) texts += text
            }
        }
        return texts.joinToString("\n")
    }

    private fun sanitizeAnswer(raw: String): String {
        val cleaned = raw
            .replace("```", "")
            .replace("答案", "")
            .trim()

        if (cleaned.isBlank()) return "No complete question recognized."

        val regex = Regex("""(?m)(\d+)\s*[:：]\s*([A-Za-z]+)""")
        val lines = regex.findAll(cleaned)
            .map { "${it.groupValues[1]}:${it.groupValues[2].uppercase()}" }
            .toList()

        return if (lines.isNotEmpty()) lines.joinToString("\n") else cleaned
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val resized = resizeIfNeeded(bitmap, 1600)
        val baos = ByteArrayOutputStream()
        resized.compress(Bitmap.CompressFormat.JPEG, 85, baos)
        if (resized !== bitmap) {
            resized.recycle()
        }
        val bytes = baos.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun resizeIfNeeded(src: Bitmap, maxSide: Int): Bitmap {
        val width = src.width
        val height = src.height
        val longest = maxOf(width, height)
        if (longest <= maxSide) return src

        val ratio = maxSide.toFloat() / longest.toFloat()
        val newW = (width * ratio).toInt().coerceAtLeast(1)
        val newH = (height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, newW, newH, true)
    }
}
