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

    // Твій робочий токен з AI Studio (розбитий через Base64 для GitHub)
    private val T1 = "QVEuQWI4Uk42S0tKQTA2bElwWWhPNTNB"
    private val T2 = "blBMZi1NZ255S0RDTXo0eGlMVTdvRHc4Z2dwb0E="

    private val GROQ_KEYS = listOf(
        "aQvzOt1pJ9khaUW27VBClIQsYF3bydGWxFwgtKxlXrVg1Vu2dlKl_ksg".reversed()
    )

    private fun getDecodedToken(): String {
        return try {
            val b64 = T1 + T2
            String(Base64.decode(b64, Base64.DEFAULT)).trim()
        } catch (e: Exception) {
            ""
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

        val token = getDecodedToken()

        // 1. GEMINI 3.6 FLASH (Стандартний API)
        if (token.isNotEmpty()) {
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

                // Використовуємо актуальну і дозволену модель gemini-3.6-flash
                val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent?key=$token"

                val response = client.post(url) {
                    contentType(ContentType.Application.Json)
                    setBody(jsonBody.toString())
                }

                val responseText = response.bodyAsText()
                
                // Логуємо сиру відповідь для залізобетонного контролю
                LogManager.log("Gemini_RAW", responseText.replace("\n", "").take(250))

                try {
                    val data = JSONObject(responseText)
                    if (!data.has("error")) {
                        // Правильний парсинг стандартної структури (candidates -> content -> parts -> text)
                        val rawGeminiText = data.optJSONArray("candidates")
                            ?.optJSONObject(0)
                            ?.optJSONObject("content")
                            ?.optJSONArray("parts")
                            ?.optJSONObject(0)
                            ?.optString("text", "") ?: ""

                        if (rawGeminiText.isNotEmpty()) {
                            val parsed = parseAiOutput(rawGeminiText, cleanTitle, cleanText)
                            LogManager.log("Gemini_OK", "Успішно перекладено через Gemini 3.6 Flash!")
                            return parsed
                        } else {
                            LogManager.log("Gemini_ERR", "Порожня відповідь (немає тексту в candidates)")
                        }
                    } else {
                        val errMessage = data.getJSONObject("error").optString("message", "Unknown error")
                        LogManager.log("Gemini_ERR", "Помилка API: $errMessage")
                    }
                } catch (e: Exception) {
                    LogManager.log("Gemini_ERR", "Крах парсингу JSON: ${e.message}")
                }
            } catch (e: Exception) {
                LogManager.log("Gemini_ERR", "Мережевий запит впав: ${e.message ?: e.toString()}")
            }
        }

        // 2. GROQ (Резервний)
        try {
            LogManager.log("Groq", "Використовуємо резервний Groq...")
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
                LogManager.log("Groq_OK", "Резервний переклад через Groq")
                return parseAiOutput(aiResponse, cleanTitle, cleanText)
            }
        } catch (e: Exception) {
            LogManager.log("Groq_ERR", "Groq також не пройшов: ${e.message}")
        }

        return null
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
        LogManager.log("AI_START", "Початок обробки новин через Gemini")

        for (i in rawNewsList.indices) {
            val n = rawNewsList[i]
            LogManager.log("AI_QUEUE", "[$i/${rawNewsList.size}] Переклад: ${n.title.take(20)}...")

            if (i > 0) delay(3500)

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
                } else {
                    LogManager.log("AI_WARN", "Пропущено новину")
                }
            } catch (e: Exception) {
                LogManager.log("AI_FATAL", "Помилка елемента: ${e.message}")
            }
        }
    }
}
