package com.newsapp.data.api

import android.content.Context
import com.newsapp.BuildConfig
import com.newsapp.data.LogManager
import com.newsapp.model.NewsItem
import com.newsapp.service.NewsProcessingService
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.endpoint
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

object AiRewriter {
    private val processingNewsIds = mutableSetOf<String>()

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
    var lastRequestTimestamp = 0L
    val geminiMutex = kotlinx.coroutines.sync.Mutex()

    suspend fun enforceRateLimit() {
        geminiMutex.withLock {
            val now = System.currentTimeMillis()
            val timeSinceLastRequest = now - lastRequestTimestamp
            if (timeSinceLastRequest < 16_000) {
                val waitTime = 16_000 - timeSinceLastRequest
                LogManager.log("AI_RATE", "Замок Mutex: чекаємо ${waitTime / 1000} сек...")
                delay(waitTime)
            }
        }
    }

    private fun getActiveKey(): Pair<String, Int> {
        val keys = apiKeys
        if (keys.isEmpty()) return Pair("", 0)
        val index = currentKeyIndex % keys.size
        return Pair(keys[index], index + 1)
    }

    private fun switchToNextKey() {
        val keys = apiKeys
        if (keys.isNotEmpty()) {
            currentKeyIndex = (currentKeyIndex + 1) % keys.size
        }
    }

    suspend fun translateFullArticle(newsItem: NewsItem): String? {
        var fullOriginalText = ""
        try {
            if (newsItem.link.isNotEmpty()) {
                val response = client.get(newsItem.link) {
                    header(io.ktor.http.HttpHeaders.UserAgent, "Mozilla/5.0")
                }
                val html = response.bodyAsText()
                val cleanHtml = html.replace(Regex("<(nav|header|footer|script|style|button|aside|noscript)[^>]*>[\\s\\S]*?<\\/\\1>", RegexOption.IGNORE_CASE), "")
                val pMatches = Regex("<p[^>]*>(.*?)</p>", RegexOption.IGNORE_CASE).findAll(cleanHtml)
                val validParagraphs = pMatches
                    .map { it.groupValues[1].replace(Regex("<[^>]*>"), "").trim() }
                    .filter { t -> t.length > 80 && t.contains(".") }
                    .toList()
                
                fullOriginalText = validParagraphs.joinToString("\n\n")
            }
        } catch (e: Exception) {
            LogManager.log("AI_SCRAPE_ERR", "Не вдалося витягнути оригінал: ${e.message}")
        }

        val textToTranslate = if (fullOriginalText.length > 150) fullOriginalText else newsItem.description

        val prompt = """
            Ти — науковий перекладач. Зроби повний, детальний та якісний переклад усієї статті українською мовою. 
            Збережи всі абзаци, наукові факти, терміни та деталі оригінального тексту. Нічого не скорочуй.
            
            Заголовок: ${newsItem.originalTitle.ifEmpty { newsItem.title }}
            Текст: $textToTranslate
        """.trimIndent()

        var translatedText: String? = null
        var attempts = 0
        val keys = apiKeys

        while (translatedText == null && attempts < keys.size) {
            val (apiKey, keyNum) = getActiveKey()
            translatedText = callGeminiApi(prompt, apiKey, keyNum, "gemini-3.6-flash")

            if (translatedText == null) {
                switchToNextKey()
                attempts++
            }
        }
        return translatedText
    }

