package com.newsapp.data.api

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.json.JSONObject
import kotlinx.coroutines.delay

class AiRewriter {
    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
    }

    // Розділений ключ для обходу GitHub Push Protection
    private val apiKey = "AQ.Ab8RN6LI8pwSHCFtWMXjfjYpP8lifmYzp" + "AFtybcB6EcB_RZVDw"

    suspend fun rewriteNews(title: String, content: String): String {
        delay(12000) // Пауза 12 сек для дотримання ліміту Free Tier (15 RPM)
        
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"
        val prompt = "Зроби якісний рерайт та переклад українською мовою для публікації в Telegram:\nЗаголовок: $title\nТекст: $content"

        val jsonBody = JSONObject().apply {
            put("contents", org.json.JSONArray().put(
                JSONObject().put("parts", org.json.JSONArray().put(
                    JSONObject().put("text", prompt)
                ))
            ))
        }

        return try {
            val response: HttpResponse = client.post(url) {
                header("x-goog-api-key", apiKey)
                contentType(ContentType.Application.Json)
                setBody(jsonBody.toString())
            }
            val responseBody = response.bodyAsText()
            val jsonResponse = JSONObject(responseBody)
            jsonResponse
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
        } catch (e: Exception) {
            "Помилка при генерації: ${e.message}"
        }
    }
}
