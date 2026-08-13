package com.newsapp.data.api

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

    private val apiKeys = listOf(
        "AIzaSyBBYmsxS49GvKbx" + "KTvUMxL5WIBvJL6mUHU"
    )

    private var currentKeyIndex = 0

    private fun getNextKey(): Pair<String, Int> {
        val index = currentKeyIndex % apiKeys.size
        val key = apiKeys[index].replace(Regex("\\s+"), "")
        val keyNumber = index + 1
        currentKeyIndex++
        return Pair(key, keyNumber)
    }

    suspend fun processAllNewsWithAi(
        newsList: List<NewsItem>,
        onItemProcessed: (NewsItem) -> Unit
    ) {
        val total = newsList.size
        LogManager.log("AI_START", "Обробка $total новин (5 новин/хв)")

        newsList.forEachIndexed { index, item ->
            LogManager.log("AI_QUEUE", "[${index + 1}/$total] Обробка...")

            val cleanTitle = item.title.replace("\"", "'").replace("\n", " ")
            val cleanDesc = item.description.replace("\"", "'").replace("\n", " ")
            val prompt = "Переклади українською та зроби короткий рерайт новини для Telegram:\nЗаголовок: $cleanTitle\nТекст: $cleanDesc"

            var translatedText: String? = null
            var attempts = 0

            while (translatedText == null && attempts < apiKeys.size) {
                val (apiKey, keyNum) = getNextKey()
                translatedText = callGeminiApi(prompt, apiKey, keyNum)
                if (translatedText == null) {
                    attempts++
                    delay(1000)
                }
            }

            val finalItem = if (!translatedText.isNullOrEmpty()) {
                val parts = translatedText.split("\n", limit = 2)
                val newTitle = parts.getOrNull(0)?.replace(Regex("^[#*\\s]+"), "")?.trim() ?: item.title
                val newDesc = parts.getOrNull(1)?.trim() ?: translatedText
                item.copy(title = newTitle, description = newDesc, status = "Готово")
            } else {
                LogManager.log("AI_FALLBACK", "ШІ недоступний. Використовуємо оригінальний текст.")
                item.copy(status = "Готово")
            }

            onItemProcessed(finalItem)
            if (index < total - 1) delay(12000)
        }
    }

    private suspend fun callGeminiApi(prompt: String, apiKey: String, keyNum: Int): String? {
        return try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"

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
