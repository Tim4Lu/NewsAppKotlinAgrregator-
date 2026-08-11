package com.newsapp.data.api

import android.util.Log
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
                Ти — шеф-редактор новинного науково-історичного Telegram-каналу. Твоє завдання — перетворити надану чернетку на короткий, соковитий пост "по суті".

                ЖОРСТКІ ПРАВИЛА СТИЛЮ (АНТИ-ШІ ВОДА):
                1. ОБ'ЄМ: Максимально лаконічно, строго до 500-600 символів. Жодної води, вступних фраз чи роздумів. Одразу перейди до фактів.
                2. СТРУКТУРА ПОСТУ:
                   - Перше речення: Найголовніший факт або сенсація (Що і де знайшли / Що саме створили).
                   - Далі маркований список (використовуй емодзі '•'): 2-3 найважливіші технічні або історичні деталi (конкретні цифри, факти, як це працює, чому це унікально). 
                   - ЖОРСТКЕ ТАБУ НА ЗІРОЧКИ: Ніколи не використовуй зірочки ** для виділення тексту! Замість цього бери ключові фрази та важливі назви в українські лапки (« »).
                   - Фінальне речення: Короткий підсумок про значення або подальшу долю знахідки.
                3. МОВА: Тільки якісна, жива українська мова.

                ФОРМАТ ВІДПОВІДІ (ДОТРИМУЙСЯ МАРКЕРІВ):
                ЗАГОЛОВОК: (Короткий заголовок українською з 1 тематичним емодзі)
                ТЕКСТ: (Короткий структурований пост строго за правилами вище)

                *Якщо вхідний текст містить лише технічне меню чи сміття, поверни ТІЛЬКИ ОДНЕ СЛОВО: ПОМИЛКА.
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
                return null
            }

            val aiResponse = data.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()

            if (aiResponse.contains("ПОМИЛКА")) {
                return null
            }

            val titleMatch = Regex("ЗАГОЛОВОК:\\s*(.*?)\\n", RegexOption.IGNORE_CASE).find(aiResponse)
            val textMatch = Regex("ТЕКСТ:\\s*([\\s\\S]*)", RegexOption.IGNORE_CASE).find(aiResponse)

            var finalTitle = titleMatch?.groupValues?.get(1)?.trim() ?: title
            var finalText = textMatch?.groupValues?.get(1)?.trim() ?: text

            finalTitle = finalTitle.replace(Regex("\\*\\*(.*?)\\*\\*"), "«$1»").replace("*", "")
            finalText = finalText.replace(Regex("\\*\\*(.*?)\\*\\*"), "«$1»").replace("*", "")

            return Pair(finalTitle, finalText)

        } catch (e: Exception) {
            return null
        }
    }

    // СУВОРЕ ПОСЛІДОВНЕ ВИКОНАННЯ (ПО ОДНІЙ НОВИНІ)
    suspend fun processAllNewsWithAi(
        rawNewsList: List<NewsItem>, 
        onItemProcessed: (NewsItem) -> Unit
    ) {
        val fallbackImg = "https://images.unsplash.com/photo-1451187580459-43490279c0fa?q=80&w=800&auto=format&fit=crop"

        for (i in rawNewsList.indices) {
            val n = rawNewsList[i]
            Log.d("AiRewriter", "[QUEUE] Обробка новини ${i + 1} з ${rawNewsList.size}")

            if (i > 0) {
                delay(2000) // Пауза між запитами, щоб не зловити ліміт (Rate Limit)
            }

            var geminiTitleDraft = n.title
            var geminiTextDraft = n.description
            val geminiKey = getKey("GEMINI")

            try {
                val prompt = """
                    Ти — редактор стрічки новин. Переклади українською мовою та зроби сухий, короткий виклад статті "по суті" без води.
                    Формат відповідь строго такий:
                    ЗАГОЛОВОК_УКР: (короткий переклад заголовка з 1 емодзі)
                    ТЕКСТ_УКР: (короткий текст до 3-4 речень, головні факти)

                    Оригінал заголовка: ${n.title}
                    Оригінал тексту: ${n.description}
                """.trimIndent()

                val jsonBody = JSONObject().apply {
                    val contents = JSONArray().apply {
                        put(JSONObject().apply {
                            val parts = JSONArray().apply {
                                put(JSONObject().apply {
                                    put("text", prompt)
                                })
                            }
                            put("parts", parts)
                        })
                    }
                    put("contents", contents)
                    put("generationConfig", JSONObject().apply { put("temperature", 0.4) })
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
                }
            } catch (e: Exception) {
                // Якщо Gemini впав, використовуємо оригінал для Грока
            }

            val processedResult = verifyWithGroqEditor(geminiTextDraft, geminiTitleDraft)

            var displayTitle = geminiTitleDraft
            var displayText = geminiTextDraft

            if (processedResult != null) {
                displayTitle = processedResult.first
                displayText = processedResult.second
            }

            displayTitle = displayTitle.replace("**", "").replace("*", "")
            displayText = displayText.replace("**", "").replace("*", "")

            val finishedItem = n.copy(
                id = "${n.source}-${UUID.randomUUID()}",
                title = displayTitle,
                description = displayText,
                image = if (n.image.isEmpty()) fallbackImg else n.image,
                status = "Готово",
                telegramCaption = "🚀 <b>$displayTitle</b>\n\n$displayText\n\n• <b>Джерело:</b> ${n.source}"
            )

            // ВІДПРАВЛЯЄМО КОЖНУ ГОТОВУ НОВИНУ НА ЕКРАН ОДРАЗУ
            onItemProcessed(finishedItem)
        }
    }
}
