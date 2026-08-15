package com.newsapp.data.api

import com.newsapp.BuildConfig
import com.newsapp.data.LogManager
import com.newsapp.model.NewsItem
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.endpoint
import io.ktor.client.request.header
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
        engine {
            requestTimeout = 30_000
            endpoint {
                connectTimeout = 30_000
                socketTimeout = 30_000
            }
        }
    }

    private val apiKeys: List<String>
        get() = BuildConfig.GEMINI_KEYS.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    private var currentKeyIndex = 0

    private fun getActiveKey(): Pair<String, Int> {
        if (apiKeys.isEmpty()) return Pair("", 0)
        val key = apiKeys[currentKeyIndex % apiKeys.size]
        return Pair(key, (currentKeyIndex % apiKeys.size) + 1)
    }

    private fun switchToNextKey() {
        if (apiKeys.isNotEmpty()) {
            currentKeyIndex = (currentKeyIndex + 1) % apiKeys.size
        }
    }

    suspend fun processAllNewsWithAi(
        newsList: List<NewsItem>,
        onItemProcessed: (NewsItem) -> Unit
    ) {
        val total = newsList.size
        LogManager.log("AI_START", "Обробка $total новин через Gemini 3.6 Flash")

        newsList.forEachIndexed { index, item ->
            LogManager.log("AI_QUEUE", "[${index + 1}/$total] Обробка...")

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

            while (translatedText == null && attempts < apiKeys.size) {
                val (apiKey, keyNum) = getActiveKey()
                translatedText = callGeminiApi(prompt, apiKey, keyNum)
                
                if (translatedText == null) {
                    switchToNextKey()
                    attempts++
                    delay(12000)
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
                LogManager.log("AI_FALLBACK", "ШІ недоступний. Використовуємо оригінальний текст.")
                val caption = "🚀 ${item.title}\n\n${item.description}\n\n• Джерело: ${item.source}"
                item.copy(telegramCaption = caption, status = "Готово")
            }

            onItemProcessed(finalItem)
            if (index < total - 1) delay(12000)
        }
    }

    suspend fun processFullArticleWithAi(item: NewsItem): NewsItem {
        LogManager.log("AI_FULL", "Генерація повної статті для: ${item.title}")
        val cleanTitle = item.title.replace("\"", "'").replace("\n", " ")
        val cleanDesc = item.description.replace("\"", "'").replace("\n", " ")
        
        val prompt = """
            Переклади та детально розпиши наукову статтю українською мовою.
            СУВОРІ ПРАВИЛА:
            1. КАТЕГОРИЧНО ЗАБОРОНЕНО використовувати подвійні зірочки ** (жодного жирного тексту).
            2. НЕ ДУБЛЮЙ вступні фрази ("Ось адаптована новина" тощо).
            3. Для назв та термінів використовуй кутові лапки « ».

            Заголовок: $cleanTitle
            Джерело: ${item.source}
            Зміст статті:
            $cleanDesc
        """.trimIndent()

        var translatedText: String? = null
        var attempts = 0

        while (translatedText == null && attempts < apiKeys.size) {
            val (apiKey, keyNum) = getActiveKey()
            translatedText = callGeminiApi(prompt, apiKey, keyNum)
            
            if (translatedText == null) {
                switchToNextKey()
                attempts++
                delay(12000)
            }
        }

        return if (!translatedText.isNullOrEmpty()) {
            val cleanResult = translatedText.replace("**", "")
            item.copy(
                description = cleanResult,
                status = "Повна стаття"
            )
        } else {
            LogManager.log("AI_ERR", "Не вдалося згенерувати повну статтю.")
            item
        }
    }

    private suspend fun callGeminiApi(prompt: String, apiKey: String, keyNum: Int): String? {
        return try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent"

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
                header("x-goog-api-key", apiKey)
                setBody(jsonBody.toString())
            }

            if (response.status.value == 200) {
                val responseText = response.bodyAsText()
                val resultText = JSONObject(responseText)
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                LogManager.log("Gemini_OK", "Успіх Gemini (ключ #$keyNum)")
                resultText
            } else {
                LogManager.log("AI_ERR", "Помилка Gemini API (код ${response.status.value})")
                null
            }
        } catch (e: Exception) {
            LogManager.log("AI_ERR", "Помилка з'єднання: ${e.message}")
            null
        }
    }
}
