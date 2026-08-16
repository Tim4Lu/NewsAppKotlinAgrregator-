package com.newsapp.data.api

import android.content.Context
import com.newsapp.BuildConfig
import com.newsapp.data.LogManager
import com.newsapp.model.NewsItem
import com.newsapp.service.NewsProcessingService
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.endpoint
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

class AiRewriter {

    private val client = HttpClient(CIO) {
        expectSuccess = false
        engine {
            requestTimeout = 60_000
            endpoint {
                connectTimeout = 60_000
                socketTimeout = 60_000
            }
        }
    }

    private val apiKeys: List<String>
        get() = BuildConfig.GEMINI_KEYS
            .replace("\"", "")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private var currentKeyIndex = 0
    private var lastRequestTimestamp = 0L

    private fun getActiveKey(): Pair<String, Int> {
        val keys = apiKeys
        if (keys.isEmpty()) return Pair("", 0)
        val index = currentKeyIndex % keys.size
        return Pair(keys[index], index + 1)
    }

    private fun switchToNextKey() {
        val keys = apiKeys
        if (keys.isNotEmpty()) {
            currentKeyIndex = (currentKeyIndex + 1) % keys.size
        }
    }

    suspend fun processAllNewsWithAi(
        newsList: List<NewsItem>,
        context: Context? = null,
        onItemProcessed: (NewsItem) -> Unit
    ) {
        context?.let { NewsProcessingService.start(it) }

        try {
            val total = newsList.size
            val keysCount = apiKeys.size
            LogManager.log("AI_START", "Обробка $total новин (доступно ключів: $keysCount)")

            if (keysCount == 0) {
                LogManager.log("AI_ERR", "УВАГА: Масив ключів GEMINI_KEYS порожній!")
            }

            newsList.forEachIndexed { index, item ->
                try {
                    LogManager.log("AI_QUEUE", "[${index + 1}/$total] Обробка: ${item.title.take(30)}...")

                    val cleanTitle = item.title.replace("\"", "'").replace("\n", " ")
                    val cleanDesc = item.description.replace("\"", "'").replace("\n", " ")

                    val prompt = """
                        Ти — науковий редактор каналу "Наука кожного дня".
                        Переклади та адаптуй новину українською мовою для Telegram.

                        СУВОРІ ПРАВИЛА:
                        1. КАТЕГОРИЧНО ЗАБОРОНЕНО використовувати подвійні зірочки ** (жодного жирного тексту).
                        2. НЕ ДУБЛЮЙ вступні фрази ("Ось адаптована новина" тощо). Починай ОДРАЗУ з заголовочного емодзі.
                        3. Для назв, термінів та цитат використовуй тільки кутові лапки « ».

                        ШАБЛОН:
                        🚀 $cleanTitle 🚀

                        [Короткий тизер на 1-2 речення про суть]
                        • [Перший важливий факт]
                        • [Другий факт]
                        • [Третій факт]
                        • Джерело: ${item.source}

                        Текст новини:
                        $cleanDesc
                    """.trimIndent()

                    var translatedText: String? = null
                    var attempts = 0
                    val keys = apiKeys

                    while (translatedText == null && attempts < keys.size) {
                        val (apiKey, keyNum) = getActiveKey()

                        translatedText = callGeminiApi(prompt, apiKey, keyNum, "gemini-3.6-flash")
                        if (translatedText == null) {
                            translatedText = callGeminiApi(prompt, apiKey, keyNum, "gemini-1.5-flash")
                        }

                        if (translatedText == null) {
                            switchToNextKey()
                            attempts++
                        }
                    }

                    val finalItem = if (!translatedText.isNullOrEmpty()) {
                        val cleanResult = translatedText.replace("**", "")
                        val parts = cleanResult.split("\n", limit = 2)
                        val newTitle = parts.getOrNull(0)?.replace(Regex("^[#*\\s]+"), "")?.trim() ?: item.title
                        val newDesc = parts.getOrNull(1)?.trim() ?: cleanResult

                        item.copy(
                            title = newTitle,
                            description = newDesc,
                            telegramCaption = cleanResult,
                            status = "Готово"
                        )
                    } else {
                        LogManager.log("AI_FALLBACK", "ШІ недоступний (усі ключі вичерпано). Використовуємо оригінал.")
                        val caption = "🚀 ${item.title}\n\n${item.description}\n\n• Джерело: ${item.source}"
                        item.copy(telegramCaption = caption, status = "Готово")
                    }

                    onItemProcessed(finalItem)
                } catch (e: Exception) {
                    LogManager.log("AI_CRITICAL", "Збій обробки новини: ${e.message}")
                }
            }
        } finally {
            context?.let { NewsProcessingService.stop(it) }
        }
    }

    private suspend fun callGeminiApi(prompt: String, apiKey: String, keyNum: Int, modelName: String): String? {
        val now = System.currentTimeMillis()
        val timeSinceLastRequest = now - lastRequestTimestamp
        if (timeSinceLastRequest < 12_000) {
            val waitTime = 12_000 - timeSinceLastRequest
            LogManager.log("AI_RATE", "Пауза ${waitTime / 1000} сек для дотримання ліміту (1 запит / 12 сек)...")
            delay(waitTime)
        }
        lastRequestTimestamp = System.currentTimeMillis()

        return try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"

            val jsonBody = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", prompt) })
                        })
                    })
                })
            }

            val response = client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(jsonBody.toString())
            }

            val responseText = response.bodyAsText()

            if (response.status.value == 200) {
                val json = JSONObject(responseText)
                val candidates = json.optJSONArray("candidates")
                val firstCandidate = candidates?.optJSONObject(0)
                val content = firstCandidate?.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                val resultText = parts?.optJSONObject(0)?.optString("text")

                if (!resultText.isNullOrEmpty()) {
                    LogManager.log("Gemini_OK", "Успіх ($modelName, ключ #$keyNum)")
                    resultText
                } else {
                    val finishReason = firstCandidate?.optString("finishReason") ?: "UNKNOWN"
                    LogManager.log("AI_ERR", "Порожня відповідь $modelName (ключ #$keyNum, причина: $finishReason)")
                    null
                }
            } else if (response.status.value == 503) {
                LogManager.log("AI_ERR", "Сервер $modelName перевантажений (503). Спробуємо резерв...")
                delay(2000)
                null
            } else {
                LogManager.log("AI_ERR", "Помилка $modelName (ключ #$keyNum, код ${response.status.value}): ${responseText.take(120)}")
                null
            }
        } catch (e: Exception) {
            LogManager.log("AI_ERR", "Помилка з'єднання ($modelName, ключ #$keyNum): ${e.message ?: "null"}")
            null
        }
    }
}
