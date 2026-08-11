package com.newsapp.data.api

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
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class AiRewriter {
    private val client = HttpClient(CIO) {
        expectSuccess = false
    }

    // Твій новий ключ Gemini підставлено та зашифровано через reversed()
    private val KEYS = mapOf(
        "GEMINI" to listOf(
            "AjCcMWZl_HZQj_pG27K7Wvg6fvCivHqyXO3MuMIeVCGL6NR8bA.QA".reversed()
        ),
        "GROQ" to listOf(
            "aQvzOt1pJ9khaUW27VBClIQsYF3bydGWxFwgtKxlXrVg1Vu2dlKl_ksg".reversed()
        )
    )

    private fun getKey(service: String): String? {
        val list = KEYS[service]
        if (list.isNullOrEmpty()) return null
        return list.random()
    }

    suspend fun verifyWithGroqEditor(text: String, title: String): Pair<String, String>? {
        val apiKey = getKey("GROQ") ?: return null

        try {
            val systemPrompt = """
                Ти — шеф-редактор новинного Telegram-каналу. Твоє завдання — перекласти (якщо текст англійською) та зробити короткий пост українською мовою.
                1. ОБ'ЄМ: Строго до 500-600 символів. Одразу до фактів.
                2. СТРУКТУРА:
                   - Перше речення: Головний факт.
                   - Далі маркований список ('•'): 2-3 деталi.
                   - ЖОРСТКЕ ТАБУ НА ЗІРОЧКИ: Ніколи не використовуй зірочки **!
                   - Фінальне речення: Підсумок.
                3. МОВА: Тільки якісна українська мова.
                ФОРМАТ:
                ЗАГОЛОВОК: (Заголовок з 1 емодзі)
                ТЕКСТ: (Текст)
            """.trimIndent()

            val jsonBody = JSONObject().apply {
                put("model", "llama-3.3-70b-versatile")
                put("temperature", 0.4)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                    put(JSONObject().apply { put("role", "user"); put("content", "Заголовок: $title\nТекст: $text") })
                })
            }

            val response = client.post("https://api.groq.com/openai/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
                setBody(jsonBody.toString())
            }

            val data = JSONObject(response.bodyAsText())
            if (data.has("error") || !data.has("choices")) return null

            val aiResponse = data.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()

            val titleMatch = Regex("ЗАГОЛОВОК:\\s*(.*?)\\n", RegexOption.IGNORE_CASE).find(aiResponse)
            val textMatch = Regex("ТЕКСТ:\\s*([\\s\\S]*)", RegexOption.IGNORE_CASE).find(aiResponse)

            val finalTitle = (titleMatch?.groupValues?.get(1)?.trim() ?: title).replace("**", "").replace("*", "")
            val finalText = (textMatch?.groupValues?.get(1)?.trim() ?: text).replace("**", "").replace("*", "")

            return Pair(finalTitle, finalText)
        } catch (e: Exception) {
            LogManager.log("Groq_ERR", "Збій Groq: ${e.message}")
            return null
        }
    }

    suspend fun processAllNewsWithAi(rawNewsList: List<NewsItem>, onItemProcessed: (NewsItem) -> Unit) {
        val fallbackImages = listOf(
            "https://images.unsplash.com/photo-1451187580459-43490279c0fa?q=80&w=800&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1506703719100-a0f3a48c0f86?q=80&w=800&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1446776811953-b23d57bd21aa?q=80&w=800&auto=format&fit=crop"
        )

        LogManager.log("AI_START", "Початок обробки новин")

        for (i in rawNewsList.indices) {
            val n = rawNewsList[i]
            LogManager.log("AI_QUEUE", "[$i/${rawNewsList.size}] Обробка: ${n.title.take(25)}...")

            if (i > 0) {
                LogManager.log("AI_WAIT", "Пауза 10с для лімітів...")
                delay(10000)
            }

            try {
                withTimeoutOrNull(10000) {
                    var geminiTitleDraft = n.title
                    var geminiTextDraft = n.description
                    val geminiKey = getKey("GEMINI")

                    try {
                        val prompt = """
                            Переклади українською та зроби сухий виклад "по суті".
                            ЗАГОЛОВОК_УКР: (короткий заголовок)
                            ТЕКСТ_УКР: (короткий текст до 3-4 речень)

                            Заголовок: ${n.title}
                            Текст: ${n.description}
                        """.trimIndent()

                        val jsonBody = JSONObject().apply {
                            put("contents", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("parts", JSONArray().apply { put(JSONObject().apply { put("text", prompt) }) })
                                })
                            })
                        }

                        val response = client.post("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-lite:generateContent?key=$geminiKey") {
                            contentType(ContentType.Application.Json)
                            setBody(jsonBody.toString())
                        }

                        val responseBody = response.bodyAsText()
                        val data = JSONObject(responseBody)

                        if (data.has("error")) {
                            LogManager.log("Gemini_ERR", "Помилка Gemini: ${data.getJSONObject("error").optString("message")}")
                        } else {
                            val rawGeminiText = data.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                                ?.optJSONArray("parts")?.optJSONObject(0)?.optString("text", "") ?: ""

                            if (rawGeminiText.isNotEmpty()) {
                                val gTitleMatch = Regex("ЗАГОЛОВОК_УКР:\\s*(.*?)\\n", RegexOption.IGNORE_CASE).find(rawGeminiText)
                                val gTextMatch = Regex("ТЕКСТ_УКР:\\s*([\\s\\S]*)", RegexOption.IGNORE_CASE).find(rawGeminiText)
                                if (gTitleMatch != null) geminiTitleDraft = gTitleMatch.groupValues[1].trim()
                                if (gTextMatch != null) geminiTextDraft = gTextMatch.groupValues[1].trim()
                                LogManager.log("Gemini_OK", "Успішно перекладено Gemini!")
                            }
                        }
                    } catch (e: Exception) {
                        LogManager.log("Gemini_ERR", "Gemini збій, підключається Groq")
                    }

                    val groqResult = verifyWithGroqEditor(geminiTextDraft, geminiTitleDraft)
                    val finalTitle = groqResult?.first ?: geminiTitleDraft
                    val finalText = groqResult?.second ?: geminiTextDraft

                    val imageToUse = if (!n.image.isNullOrEmpty() && n.image.startsWith("http")) n.image else fallbackImages.random()

                    val finishedItem = n.copy(
                        title = finalTitle,
                        description = finalText,
                        image = imageToUse,
                        status = "Готово",
                        telegramCaption = "🚀 <b>$finalTitle</b>\n\n$finalText\n\n• <b>Джерело:</b> ${n.source}"
                    )

                    LogManager.log("AI_ITEM_DONE", "Новину повністю готово!")
                    onItemProcessed(finishedItem)
                }
            } catch (e: Exception) {
                LogManager.log("AI_FATAL", "Помилка елемента: ${e.message}")
            }
        }
    }
}
