package com.newsapp.data.api

import com.newsapp.BuildConfig
import com.newsapp.model.NewsItem
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.endpoint
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.json.JSONArray
import org.json.JSONObject

enum class VoiceMode(val label: String, val maxCharsNoSpaces: Int) {
    OWN_VOICE("Власний голос (до 800 симв.)", 800),
    ELEVEN_LABS("ElevenLabs (до 600 симв.)", 600)
}

object ScriptGenerator {
    private val client = HttpClient(CIO) {
        expectSuccess = false
        engine {
            requestTimeout = 60_000
            endpoint {
                connectTimeout = 60_000
                socketTimeout = 60_000
            }
        }
    }

    private val apiKeys: List<String>
        get() = BuildConfig.GEMINI_KEYS
            .replace("\"", "")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private var currentKeyIndex = 0

    private fun getActiveKey(): String {
        val keys = apiKeys
        if (keys.isEmpty()) return ""
        return keys[currentKeyIndex % keys.size]
    }

    private fun switchToNextKey() {
        val keys = apiKeys
        if (keys.isNotEmpty()) {
            currentKeyIndex = (currentKeyIndex + 1) % keys.size
        }
    }

    suspend fun generateScript(newsItem: NewsItem, mode: VoiceMode): String? {
        val cleanTitle = newsItem.title.replace("\"", "'").replace("\n", " ").replace("🚀", "")
        val cleanDesc = newsItem.description.replace("\"", "'").replace("\n", " ")

        val prompt = """
            📋 ОНОВЛЕНИЙ ПРОМПТ ДЛЯ ВІРУСНИХ ТІКТОК/SHORTS/REELS
            Ти — креативний продюсер формату Short-form. Твоя мета — максимізація View vs Swipe та Retention > 105% для каналу «Наука кожного дня».

            🎯 ЗОЛОТІ ПРАВИЛА:
            1. 🔥 Хук (0-3 сек): Жодних статичних вступів, кліше чи розкачок. Відразу інтрига або парадокс.
            2. 🗣️ Вимова: Власні назви та абревіатури пиши точною українською транскрипцією з наголосами (наприклад: На́са, Ем ай ті).
            3. 🔄 Безшовне Зациклення (Perfect Loop): Фінальне слово сценарію має граматично та змістовно зливатися з першим словом хука.
            4. ⏱️ ХРОНОМЕТРАЖ: Строгий ліміт — до ${mode.maxCharsNoSpaces} символів БЕЗ ПРОБІЛІВ. Ні символом більше! Режим озвучки: ${mode.label}.

            📥 ФОРМАТ ВИДАЧІ:
            Блок 1: 🎙️ Текст сценарію
            [Розбити на 4 швидкі смислові блоки з маркерами часу. Тільки текст диктора, повна пунктуація для правильних пауз.]
            (В кінці вказати: кількість символів без пробілів).

            Блок 2: 🎬 Візуальний план (CapCut)
            [Короткі вказівки для відеоряду на кожен блок].

            Блок 3: 📋 Чистий Markdown-код
            ```markdown
            [Текст та план для копіювання]
            ```

            Джерело:
            Заголовок: $cleanTitle
            Текст: $cleanDesc
        """.trimIndent()

        var result: String? = null
        var attempts = 0

        while (result == null && attempts < 5) {
            if (AiRewriter.isGloballyBlocked()) {
                com.newsapp.data.LogManager.log("AI_ERR", "Ліміти вичерпано! Дочекайтесь ${AiRewriter.getBlockTimeFormatted()}")
                break
            }
            result = AiRewriter.callGeminiApi(prompt, "gemini-3.6-flash")
            if (result == null) {
                attempts++
                kotlinx.coroutines.delay(2000)
            }
        }
        return result
    }
}
