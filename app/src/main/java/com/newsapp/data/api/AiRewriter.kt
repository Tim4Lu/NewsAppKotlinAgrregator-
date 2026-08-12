package com.newsapp.data.api

import android.text.Html
import android.util.Base64
import com.newsapp.data.LogManager
import com.newsapp.model.NewsItem
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
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
        expectSuccess = false
    }

    // Зашифровані ключі Gemini у Base64
    private val GEMINI_B64_KEYS = listOf(
        "QVEuQWI4Uk42Sk1Pb1FrSWFETzVUSFdsS0pJNC13WW4yUjhyS0s2YzY4NVRmRUVLN0V1bnc=",
        "QVEuQWI4Uk42SUFyYnJwVEJWZi1ZQXFGbHJYNmpNeG5UWXNMTzktV3JnUVRrOVIwQTNzdlE=",
        "QVEuQWI4Uk42TFRiQ05NZmc5VHV1RWgxc1NqR0FxZzYwSDFVNWRSYkloOFVaSl9fS1RqSnc=",
        "QUl6YVN5QkJZbXN4UzQ5R3ZLYnhLVHZVTXhMNVdJQnZKTDZtVUhV",
        "QVEuQWI4Uk42S09uUkZPNV9YSGluTU12dWtmOFFRdVZ0Q0VpN2J2bk5zVG1iSnhqd1ZfakE="
    )

    private val GROQ_KEYS = listOf(
        "aQvzOt1pJ9khaUW27VBClIQsYF3bydGWxFwgtKxlXrVg1Vu2dlKl_ksg".reversed()
    )

    private fun getDecodedGeminiKeys(): List<String> {
        return try {
            GEMINI_B64_KEYS.map { String(Base64.decode(it, Base64.DEFAULT)).trim() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun cleanHtmlArtifacts(text: String): String {
        return try {
            Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString()
        } catch (e: Exception) {
            text
        }.replace(Regex("\\[&#\\d+;\\]|&#\\d+;"), "")
         .replace(Regex("\\s+"), " ")
         .trim()
    }

    suspend fun processWithGeminiOrGroq(text: String, title: String): Pair<String, String>? {
        val cleanTitle = cleanHtmlArtifacts(title)
        val cleanText = cleanHtmlArtifacts(text).take(1200)

        val systemPrompt = """
            Ти — шеф-редактор новинного Telegram-каналу "Наука кожного дня".
            ТВОЄ ГОЛОВНЕ ЗАВДАННЯ: Перекласти англійську новину на якісну українську мову та зробити короткий структурований пост.

            ЖОРСТКІ ПРАВИЛА:
            1. МОВА: ПОВНІСТЮ ТА СТРOГО УКРАЇНСЬКА МОВА! Жодного англійського слова у фінальному тексті!
            2. ЗАГОЛОВОК: Обов'язково з емодзі ракетами: 🚀 Заголовок «Назва» 🚀
            3. ТЕКСТ:
               - Перше речення: Вступний факт або сенсація українською.
               - Маркований список строго через '•': 2-4 конкретні деталі з цифрами та фактами українською.
               - Фінальне речення: Підсумок українською.
            4. НІЯКИХ ЗІРОЧОК: Не використовуй зірочки **! Для назв використовуй українські лапки « ».

            ФОРМАТ ВІДПОВІДІ:
            ЗАГОЛОВОК: 🚀 Заголовок українською «Назва» 🚀
            ТЕКСТ:
            Перше речення вступ українською.
            • Факт 1 українською
            • Факт 2 українською
            Підсумок українською.
        """.trimIndent()

        val decodedKeys = getDecodedGeminiKeys()

        for ((index, token) in decodedKeys.withIndex()) {
            if (token.isBlank()) continue
            try {
                val jsonBody = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("text", "ПЕРЕКЛАДИ НА УКРАЇНСЬКУ МОВУ ТА СФОРМУЙ ПОСТ:\nЗаголовок: $cleanTitle\nТекст: $cleanText\n\n$systemPrompt")
                                })
                            })
                        })
                    })
                    put("generationConfig", JSONObject().apply {
                        put("temperature", 0.2)
                    })
                }

                val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent?key=$token"

                val response = client.post(url) {
                    contentType(ContentType.Application.Json)
                    setBody(jsonBody.toString())
                }

                val responseText = response.bodyAsText()
                val data = JSONObject(responseText)

                if (!data.has("error")) {
                    val rawGeminiText = data.optJSONArray("candidates")
                        ?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")
                        ?.optJSONObject(0)
                        ?.optString("text", "") ?: ""

                    if (rawGeminiText.isNotEmpty()) {
                        LogManager.log("Gemini_OK", "Успішно через Gemini (ключ #${index + 1})")
                        return parseAiOutput(rawGeminiText, cleanTitle, cleanText)
                    }
                }
            } catch (e: Exception) {
                // Ігноруємо і йдемо до наступного ключа
            }
        }

        try {
            val groqKey = GROQ_KEYS.random()
            val jsonBody = JSONObject().apply {
                put("model", "llama-3.1-8b-instant")
                put("temperature", 0.2)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", "ПЕРЕКЛАДИ ЦЕЙ ТЕКСТ НА УКРАЇНСЬКУ МОВУ ТА ЗРОБИ ПОСТ У ВКАЗАНОМУ ФОРМАТІ:\nЗаголовок: $cleanTitle\nТекст: $cleanText")
                    })
                })
            }

            val response = client.post("https://api.groq.com/openai/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $groqKey")
                setBody(jsonBody.toString())
            }

            val data = JSONObject(response.bodyAsText())
            if (data.has("choices")) {
                val aiResponse = data.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
                LogManager.log("Groq_OK", "Успішно через Groq")
                return parseAiOutput(aiResponse, cleanTitle, cleanText)
            }
        } catch (e: Exception) {
            // Ігноруємо помилку Groq
        }

        LogManager.log("AI_FALLBACK", "ШІ недоступний. Використовуємо оригінальний текст.")
        val fallbackTitle = "🚀 $cleanTitle 🚀"
        val fallbackText = if (cleanText.isNotEmpty()) cleanText else "Свіжа наукова новина."
        return Pair(fallbackTitle, fallbackText)
    }

    private fun parseAiOutput(aiText: String, defaultTitle: String, defaultText: String): Pair<String, String> {
        val titleMatch = Regex("ЗАГОЛОВОК:\\s*(.*?)\\n", RegexOption.IGNORE_CASE).find(aiText)
        val textMatch = Regex("ТЕКСТ:\\s*([\\s\\S]*)", RegexOption.IGNORE_CASE).find(aiText)

        var finalTitle = (titleMatch?.groupValues?.get(1)?.trim() ?: defaultTitle)
            .replace("**", "").replace("*", "")
        if (!finalTitle.contains("🚀")) {
            finalTitle = "🚀 $finalTitle 🚀"
        }

        val finalText = (textMatch?.groupValues?.get(1)?.trim() ?: defaultText)
            .replace("**", "").replace("*", "")

        return Pair(finalTitle, finalText)
    }

    suspend fun processAllNewsWithAi(rawNewsList: List<NewsItem>, onItemProcessed: (NewsItem) -> Unit) {
        LogManager.log("AI_START", "Початок обробки новин")

        for (i in rawNewsList.indices) {
            val n = rawNewsList[i]
            LogManager.log("AI_QUEUE", "[$i/${rawNewsList.size}] Обробка...")

            if (i > 0) delay(1000)

            try {
                val result = processWithGeminiOrGroq(n.description, n.title)

                if (result != null) {
                    val (finalTitle, finalText) = result
                    val telegramFormattedCaption = "$finalTitle\n\n$finalText\n\n• <b>Джерело:</b> ${n.source}"

                    val finishedItem = n.copy(
                        title = finalTitle,
                        description = finalText,
                        status = "Готово",
                        telegramCaption = telegramFormattedCaption
                    )
                    onItemProcessed(finishedItem)
                }
            } catch (e: Exception) {
                LogManager.log("AI_FATAL", "Помилка: ${e.message}")
            }
        }
    }
}
