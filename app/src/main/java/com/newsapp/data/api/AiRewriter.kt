package com.newsapp.data.api

import android.util.Base64
import android.util.Log
import com.newsapp.BuildConfig
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*

object AiRewriter {
    private const val TAG = "AiRewriter"

    // Декодування ключів із BuildConfig
    private fun decodeKey(base64Str: String): String {
        return try {
            String(Base64.decode(base64Str, Base64.DEFAULT)).trim()
        } catch (e: Exception) {
            ""
        }
    }

    private val GEMINI_KEYS by lazy {
        listOf(decodeKey(BuildConfig.GEMINI_1), decodeKey(BuildConfig.GEMINI_2))
    }
    private val GROQ_KEYS by lazy {
        listOf(decodeKey(BuildConfig.GROQ))
    }
    private val OPENAI_KEYS by lazy {
        listOf(decodeKey(BuildConfig.OPENAI))
    }

    private val client = HttpClient(CIO)

    private const val SYSTEM_PROMPT = """
Ти — професійний редактор популярного українського Telegram-каналу про науки та технології.
Перепиши надану новину за наступною суворою структурою:

📌 [Яскравий та залучаючий заголовок з емодзі]

[Основний зміст новини: 2-3 коротких, динамічних абзаци з ключовими фактами]

💡 **Чому це важливо:** [1-2 речення про значення цієї події чи технології]

Вимоги:
- Мова: українська.
- Стиль: живий, зрозумілий, без складного канцеляризму.
- Використовуй емодзі для покращення читабельності.
- Зберігай фактичну точність оригіналу.
"""

    suspend fun rewriteNews(originalText: String): String {
        Log.d(TAG, "[LOG] Початок ШІ-рерайту новини...")

        val geminiResult = rewriteWithGemini25(originalText)
        if (geminiResult != null) {
            Log.d(TAG, "[LOG] Успішний рерайт через Gemini 2.5 Flash API!")
            return geminiResult
        }

        Log.d(TAG, "[LOG] Gemini 2.5 не відповів, переходимо до Groq API...")
        val groqResult = rewriteWithGroq(originalText)
        if (groqResult != null) {
            Log.d(TAG, "[LOG] Успішний рерайт через Groq API!")
            return groqResult
        }

        Log.d(TAG, "[LOG] Groq не відповів, переходимо до OpenAI API...")
        val openAiResult = rewriteWithOpenAi(originalText)
        if (openAiResult != null) {
            Log.d(TAG, "[LOG] Успішний рерайт через OpenAI API!")
            return openAiResult
        }

        Log.e(TAG, "[LOG] Усі ШІ-сервіси повернули помилку. Повертаємо оригінальний текст.")
        return originalText
    }

    private suspend fun rewriteWithGemini25(text: String): String? {
        val apiKey = GEMINI_KEYS.firstOrNull { it.isNotBlank() } ?: return null
        return try {
            Log.d(TAG, "[LOG] Запит до Gemini 2.5 Flash API...")
            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"

            val fullPrompt = "$SYSTEM_PROMPT\n\nНовина для рерайту:\n$text"
            val jsonBody = """
                {
                    "contents": [{
                        "parts": [{"text": ${escapeJson(fullPrompt)}}]
                    }]
                }
            """.trimIndent()

            val response: HttpResponse = client.post(url) {
                header(HttpHeaders.ContentType, "application/json")
                setBody(jsonBody)
            }

            if (response.status.value == 200) {
                val body = response.bodyAsText()
                extractContentFromGeminiJson(body)
            } else {
                Log.e(TAG, "[LOG] Gemini 2.5 API Помилка: ${response.status.value}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "[LOG] Виняток при запиті до Gemini 2.5: ${e.message}")
            null
        }
    }

    private suspend fun rewriteWithGroq(text: String): String? {
        val apiKey = GROQ_KEYS.firstOrNull { it.isNotBlank() } ?: return null
        return try {
            Log.d(TAG, "[LOG] Запит до Groq API (llama-3.3-70b-versatile)...")
            
            val jsonBody = """
                {
                    "model": "llama-3.3-70b-versatile",
                    "messages": [
                        {"role": "system", "content": ${escapeJson(SYSTEM_PROMPT)}},
                        {"role": "user", "content": ${escapeJson(text)}}
                    ]
                }
            """.trimIndent()

            val response: HttpResponse = client.post("https://api.groq.com/openai/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                header(HttpHeaders.ContentType, "application/json")
                setBody(jsonBody)
            }

            if (response.status.value == 200) {
                val body = response.bodyAsText()
                extractContentFromJson(body)
            } else {
                Log.e(TAG, "[LOG] Groq API Помилка: ${response.status.value}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "[LOG] Виняток при запиті до Groq: ${e.message}")
            null
        }
    }

    private suspend fun rewriteWithOpenAi(text: String): String? {
        val apiKey = OPENAI_KEYS.firstOrNull { it.isNotBlank() } ?: return null
        return try {
            Log.d(TAG, "[LOG] Запит до OpenAI API (gpt-4o-mini)...")
            
            val jsonBody = """
                {
                    "model": "gpt-4o-mini",
                    "messages": [
                        {"role": "system", "content": ${escapeJson(SYSTEM_PROMPT)}},
                        {"role": "user", "content": ${escapeJson(text)}}
                    ]
                }
            """.trimIndent()

            val response: HttpResponse = client.post("https://api.openai.com/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                header(HttpHeaders.ContentType, "application/json")
                setBody(jsonBody)
            }

            if (response.status.value == 200) {
                val body = response.bodyAsText()
                extractContentFromJson(body)
            } else {
                Log.e(TAG, "[LOG] OpenAI API Помилка: ${response.status.value}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "[LOG] Виняток при запиті до OpenAI: ${e.message}")
            null
        }
    }

    private fun escapeJson(text: String): String {
        return "\"" + text.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "") + "\""
    }

    private fun extractContentFromJson(json: String): String? {
        val marker = "\"content\":"
        val index = json.indexOf(marker)
        if (index == -1) return null
        val start = json.indexOf("\"", index + marker.length) + 1
        val end = json.indexOf("\"", start)
        return if (start > 0 && end > start) {
            json.substring(start, end)
                .replace("\\n", "\n")
                .replace("\\\"", "\"")
        } else null
    }

    private fun extractContentFromGeminiJson(json: String): String? {
        val marker = "\"text\":"
        val index = json.indexOf(marker)
        if (index == -1) return null
        val start = json.indexOf("\"", index + marker.length) + 1
        val end = json.indexOf("\"", start)
        return if (start > 0 && end > start) {
            json.substring(start, end)
                .replace("\\n", "\n")
                .replace("\\\"", "\"")
        } else null
    }
}
