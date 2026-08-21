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
    private var lastRequestTimestamp = 0L
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
            📋 ОНОВЛЕНИЙ ПРОМПТ-ІНСТРУКЦІЯ ДЛЯ СТВОРЕННЯ ВІРУСНИХ СЦЕНАРІЇВ
            Роль:
            Ти — топовий сценарист та креативний продюсер вірусного науково-популярного контенту (Shorts, TikTok, Reels) для проєкту «Наука кожного дня».
            Головна мета:
            Створювати динамічні, строго фактичні та безшовно зациклені сценарії, які пробивають планку View vs Swipe > 75% та забезпечують утримання аудиторії (Retention > 105%).

            🎯 ЗОЛОТІ ПРАВИЛА ТА АРХІТЕКТУРА СЦЕНАРІЮ:
            1. 🔥 Архітектура Перших 2 Секунд (Вибуховий Унікальний Хук):
               За забороною: Будь-які описові вступи («Науковці виявили...», «Сьогодні ми дізнаємося...»).
               Візуальне правило на хуку: Уникати кадрів вибухів, аварій чи падіння ракет. Замість цього використовувати епічні запуски, красивий проліт ракет/кораблів у космосі, наближення до планет, футуристичні бази або масивні тексти з агресивним саунд-дизайном.
            2. 🔄 Безшовне Зациклення (Perfect Loop Machine):
               Фінальне слово або фраза сценарію має безшовно зливатися з першим словом хука в єдине змістовне та граматично правильне речення.
            3. 🎯 Точність, фактаж та стиль:
               Тільки перевірені наукові факти, без клікбейтного апокаліпсису. Максимальна концентрація цифр і фактів.
            4. ⏱️ ХРОНОМЕТРАЖ ТА ОБСЯГ (СУВОРЕ ОБМЕЖЕННЯ):
               Увага! Якщо текст озвучує ШІ (ElevenLabs), жорсткий ліміт — до 600 символів БЕЗ ПРОБІЛІВ. 
               Якщо озвучує людина (Власний голос), ліміт — до 800 символів БЕЗ ПРОБІЛІВ.
               Зараз обрано режим: ${mode.label}.
               КАТЕГОРИЧНО ЗАБОРОНЕНО перевищувати ліміт у ${mode.maxCharsNoSpaces} символів БЕЗ ПРОБІЛІВ для дикторського тексту! Це критично!
            5. 🎙️ Правила оформлення:
               Повноцінна пунктуація для мікропауз диктора.
               Текст розбити на 4 чіткі смислові блоки з часовими маркерами (0:00–0:08, 0:08–0:19, 0:19–0:31, 0:31–0:40).
               Ключові слова виділяти жирним шрифтом.

            📥 ФОРМАТ ВИДАЧІ ВІДПОВІДІ:
            Блок 1: 🎙️ Текст сценарію для озвучення (38–40 сек)
            Розбитий на 4 смислові блоки з часовими маркерами.
            Наприкінці вказати:
            - Кількість слів
            - Кількість символів з пробілами
            - Кількість символів без пробілів (має бути ≤ ${mode.maxCharsNoSpaces})

            Блок 2: 🎬 Візуальний план та рекомендації для CapCut
            Hook (0:00 - 0:03)
            Details (0:03 - 0:25)
            Climax (0:25 - 0:33)
            Loop (0:33 - 0:40)

            Блок 3: 📋 Чистий Markdown-код (для копіювання в 1 клік)
            Повний текст сценарію та візуального плану у блоці коду ```markdown...```.

            Оригінал новини:
            Заголовок: $cleanTitle
            Текст: $cleanDesc
        """.trimIndent()

        var result: String? = null
        var attempts = 0
        val keys = apiKeys

        while (result == null && attempts < keys.size) {
            val key = getActiveKey()
            result = callGeminiApi(prompt, key, "gemini-3.6-flash")
            if (result == null) {
                switchToNextKey()
                attempts++
            }
        }
        return result
    }

    private suspend fun callGeminiApi(prompt: String, apiKey: String, modelName: String): String? {
        val now = System.currentTimeMillis()
        val timeSinceLastRequest = now - lastRequestTimestamp
        if (timeSinceLastRequest < 16_000) {
            val waitTime = 16_000 - timeSinceLastRequest
            com.newsapp.data.LogManager.log("AI_RATE", "ScriptGen: Пауза ${waitTime / 1000} сек...")
            kotlinx.coroutines.delay(waitTime)
        }
        return try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
            val jsonBody = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", prompt) })
                        })
                    })
                })
            }

            val response = client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(jsonBody.toString())
            }

            if (response.status.value == 200) {
                lastRequestTimestamp = System.currentTimeMillis()
                val json = JSONObject(response.bodyAsText())
                json.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text")
            } else {
                com.newsapp.data.LogManager.log("AI_ERR", "Gemini HTTP ${response.status.value}: ${response.bodyAsText()}")
                lastRequestTimestamp = System.currentTimeMillis()
                null
            }
        } catch (e: Exception) {
            lastRequestTimestamp = System.currentTimeMillis()
            null
        }
    }
}
