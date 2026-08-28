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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object AiRewriter {
    private val processingNewsIds = ConcurrentHashMap.newKeySet<String>()
    private val keyCooldowns = ConcurrentHashMap<String, Long>()

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
        get() = BuildConfig.GEMINI_KEYS.replace("\"", "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        
    private var currentKeyIndex = 0
    var lastRequestTimestamp = 0L
    val geminiMutex = Mutex()

    suspend fun enforceRateLimit() {
        geminiMutex.withLock {
            val now = System.currentTimeMillis()
            val timeSinceLastRequest = now - lastRequestTimestamp
            if (timeSinceLastRequest < 16_000) {
                val waitTime = 16_000 - timeSinceLastRequest
                LogManager.log("AI_RATE", "Mutex: чекаємо ${waitTime / 1000} сек...")
                delay(waitTime)
            }
            lastRequestTimestamp = System.currentTimeMillis()
        }
    }

    private fun getActiveKey(): Pair<String, Int>? {
        val keys = apiKeys
        if (keys.isEmpty()) return null
        val now = System.currentTimeMillis()
        for (i in keys.indices) {
            val index = (currentKeyIndex + i) % keys.size
            val key = keys[index]
            if (now > (keyCooldowns[key] ?: 0L)) {
                currentKeyIndex = index
                return Pair(key, index + 1)
            }
        }
        return null
    }

    private fun markKeyOnCooldown(key: String, keyNum: Int) {
        LogManager.log("AI_ERR", "Ключ №$keyNum вичерпано. Блокуємо на 1 годину.")
        keyCooldowns[key] = System.currentTimeMillis() + (60 * 60 * 1000L)
    }

    suspend fun translateFullArticle(newsItem: NewsItem): String? {
        var fullOriginalText = ""
        try {
            if (newsItem.link.isNotEmpty()) {
                val response = client.get(newsItem.link) { header(io.ktor.http.HttpHeaders.UserAgent, "Mozilla/5.0") }
                val cleanHtml = response.bodyAsText().replace(Regex("<(nav|header|footer|script|style|button|aside|noscript)[^>]*>[\\s\\S]*?<\\/\\1>", RegexOption.IGNORE_CASE), "")
                val pMatches = Regex("<p[^>]*>(.*?)</p>", RegexOption.IGNORE_CASE).findAll(cleanHtml)
                fullOriginalText = pMatches.map { it.groupValues[1].replace(Regex("<[^>]*>"), "").trim() }.filter { t -> t.length > 80 && t.contains(".") }.toList().joinToString("\n\n")
            }
        } catch (e: Exception) {}

        val textToTranslate = if (fullOriginalText.length > 150) fullOriginalText else newsItem.description
        val prompt = "Ти — науковий перекладач. Зроби повний, детальний та якісний переклад усієї статті українською мовою. Збережи всі абзаци, наукові факти, терміни та деталі оригінального тексту. Нічого не скорочуй.\n\nЗаголовок: ${newsItem.originalTitle.ifEmpty { newsItem.title }}\nТекст: $textToTranslate"
        
        var translatedText: String? = null
        var attempts = 0
        while (translatedText == null && attempts < apiKeys.size) {
            if (getActiveKey() == null) { LogManager.log("AI_ERR", "Усі ключі на кулдауні!"); break }
            translatedText = callGeminiApi(prompt, "gemini-3.6-flash")
            if (translatedText == null) attempts++
        }
        return translatedText
    }

    suspend fun processAllNewsWithAi(newsList: List<NewsItem>, context: Context? = null, onItemProcessed: (NewsItem) -> Unit) {
        context?.let { NewsProcessingService.start(it) }
        try {
            val newsToProcess = mutableListOf<NewsItem>()
            for (item in newsList) {
                if (!processingNewsIds.contains(item.id) ) {
                    processingNewsIds.add(item.id)
                    newsToProcess.add(item)
                }
            }
            if (newsToProcess.isEmpty()) return
            LogManager.log("AI_START", "Безпечна обробка ${newsToProcess.size} новин")

            var isQueueStopped = false
        for (item in newsToProcess) {
            if (isQueueStopped) {
                processingNewsIds.remove(item.id)
                continue
            }
                try {
                    val prompt = "Зроби пост для Telegram українською. СТИСЛО!\n1. Яскравий заголовок.\n2. 2 речення суті.\n3. 3 головні факти булітами (•).\nБез вступів, без \"Ось переклад\", без **. Джерело не пиши.\n\nЗаголовок: ${item.title.replace("\"", "'").replace("\n", " ").replace("🚀", "")}\nТекст: ${item.description.replace("\"", "'").replace("\n", " ")}"
                    var translatedText: String? = null
                    var attempts = 0

                    while (translatedText == null && attempts < apiKeys.size) {
                        if (getActiveKey() == null) { LogManager.log("AI_ERR", "Ключі вичерпано! Зупиняємо всю чергу."); isQueueStopped = true; break }
                        translatedText = callGeminiApi(prompt, "gemini-3.6-flash")
                        if (translatedText == null) attempts++
                    }

                    val finalItem = if (!translatedText.isNullOrEmpty()) {
                        var cleanResult = translatedText.replace("**", "").trim().replace("(?i)^текст новини:\\s*".toRegex(), "")
                        val parts = cleanResult.split("\n", limit = 2)
                        val rawTitle = parts.getOrNull(0)?.replace(Regex("^[#*\\s🚀]+"), "")?.trim() ?: item.title
                        var newDesc = parts.getOrNull(1)?.trim() ?: cleanResult
                        val sourceIndex = newDesc.indexOf("Джерело:", ignoreCase = true)
                        if (sourceIndex != -1) newDesc = newDesc.substring(0, sourceIndex).trimEnd(' ', '\n', '•', '\r')
                        
                        val sourceLinkHtml = if (item.link.isNotEmpty()) "• <b>Джерело:</b> <a href=\"${item.link}\">${item.source}</a>" else "• <b>Джерело:</b> ${item.source}"
                        item.copy(title = rawTitle, description = "$newDesc\n\n• Джерело: ${item.source}", telegramCaption = "🚀 <b>$rawTitle</b> 🚀\n\n$newDesc\n\n$sourceLinkHtml", status = "Готово")
                    } else {
                        LogManager.log("AI_WARN", "Не перекладено: '${item.title}'")
                        val cleanOrigTitle = item.title.replace("🚀", "").trim()
                        var cleanOrigDesc = item.description
                        val sourceIdx = cleanOrigDesc.indexOf("Джерело:", ignoreCase = true)
                        if (sourceIdx != -1) cleanOrigDesc = cleanOrigDesc.substring(0, sourceIdx).trimEnd(' ', '\n', '•', '\r')
                        val sourceLinkHtml = if (item.link.isNotEmpty()) "• <b>Джерело:</b> <a href=\"${item.link}\">${item.source}</a>" else "• <b>Джерело:</b> ${item.source}"
                        item.copy(title = cleanOrigTitle, description = "$cleanOrigDesc\n\n• Джерело: ${item.source}", telegramCaption = "🚀 <b>$cleanOrigTitle</b> 🚀\n\n$cleanOrigDesc\n\n$sourceLinkHtml", status = "Не перекладено")
                    }
                    onItemProcessed(finalItem)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    LogManager.log("AI_CRITICAL", "Збій: ${e.message}")
                    onItemProcessed(item.copy(status = "Не перекладено"))
                } finally {
                    processingNewsIds.remove(item.id)
                }
            }
        } finally { context?.let { NewsProcessingService.stop(it) } }
    }


    private fun getNextQuotaResetTime(): Long {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Europe/Kiev"))
        if (cal.get(java.util.Calendar.HOUR_OF_DAY) >= 10) {
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        cal.set(java.util.Calendar.HOUR_OF_DAY, 10)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }


    fun isGloballyBlocked(): Boolean {
        val keys = apiKeys
        if (keys.isEmpty()) return false
        val nextTime = keys.map { keyCooldowns[it] ?: 0L }.minOrNull() ?: 0L
        return System.currentTimeMillis() < nextTime
    }

    fun getBlockTimeFormatted(): String {
        val keys = apiKeys
        if (keys.isEmpty()) return ""
        val nextTime = keys.map { keyCooldowns[it] ?: 0L }.minOrNull() ?: 0L
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(nextTime))
    }

    suspend fun callGeminiApi(prompt: String, modelName: String): String? {
        enforceRateLimit()
        val active = getActiveKey()
        if (active == null) {
            LogManager.log("AI_ERR", "Усі ключі на кулдауні! Зупинка черги.")
            return null
        }
        val apiKey = active.first
        val keyNum = active.second
        return try {
            val response = client.post("https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey") {
                contentType(ContentType.Application.Json)
                setBody(JSONObject().apply { put("contents", JSONArray().apply { put(JSONObject().apply { put("parts", JSONArray().apply { put(JSONObject().apply { put("text", prompt) }) }) }) }) }.toString())
            }
            if (response.status.value == 200) {
                JSONObject(response.bodyAsText()).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")?.takeIf { it.isNotEmpty() }
            } else if (response.status.value == 401) {
                LogManager.log("AI_ERR", "Ключ №$keyNum недійсний (401). Видаляємо з ротації.")
                keyCooldowns[apiKey] = System.currentTimeMillis() + (24 * 60 * 60 * 1000L)
                null
            } else if (response.status.value == 429) {
                val errBody = try { response.bodyAsText().lowercase() } catch (e: Exception) { "" }
                if (errBody.contains("per day") || errBody.contains("quota")) {
                val resetTime = getNextQuotaResetTime()
                LogManager.log("AI_ERR", "Ключ №$keyNum вичерпав денний ліміт. Блок до 10:00 ранку.")
                keyCooldowns[apiKey] = resetTime
            } else {
                    LogManager.log("AI_WARN", "Ключ №$keyNum перевантажений (RPM). Пауза 5 хв...")
                    keyCooldowns[apiKey] = System.currentTimeMillis() + (5 * 60 * 1000L)
                }
                null
            } else {
                LogManager.log("AI_ERR", "Gemini HTTP ${response.status.value}: ${response.bodyAsText()}")
                null
            }
        } catch (e: Exception) {
                val msg = e.message ?: ""
                if (msg.contains("EOF")) LogManager.log("AI_WARN", "Мережа: обрив з'єднання (EOF). Повтор...")
                else LogManager.log("AI_ERR", "Мережевий збій Gemini: $msg")
                keyCooldowns[apiKey] = System.currentTimeMillis() + 30_000L // Пауза 30с від спаму
                null 
            }
    }
}
