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

object AiRewriter {
    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
    }

    // Резервний список з 3-х ключів у разі відсутності BuildConfig
    private val fallbackKeys = listOf(
        "AQ.Ab8RN6J0H2eotoyoxuydNaJWOzqF99c7" + "PfXfqMrY3ZPec_LxNQ",
        "AQ.Ab8RN6IPtDnd1HOk12WQo0wYos-Nqq6F" + "JrMiYe_PzYjRxgRMIw",
        "AQ.Ab8RN6KJadyu7NCoJbKz2GljkJNaBO0C" + "fGCkVNELttu2Nw3Ifw"
    )

    private val apiKeys: List<String>
        get() {
            val fromConfig = BuildConfig.GEMINI_KEYS
                .replace("\"", "")
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            return if (fromConfig.isNotEmpty()) fromConfig else fallbackKeys
        }

    private val currentKeyIndex = AtomicInteger(0)

    fun init(context: Context? = null) {}

    fun isGloballyBlocked(): Boolean = false

    fun getBlockTimeFormatted(): String = ""

    private fun getNextKey(): String {
        val keys = apiKeys
        if (keys.isEmpty()) return ""
        val index = currentKeyIndex.getAndIncrement() % keys.size
        return keys[Math.abs(index)]
    }

    suspend fun callGeminiApi(prompt: String, model: String = "gemini-1.5-flash"): String? {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        val keys = apiKeys
        if (keys.isEmpty()) return null

        val jsonBody = JSONObject().apply {
            put("contents", org.json.JSONArray().put(
                JSONObject().put("parts", org.json.JSONArray().put(
                    JSONObject().put("text", prompt)
                ))
            ))
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
                // Виклик наступного ключа при помилці
            }
        }
        return null
    }

    suspend fun rewriteNews(title: String, content: String): String? {
        delay(12000)
        val prompt = "Зроби якісний рерайт та переклад українською мовою для публікації в Telegram:\nЗаголовок: $title\nТекст: $content"
        return callGeminiApi(prompt)
    }

    suspend fun translateFullArticle(item: NewsItem): String {
        return rewriteNews(item.title, item.description) ?: "Помилка перекладу."
    }

    suspend fun translateFullArticle(title: String, content: String): String {
        return rewriteNews(title, content) ?: "Помилка перекладу."
    }

    suspend fun processAllNewsWithAi(
        items: List<NewsItem>,
        context: Context? = null,
        onItemProcessed: (NewsItem) -> Unit
    ) {
        items.forEach { item ->
            val newDesc = rewriteNews(item.title, item.description)
            if (newDesc != null) { val updatedItem = item.copy(
                description = newDesc,
                status = "Готово",
                telegramCaption = "🚀 <b>${item.title}</b> 🚀\n\n$newDesc\n\n• <b>Джерело:</b> ${item.source}"
            )
            onItemProcessed(updatedItem) } else { onItemProcessed(item.copy(status = "Помилка")) }
        }
    }
}
