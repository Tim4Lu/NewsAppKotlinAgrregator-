package com.newsapp.data.api

import android.content.Context
import com.newsapp.BuildConfig
import com.newsapp.model.NewsItem
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.json.JSONObject
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger

class AiRewriter {
    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
    }

    private val apiKeys: List<String>
        get() = BuildConfig.GEMINI_KEYS
            .replace("\"", "")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private val currentKeyIndex = AtomicInteger(0)

    private fun getNextKey(): String {
        val keys = apiKeys
        if (keys.isEmpty()) return ""
        val index = currentKeyIndex.getAndIncrement() % keys.size
        return keys[Math.abs(index)]
    }

    suspend fun rewriteNews(title: String, content: String): String {
        delay(12000)

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"
        val prompt = "Зроби якісний рерайт та переклад українською мовою для публікації в Telegram:\nЗаголовок: $title\nТекст: $content"

        val jsonBody = JSONObject().apply {
            put("contents", org.json.JSONArray().put(
                JSONObject().put("parts", org.json.JSONArray().put(
                    JSONObject().put("text", prompt)
                ))
            ))
        }

        val keys = apiKeys
        if (keys.isEmpty()) {
            return "Помилка: API ключі не знайдені в конфігурації збірки."
        }

        for (attempt in keys.indices) {
            val apiKey = getNextKey()
            try {
                val response: HttpResponse = client.post(url) {
                    header("x-goog-api-key", apiKey)
                    contentType(ContentType.Application.Json)
                    setBody(jsonBody.toString())
                }

                if (response.status == HttpStatusCode.OK) {
                    val responseBody = response.bodyAsText()
                    val jsonResponse = JSONObject(responseBody)
                    return jsonResponse
                        .getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")
                }
            } catch (e: Exception) {
                // Ігноруємо та пробуємо наступний ключ
            }
        }

        return "Помилка при генерації через Gemini API."
    }

    // Метод для обробки суцільного тексту або об'єкта
    suspend fun translateFullArticle(item: NewsItem): String {
        return rewriteNews(item.title, item.description)
    }

    suspend fun translateFullArticle(title: String, content: String): String {
        return rewriteNews(title, content)
    }

    companion object {
        private val sharedRewriter = AiRewriter()

        // 1. Приймаємо Context для сумісності з NewsWorker і NewsViewModel
        fun init(context: Context? = null) {}

        fun isGloballyBlocked(): Boolean = false

        fun getBlockTimeFormatted(): String = ""

        // 2. Метод, якого вимагав ScriptGenerator.kt
        suspend fun callGeminiApi(prompt: String, model: String = "gemini-1.5-flash"): String? {
            return sharedRewriter.rewriteNews("Сценарій", prompt)
        }

        // 3. Підтримка виклику processAllNewsWithAi з 2 та 3 параметрами
        suspend fun processAllNewsWithAi(
            items: List<NewsItem>,
            context: Context,
            onItemProcessed: (NewsItem) -> Unit
        ) {
            processAllNewsWithAi(items, { _, _ -> }, onItemProcessed)
        }

        suspend fun processAllNewsWithAi(
            items: List<NewsItem>,
            onProgress: (Int, Int) -> Unit = { _, _ -> },
            onItemProcessed: (NewsItem) -> Unit = {}
        ) {
            items.forEachIndexed { index, item ->
                val newDesc = sharedRewriter.rewriteNews(item.title, item.description)
                val updatedItem = item.copy(
                    description = newDesc,
                    status = "Готово",
                    telegramCaption = "🚀 <b>${item.title}</b> 🚀\n\n$newDesc\n\n• <b>Джерело:</b> ${item.source}"
                )
                onProgress(index + 1, items.size)
                onItemProcessed(updatedItem)
            }
        }
    }
}
