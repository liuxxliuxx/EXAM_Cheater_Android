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
            return "请先在控制台配置 API Key"
        }

        val base64 = bitmapToBase64(bitmap)
        val prompt = buildPrompt()

        val bodyJson = JSONObject().apply {
            put("model", settings.model)
            put("temperature", 0.1)
            put("messages", JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("role", "system")
                        put("content", "你是选择题答题助手，只输出题号和答案。")
                    }
                )
                put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", JSONArray().apply {
                            put(
                                JSONObject().apply {
                                    put("type", "text")
                                    put("text", prompt)
                                }
                            )
                            put(
                                JSONObject().apply {
                                    put("type", "image_url")
                                    put("image_url", JSONObject().apply {
                                        put("url", "data:image/jpeg;base64,$base64")
                                    })
                                }
                            )
                        })
                    }
                )
            })
        }

        return try {
            val request = Request.Builder()
                .url(settings.baseUrl)
                .addHeader("Authorization", "Bearer ${settings.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(bodyJson.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return "模型请求失败: HTTP ${response.code}"
                }
                val raw = response.body?.string().orEmpty()
                parseModelText(raw)
            }
        } catch (e: Exception) {
            "模型请求异常: ${e.message ?: "unknown"}"
        }
    }

    private fun buildPrompt(): String {
        return """
现在我有一张截图，上面有很多道选择题。
请仔细分析并直接给出答案，不要有任何解析文字。
输出格式严格为“题号:答案”。
如果有多道题，每道题一行，例如：
3:A
4:B
对于没有题号的题目、题干或选项不完整的题目，直接忽略。
        """.trimIndent()
    }

    private fun parseModelText(rawJson: String): String {
        return try {
            val root = JSONObject(rawJson)
            val choices = root.optJSONArray("choices")
            val message = choices?.optJSONObject(0)?.optJSONObject("message")
            val contentNode = message?.opt("content")
            val content = when (contentNode) {
                is String -> contentNode
                is JSONArray -> {
                    val texts = mutableListOf<String>()
                    for (i in 0 until contentNode.length()) {
                        val item = contentNode.optJSONObject(i)
                        val text = item?.optString("text").orEmpty()
                        if (text.isNotBlank()) texts += text
                    }
                    texts.joinToString("\n")
                }
                else -> ""
            }
            sanitizeAnswer(content)
        } catch (_: Exception) {
            "模型返回解析失败"
        }
    }

    private fun sanitizeAnswer(raw: String): String {
        val cleaned = raw
            .replace("```", "")
            .replace("答案", "")
            .trim()

        if (cleaned.isBlank()) return "暂无可识别完整题目"

        val regex = Regex("""(?m)(\\d+)\\s*[:：]\\s*([A-Z]+)""", RegexOption.IGNORE_CASE)
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
