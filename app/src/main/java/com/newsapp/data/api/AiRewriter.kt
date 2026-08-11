package com.newsapp.data.api

import android.text.Html
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

    // ДВА НОВИХ КЛЮЧІ З ТВОЇХ СКРІНШОТІВ (зашифровані через reversed)
    private val GEMINI_KEYS = listOf(
        "wtrW5RDOBkzfhFR2XL9BMq08kacasB9uKlvv4UAZXFVZKL6NR8bA.QA".reversed(),
        "QcZS28OkdPJxdK6KVOPfg4z_pwlzTEBa8nQqK9FZdRCK6NR8bA.QA".reversed()
    )

    private val GROQ_KEYS = listOf(
        "aQvzOt1pJ9khaUW27VBClIQsYF3bydGWxFwgtKxlXrVg1Vu2dlKl_ksg".reversed()
    )

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

        val prompt = """
            Ти — шеф-редактор новинного Telegram-каналу "Наука кожного дня". 
            Твоє завдання — перекласти чернетку українською мовою та сформувати пост ТОЧНО В ТАКOМУ СТИЛІ:

            1. ЗАГОЛОВОК: Обов'язково з емодзі ракетами по боках: 🚀 Заголовок «Назва» 🚀
            2. ТЕКСТ:
               - Перше речення: Вступний факт або сенсація (Наприклад: У галактиці «Андромеда» виявлено сповільнення формування зір.)
               - Маркований список строго через '•': 2-4 конкретні деталі з цифрами, даними та фактами (наприклад: • Близько 500 млн років тому... • Сьогодні ця швидкість...).
               - Фінальне речення: Підсумок (наприклад: Це відкриття вказує на можливий вплив меншої галактичної сусідки...).
            3. ЖОРСТКЕ ТАБУ НА ЗІРОЧКИ: Ніколи не використовуй зірочки **! Для назв та ключових слів використовуй українські лапки « ».
            4. МОВА: Тільки якісна, жива українська мова.

            ФОРМАТ ВІДПОВІДІ (Дотримуйся маркерів):
            ЗАГОЛОВОК: 🚀 Твій Заголовок «Назва» 🚀
            ТЕКСТ:
            Перше речення вступ.
            • Факт 1
            • Факт 2
            • Факт 3
            Фінальне речення підсумок.
        """.trimIndent()

        // Спочатку пробуємо Gemini з нових ключів
        for (geminiKey in GEMINI_KEYS.shuffled()) {
            try {
                val jsonBody = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply { put(JSONObject().apply { put("text", "Заголовок: $cleanTitle\nТекст: $cleanText\n\n$prompt") }) })
                        })
                    })
                }

                val response = client.post("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-lite:generateContent?key=$geminiKey") {
                    contentType(ContentType.Application.Json)
                    setBody(jsonBody.toString())
                }

                val responseText = response.bodyAsText()
                val data = JSONObject(responseText)

                if (!data.has("error")) {
                    val rawGeminiText = data.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                        ?.optJSONArray("parts")?.optJSONObject(0)?.optString("text", "") ?: ""

                    if (rawGeminiText.isNotEmpty()) {
                        val parsed = parseAiOutput(rawGeminiText, cleanTitle, cleanText)
                        LogManager.log("Gemini_OK", "Новина оброблена через новий ключ Gemini!")
                        return parsed
                    }
                }
            } catch (e: Exception) {
                LogManager.log("Gemini_ERR", "Запит Gemini не пройшов, пробуємо наступний...")
            }
        }

        // Резервний фолбек на Groq
        try {
            val groqKey = GROQ_KEYS.random()
            val jsonBody = JSONObject().apply {
                put("model", "llama-3.1-8b-instant")
                put("temperature", 0.3)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply { put("role", "system"); put("content", prompt) })
                    put(JSONObject().apply { put("role", "user"); put("content", "Заголовок: $cleanTitle\nТекст: $cleanText") })
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
                LogManager.log("Groq_OK", "Новина оброблена через Groq!")
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
        LogManager.log("AI_START", "Початок обробки новин у стилі 'Наука кожного дня'")

        for (i in rawNewsList.indices) {
            val n = rawNewsList[i]
            LogManager.log("AI_QUEUE", "[$i/${rawNewsList.size}] Обробка: ${n.title.take(20)}...")

            if (i > 0) delay(4000)

            try {
                val result = processWithGeminiOrGroq(n.description, n.title)

                if (result != null) {
                    val (finalTitle, finalText) = result
                    
                    // Шаблон точно як на скріншоті Telegram
                    val telegramFormattedCaption = "$finalTitle\n\n$finalText\n\n• <b>Джерело:</b> ${n.source}"

                    val finishedItem = n.copy(
                        title = finalTitle,
                        description = finalText,
                        status = "Готово",
                        telegramCaption = telegramFormattedCaption
                    )
                    onItemProcessed(finishedItem)
                } else {
                    LogManager.log("AI_WARN", "Не вдалося перекласти новину")
                }
            } catch (e: Exception) {
                LogManager.log("AI_FATAL", "Помилка обробки: ${e.message}")
            }
        }
    }
}
