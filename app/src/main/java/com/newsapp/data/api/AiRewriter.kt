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
import java.util.UUID

class AiRewriter {
    private val client = HttpClient(CIO) {
        expectSuccess = false
    }

    private val GROQ_KEY = "aQvzOt1pJ9khaUW27VBClIQsYF3bydGWxFwgtKxlXrVg1Vu2dlKl_ksg".reversed()

    // Очищення HTML артефактів на кшталт [&#8230;] та спецсимволів
    private fun cleanHtmlArtifacts(text: String): String {
        return try {
            Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString()
        } catch (e: Exception) {
            text
        }.replace(Regex("\\[&#\\d+;\\]|&#\\d+;"), "")
         .replace(Regex("\\s+"), " ")
         .trim()
    }

    suspend fun processWithGroq(text: String, title: String): Pair<String, String>? {
        try {
            val cleanTitle = cleanHtmlArtifacts(title)
            val cleanText = cleanHtmlArtifacts(text)

            val systemPrompt = """
                Ти — шеф-редактор новинного Telegram-каналу про космос та науку. 
                Твоє завдання — перекласти текст українською мовою та зробити короткий структурований пост.

                ЖОРСТКІ ПРАВИЛА:
                1. Перекладай строго українською мовою.
                2. ОБ'ЄМ: Максимально коротко, до 500 символів. Жодної води та меню сайту.
                3. СТРУКТУРА ПОСТУ:
                   - Перше речення: Найголовніший факт.
                   - Далі список ('•'): 2-3 важливі деталі.
                   - Фінальне речення: Підсумок.
                4. ЖОРСТКЕ ТАБУ НА ЗІРОЧКИ: Ніколи не використовуй зірочки **!

                ФОРМАТ ВІДПОВІДІ:
                ЗАГОЛОВОК: (Заголовок українською з 1 емодзі)
                ТЕКСТ: (Короткий пост українською)
            """.trimIndent()

            val jsonBody = JSONObject().apply {
                put("model", "llama-3.3-70b-versatile")
                put("temperature", 0.3)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                    put(JSONObject().apply { put("role", "user"); put("content", "Заголовок: $cleanTitle\nТекст: $cleanText") })
                })
            }

            val response = client.post("https://api.groq.com/openai/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $GROQ_KEY")
                setBody(jsonBody.toString())
            }

            val responseText = response.bodyAsText()
            val data = JSONObject(responseText)

            if (data.has("error") || !data.has("choices")) {
                LogManager.log("Groq_ERR", "Помилка відповіді Groq: $responseText")
                return null
            }

            val aiResponse = data.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()

            val titleMatch = Regex("ЗАГОЛОВОК:\\s*(.*?)\\n", RegexOption.IGNORE_CASE).find(aiResponse)
            val textMatch = Regex("ТЕКСТ:\\s*([\\s\\S]*)", RegexOption.IGNORE_CASE).find(aiResponse)

            val finalTitle = (titleMatch?.groupValues?.get(1)?.trim() ?: cleanTitle)
                .replace("**", "").replace("*", "")
            val finalText = (textMatch?.groupValues?.get(1)?.trim() ?: cleanText)
                .replace("**", "").replace("*", "")

            return Pair(finalTitle, finalText)
        } catch (e: Exception) {
            LogManager.log("Groq_ERR", "Виключення Groq: ${e.message}")
            return null
        }
    }

    suspend fun processAllNewsWithAi(rawNewsList: List<NewsItem>, onItemProcessed: (NewsItem) -> Unit) {
        LogManager.log("AI_START", "Початок обробки ${rawNewsList.size} новин через Groq")

        for (i in rawNewsList.indices) {
            val n = rawNewsList[i]
            LogManager.log("AI_QUEUE", "[$i/${rawNewsList.size}] Обробка: ${n.title.take(20)}...")

            if (i > 0) delay(3000)

            try {
                val groqResult = processWithGroq(n.description, n.title)

                if (groqResult != null) {
                    val (finalTitle, finalText) = groqResult
                    val finishedItem = n.copy(
                        title = finalTitle,
                        description = finalText,
                        status = "Готово",
                        telegramCaption = "🚀 <b>$finalTitle</b>\n\n$finalText\n\n• <b>Джерело:</b> ${n.source}"
                    )
                    LogManager.log("AI_OK", "Новина [${finalTitle.take(15)}] перекладена!")
                    onItemProcessed(finishedItem)
                } else {
                    LogManager.log("AI_WARN", "Не вдалося перекласти новину, лишаємо оригінал")
                }
            } catch (e: Exception) {
                LogManager.log("AI_FATAL", "Збій обробки елемента: ${e.message}")
            }
        }
    }
}
