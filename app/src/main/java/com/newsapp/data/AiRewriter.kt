package com.newsapp.data

import android.util.Log
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AiRewriter {

    companion object {
        private const val TAG = "AiRewriterTag"
        private const val GROQ_API_KEY = "ТВІЙ_GROQ_API_KEY"
        private const val GEMINI_API_KEY = "ТВІЙ_GEMINI_API_KEY"
    }

    private val client = HttpClient(CIO)

    // Якщо true — Groq вимкнено через вичерпані ліміти, працює лише Gemini
    @Volatile
    private var isGroqDisabled = false

    suspend fun rewrite(text: String, targetLanguage: String): String? {
        return withContext(Dispatchers.IO) {
            // 1. Якщо Groq не вимкнено — пробуємо спочатку Groq
            if (!isGroqDisabled) {
                try {
                    Log.d(TAG, "AI_REQUEST: Пробуємо переклад через Groq...")
                    val result = callGroqApi(text, targetLanguage)
                    if (result != null) return@withContext result
                } catch (e: Exception) {
                    Log.e(TAG, "GROQ_ERROR: Помилка або закінчилися ліміти Groq. Вимикаємо Groq, переходимо на Gemini!", e)
                    isGroqDisabled = true
                    throw LimitExceededException("Groq limit reached")
                }
            }

            // 2. Якщо Groq вимкнено або повернув помилку — працюємо виключно через Gemini
            return@withContext try {
                Log.d(TAG, "AI_REQUEST: Працює виключно Gemini...")
                callGeminiApi(text, targetLanguage)
            } catch (e: Exception) {
                Log.e(TAG, "GEMINI_ERROR: Помилка Gemini API", e)
                if (e.message?.contains("429") == true || e.message?.contains("limit") == true) {
                    throw LimitExceededException("Gemini limit reached")
                }
                null
            }
        }
    }

    private suspend fun callGroqApi(text: String, targetLanguage: String): String? {
        val response: HttpResponse = client.post("https://api.groq.com/openai/v1/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer $GROQ_API_KEY")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "model": "llama-3.3-70b-versatile",
                  "messages": [
                    {"role": "system", "content": "You are a news translator. Translate and rewrite strictly in $targetLanguage."},
                    {"role": "user", "content": "$text"}
                  ]
                }
                """.trimIndent()
            )
        }

        if (response.status == HttpStatusCode.TooManyRequests || response.status.value == 429) {
            throw LimitExceededException("Groq 429 Too Many Requests")
        }

        return if (response.status == HttpStatusCode.OK) {
            response.bodyAsText()
        } else {
            null
        }
    }

    private suspend fun callGeminiApi(text: String, targetLanguage: String): String? {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$GEMINI_API_KEY"
        val response: HttpResponse = client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "contents": [{
                    "parts":[{"text": "Translate and rewrite the following news strictly in $targetLanguage:\n\n$text"}]
                  }]
                }
                """.trimIndent()
            )
        }

        if (response.status == HttpStatusCode.TooManyRequests || response.status.value == 429) {
            throw LimitExceededException("Gemini 429 Too Many Requests")
        }

        return if (response.status == HttpStatusCode.OK) {
            response.bodyAsText()
        } else {
            null
        }
    }
}

class LimitExceededException(message: String) : Exception(message)
