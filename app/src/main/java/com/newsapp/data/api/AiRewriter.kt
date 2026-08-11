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
            val systemPrompt = """
                Ти — шеф-редактор новинного Telegram-каналу. Твоє завдання — перетворити чернетку на короткий пост.
                1. ОБ'ЄМ: Строго до 500-600 символів. Одразу до фактів.
                2. СТРУКТУРА:
                   - Перше речення: Головний факт.
                   - Далі маркований список ('•'): 2-3 деталi.
                   - ЖОРСТКЕ ТАБУ НА ЗІРОЧКИ: Ніколи не використовуй зірочки **!
                   - Фінальне речення: Підсумок.
                3. МОВА: Тільки українська.
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
            "https://images.unsplash.com/photo-1506703719100-a0f3a48c0f86?q=80&w=800&auto=format&fit=crop"
        )

        LogManager.log("AI_START", "Початок обробки ${rawNewsList.size} новин")

        for (i in rawNewsList.indices) {
            val n = rawNewsList[i]
            LogManager.log("AI_QUEUE", "[$i/${rawNewsList.size}] Обробка: ${n.title.take(20)}...")

            // АНТИБЛОКУВАННЯ: 4.5 секунди паузи, щоб обійти ліміт 15 запитів/хвилину
            if (i > 0) {
                LogManager.log("AI_WAIT", "Чекаємо 4.5с для обходу лімітів...")
                delay(4500)
            }

            try {
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

                    val data = JSONObject(response.bodyAsText())
                    if (data.has("error")) {
                        LogManager.log("Gemini_LIMIT", "Помилка/Ліміт Gemini: ${data.getJSONObject("error").optString("message")}")
                    }

                    val rawGeminiText = data.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                        ?.optJSONArray("parts")?.optJSONObject(0)?.optString("text", "") ?: ""

                    if (rawGeminiText.isNotEmpty()) {
                        val gTitleMatch = Regex("ЗАГОЛОВОК_УКР:\\s*(.*?)\\n", RegexOption.IGNORE_CASE).find(rawGeminiText)
                        val gTextMatch = Regex("ТЕКСТ_УКР:\\s*([\\s\\S]*)", RegexOption.IGNORE_CASE).find(rawGeminiText)
                        if (gTitleMatch != null) geminiTitleDraft = gTitleMatch.groupValues[1].trim()
                        if (gTextMatch != null) geminiTextDraft = gTextMatch.groupValues[1].trim()
                        LogManager.log("Gemini_OK", "Успішний переклад")
                    }
                } catch (e: Exception) {
                    LogManager.log("Gemini_ERR", "Мережева помилка Gemini")
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

                onItemProcessed(finishedItem)

            } catch (e: Exception) {
                LogManager.log("AI_FATAL", "Загальна помилка: ${e.message}")
            }
        }
    }
}
