package com.newsapp.data.api

import com.newsapp.BuildConfig
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.json.JSONObject
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger

object AiRewriter {
    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
    }

    private val apiKeys: List<String> = BuildConfig.GEMINI_KEYS
        .split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }

    private val currentKeyIndex = AtomicInteger(0)

    fun init() {
        // Метод ініціалізації при потребі
    }

    fun isGloballyBlocked(): Boolean = false

    fun getBlockTimeFormatted(): String = ""

    private fun getNextKey(): String {
        if (apiKeys.isEmpty()) return ""
        val index = currentKeyIndex.getAndIncrement() % apiKeys.size
        return apiKeys[Math.abs(index)]
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

        if (apiKeys.isEmpty()) {
            return "Помилка: API ключі не знайдені в конфігурації збірки."
        }

        for (attempt in apiKeys.indices) {
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
                // Спробувати наступний ключ
            }
        }

        return "Помилка при генерації через Gemini API."
    }

    suspend fun translateFullArticle(title: String, content: String): String {
        return rewriteNews(title, content)
    }

    suspend fun <T> processAllNewsWithAi(
        items: List<T>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onItemProcessed: (T) -> Unit = {}
    ) {
        // Заглушка обробки списку для сумісності з ViewModel та Worker
    }
}