    suspend fun processAllNewsWithAi(
        newsList: List<NewsItem>,
        context: Context? = null,
        onItemProcessed: (NewsItem) -> Unit
    ) {
        context?.let { NewsProcessingService.start(it) }

        try {
            val total = newsList.size
            val keysCount = apiKeys.size
            LogManager.log("AI_START", "Обробка ${total} новин (ключів: ${keysCount})")

            val newsToProcess = newsList.filter { !processingNewsIds.contains(it.id) }
        newsToProcess.forEachIndexed { index, item ->
            processingNewsIds.add(item.id)
                try {
                    val cleanTitle = item.title.replace("\"", "'").replace("\n", " ").replace("🚀", "")
                    val cleanDesc = item.description.replace("\"", "'").replace("\n", " ")

                    val prompt = """
                        Зроби пост для Telegram українською. СТИСЛО!
                        1. Яскравий заголовок.
                        2. 2 речення суті.
                        3. 3 головні факти булітами (•).
                        Без вступів, без "Ось переклад", без **. Джерело не пиши.

                        Заголовок: $cleanTitle
                        Текст: $cleanDesc
                    """.trimIndent()

                    var translatedText: String? = null
                    var attempts = 0
                    val keys = apiKeys

                    while (translatedText == null && attempts < keys.size) {
                        val (apiKey, keyNum) = getActiveKey()
                        translatedText = callGeminiApi(prompt, apiKey, keyNum, "gemini-3.6-flash")

                        if (translatedText == null) {
                            switchToNextKey()
                            attempts++
                        }
                    }

                    val finalItem = if (!translatedText.isNullOrEmpty()) {
                        var cleanResult = translatedText.replace("**", "").trim()
                        cleanResult = cleanResult.replace("(?i)^текст новини:\\s*".toRegex(), "")

                        val parts = cleanResult.split("\n", limit = 2)
                        val rawTitle = parts.getOrNull(0)?.replace(Regex("^[#*\\s🚀]+"), "")?.trim() ?: item.title
                        var newDesc = parts.getOrNull(1)?.trim() ?: cleanResult

                        val sourceIndex = newDesc.indexOf("Джерело:", ignoreCase = true)
                        if (sourceIndex != -1) {
                            newDesc = newDesc.substring(0, sourceIndex).trimEnd(' ', '\n', '•', '\r')
                        }

                        val sourceLinkHtml = if (item.link.isNotEmpty()) {
                            "• <b>Джерело:</b> <a href=\"${item.link}\">${item.source}</a>"
                        } else {
                            "• <b>Джерело:</b> ${item.source}"
                        }

                        val finalCaption = "🚀 <b>$rawTitle</b> 🚀\n\n$newDesc\n\n$sourceLinkHtml"

                        item.copy(
                            title = rawTitle,
                            description = "$newDesc\n\n• Джерело: ${item.source}",
                            telegramCaption = finalCaption,
                            status = "Готово"
                        )
                    } else {
                        LogManager.log("AI_WARN", "Не вдалося перекласти новину: '${item.title}'. Статус: Не перекладено")
                        val cleanOrigTitle = item.title.replace("🚀", "").trim()
                        var cleanOrigDesc = item.description
                        val sourceIdx = cleanOrigDesc.indexOf("Джерело:", ignoreCase = true)
                        if (sourceIdx != -1) cleanOrigDesc = cleanOrigDesc.substring(0, sourceIdx).trimEnd(' ', '\n', '•', '\r')
                        
                        val sourceLinkHtml = if (item.link.isNotEmpty()) {
                            "• <b>Джерело:</b> <a href=\"${item.link}\">${item.source}</a>"
                        } else {
                            "• <b>Джерело:</b> ${item.source}"
                        }

                        val caption = "🚀 <b>$cleanOrigTitle</b> 🚀\n\n$cleanOrigDesc\n\n$sourceLinkHtml"
                        item.copy(
                            title = cleanOrigTitle,
                            description = "$cleanOrigDesc\n\n• Джерело: ${item.source}",
                            telegramCaption = caption, 
                            status = "Не перекладено"
                        )
                    }

                    onItemProcessed(finalItem)
                } catch (e: Exception) {
                    LogManager.log("AI_CRITICAL", "Збій: ${e.message}")
                } finally {
                    processingNewsIds.remove(item.id)
                }
            }
        } finally {
            context?.let { NewsProcessingService.stop(it) }
        }
    }

    private suspend fun callGeminiApi(prompt: String, apiKey: String, keyNum: Int, modelName: String): String? {
        enforceRateLimit()

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
                val json = JSONObject(response.bodyAsText())
                json.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    ?.takeIf { it.isNotEmpty() }
            } else {
                LogManager.log("AI_ERR", "Ключ №$keyNum вичерпано (HTTP ${response.status.value}): ${response.bodyAsText()}")
                null
            }
        } catch (e: Exception) {
            LogManager.log("AI_ERR", "Мережевий збій Gemini: ${e.message}")
            null
        }
    }
}
