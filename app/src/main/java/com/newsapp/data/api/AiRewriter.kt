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
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class AiRewriter {
    private val client = HttpClient(CIO) {
        expectSuccess = false
    }

    private val KEYS = mapOf(
        "GEMINI" to listOf(
            "wN0lroowZ0QmYR21AYX6-WUccFuTPx0cv8dz3Z9KUwNK6NR8bA.QA".reversed(),
            "wnuE7KEEfT586c6KKr8R2nYw-4IJKlWHT5ODaIkQoOMJ6NR8bA.QA".reversed()
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
            LogManager.log("Groq", "Запит до Groq Llama-3...")
            val systemPrompt = """
                Ти — шеф-редактор новинного науково-історичного Telegram-каналу. Твоє завдання — перетворити надану чернетку на короткий, соковитий пост "по суті".

                ЖОРСТКІ ПРАВИЛА СТИЛЮ (АНТИ-ШІ ВОДА):
                1. ОБ'ЄМ: Максимально лаконічно, строго до 500-600 символів. Жодної води, вступних фраз чи роздумів. Одразу перейди до фактів.
                2. СТРУКТУРА ПОСТУ:
                   - Перше речення: Найголовніший факт або сенсація.
                   - Далі маркований список (використовуй '•'): 2-3 найважливіші деталi.
                   - ЖОРСТКЕ ТАБУ НА ЗІРОЧКИ: Ніколи не використовуй зірочки ** для виділення тексту!
                   - Фінальне речення: Короткий підсумок.
                3. МОВА: Тільки якісна, жива українська мова.

                ФОРМАТ ВІДПОВІДІ:
                ЗАГОЛОВОК: (Короткий заголовок з 1 емодзі)
                ТЕКСТ: (Короткий структурований пост)
            """.trimIndent()

            val jsonBody = JSONObject().apply {
                put("model", "llama-3.3-70b-versatile")
                put("temperature", 0.4)
                val messages = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", "Оригінальний заголовок: $title\nЧернетка тексту: $text")
                    })
                }
                put("messages", messages)
            }

            val response = client.post("https://api.groq.com/openai/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
                setBody(jsonBody.toString())
            }

            val responseText = response.bodyAsText()
            val data = JSONObject(responseText)

            if (data.has("error") || !data.has("choices")) {
                LogManager.log("Groq_ERR", "Відповідь Groq містить помилку або ліміт")
                return null
            }

            val aiResponse = data.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()

            val titleMatch = Regex("ЗАГОЛОВОК:\\s*(.*?)\\n", RegexOption.IGNORE_CASE).find(aiResponse)
            val textMatch = Regex("ТЕКСТ:\\s*([\\s\\S]*)", RegexOption.IGNORE_CASE).find(aiResponse)

            val finalTitle = (titleMatch?.groupValues?.get(1)?.trim() ?: title)
                .replace("**", "").replace("*", "")
            val finalText = (textMatch?.groupValues?.get(1)?.trim() ?: text)
                .replace("**", "").replace("*", "")

            LogManager.log("Groq_OK", "Успішно редаговано через Groq")
            return Pair(finalTitle, finalText)
        } catch (e: Exception) {
            LogManager.log("Groq_ERR", "Збій Groq: ${e.message}")
            return null
        }
    }

    suspend fun processAllNewsWithAi(
        rawNewsList: List<NewsItem>,
        onItemProcessed: (NewsItem) -> Unit
    ) {
        val fallbackImages = listOf(
            "https://images.unsplash.com/photo-1451187580459-43490279c0fa?q=80&w=800&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1506703719100-a0f3a48c0f86?q=80&w=800&auto=format&fit=crop"
        )

        LogManager.log("AI_START", "Початок обробки ${rawNewsList.size} новин")

        for (i in rawNewsList.indices) {
            val n = rawNewsList[i]
            LogManager.log("AI_QUEUE", "[$i/${rawNewsList.size}] Обробка: ${n.title.take(20)}...")

            if (i > 0) delay(1500)

            try {
                var geminiTitleDraft = n.title
                var geminiTextDraft = n.description
                val geminiKey = getKey("GEMINI")

                try {
                    val prompt = """
                        Переклади українською мовою та зроби сухий, короткий виклад статті "по суті".
                        Формат відповіді:
                        ЗАГОЛОВОК_УКР: (короткий заголовок)
                        ТЕКСТ_УКР: (короткий текст до 3-4 речень)

                        Оригінал заголовка: ${n.title}
                        Оригінал тексту: ${n.description}
                    """.trimIndent()

                    val jsonBody = JSONObject().apply {
                        val contents = JSONArray().apply {
                            put(JSONObject().apply {
                                put("parts", JSONArray().apply {
                                    put(JSONObject().apply { put("text", prompt) })
                                })
                            })
                        }
                        put("contents", contents)
                    }

                    val response = client.post("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-lite:generateContent?key=$geminiKey") {
                        contentType(ContentType.Application.Json)
                        setBody(jsonBody.toString())
                    }

                    val responseText = response.bodyAsText()
                    val data = JSONObject(responseText)

                    val rawGeminiText = data.optJSONArray("candidates")
                        ?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")
                        ?.optJSONObject(0)
                        ?.optString("text", "") ?: ""

                    if (rawGeminiText.isNotEmpty()) {
                        val gTitleMatch = Regex("ЗАГОЛОВОК_УКР:\\s*(.*?)\\n", RegexOption.IGNORE_CASE).find(rawGeminiText)
                        val gTextMatch = Regex("ТЕКСТ_УКР:\\s*([\\s\\S]*)", RegexOption.IGNORE_CASE).find(rawGeminiText)

                        if (gTitleMatch != null) geminiTitleDraft = gTitleMatch.groupValues[1].trim()
                        if (gTextMatch != null) geminiTextDraft = gTextMatch.groupValues[1].trim()
                        LogManager.log("Gemini_OK", "Успішний переклад Gemini")
                    } else {
                        LogManager.log("Gemini_WARN", "Gemini повернув пусту відповідь")
                    }
                } catch (e: Exception) {
                    LogManager.log("Gemini_ERR", "Помилка Gemini: ${e.message}")
                }

                val groqResult = verifyWithGroqEditor(geminiTextDraft, geminiTitleDraft)

                val finalTitle = groqResult?.first ?: geminiTitleDraft
                val finalText = groqResult?.second ?: geminiTextDraft

                val imageToUse = if (n.image.startsWith("http")) n.image else fallbackImages.random()

                val finishedItem = n.copy(
                    title = finalTitle,
                    description = finalText,
                    image = imageToUse,
                    status = "Готово",
                    telegramCaption = "🚀 <b>$finalTitle</b>\n\n$finalText\n\n• <b>Джерело:</b> ${n.source}"
                )

                LogManager.log("AI_ITEM_DONE", "Новина готова і відправлена у UI!")
                onItemProcessed(finishedItem)

            } catch (e: Exception) {
                LogManager.log("AI_FATAL", "Загальна помилка елемента: ${e.message}")
            }
        }
    }
}
