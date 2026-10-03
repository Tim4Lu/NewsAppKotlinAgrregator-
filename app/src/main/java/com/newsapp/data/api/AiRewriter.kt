package com.newsapp.data.api

import android.content.Context
import android.content.SharedPreferences
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
            endpoint { connectTimeout = 60_000; socketTimeout = 60_000 } 
        }
    }

    private val apiKeys: List<String> 
        get() = BuildConfig.GEMINI_KEYS.replace("\"", "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        
    private var currentKeyIndex = 0
    var lastRequestTimestamp = 0L
    val geminiMutex = Mutex()

    private lateinit var prefs: SharedPreferences
    private var isInitialized = false

    private const val DAILY_LIMIT = 1450 

    fun init(context: Context? = null) {
        if (context != null && !isInitialized) {
            prefs = context.getSharedPreferences("ai_stats", Context.MODE_PRIVATE)
            checkAndResetDailyCounters()
            isInitialized = true
        }
    }

    private fun checkAndResetDailyCounters() {
        if (!isInitialized) return
        val lastReset = prefs.getLong("last_reset_day_v3", 0L)
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Europe/Kiev"))
        cal.add(java.util.Calendar.HOUR_OF_DAY, -10); val currentDay = cal.get(java.util.Calendar.DAY_OF_YEAR)
        if (lastReset != currentDay.toLong()) {
            prefs.edit().clear().putLong("last_reset_day_v3", currentDay.toLong()).apply()
            keyCooldowns.clear()
        }
    }

    fun getStats(): String {
        if (!isInitialized) return "Статистика завантажується..."
        checkAndResetDailyCounters()
        val keys = apiKeys
        val sb = StringBuilder("📊 Запитів до Gemini сьогодні:\n")
        keys.forEachIndexed { index, key ->
            val count = prefs.getInt("key_count_$index", 0)
            val status = when {
                count >= DAILY_LIMIT -> "🔴 Вичерпано (блок)"
                keyCooldowns[key] ?: 0L > System.currentTimeMillis() -> "🟡 Пауза (ліміт/помилка)"
                else -> "🟢 Активний"
            }
            sb.append("Ключ ${index + 1}: $count / $DAILY_LIMIT ($status)\n")
        }
        return sb.toString().trim()
    }

    private fun incrementKeyUsage(keyIndex: Int) {
        if (isInitialized) {
            val current = prefs.getInt("key_count_$keyIndex", 0)
            prefs.edit().putInt("key_count_$keyIndex", current + 1).apply()
        }
    }

    suspend fun enforceRateLimit() {
        geminiMutex.withLock {
            val now = System.currentTimeMillis()
            val timeSinceLastRequest = now - lastRequestTimestamp
            // Трохи збільшуємо буфер між запитами (4200мс), щоб рідше ловити 15 RPM
            if (timeSinceLastRequest < 16_000) {
                delay(16_000 - timeSinceLastRequest)
            }
            lastRequestTimestamp = System.currentTimeMillis()
        }
    }

    private fun getActiveKey(): Pair<String, Int>? { checkAndResetDailyCounters()
        val keys = apiKeys
        if (keys.isEmpty()) return null
        val now = System.currentTimeMillis()
        
        for (i in keys.indices) {
            val index = (currentKeyIndex + i) % keys.size
            val key = keys[index]
            val usageCount = if (isInitialized) prefs.getInt("key_count_$index", 0) else 0

            if (usageCount >= DAILY_LIMIT) {
                if ((keyCooldowns[key] ?: 0L) < getNextQuotaResetTime()) {
                    LogManager.log("AI_LIMIT", "Ключ №${index + 1} вичерпав ліміт ($DAILY_LIMIT). Блок до 10:00.")
                    keyCooldowns[key] = getNextQuotaResetTime()
                }
                continue 
            }

            if (now > (keyCooldowns[key] ?: 0L)) {
                currentKeyIndex = (index + 1) % keys.size
                return Pair(key, index)
            }
        }
        return null
    }

    private fun getNextQuotaResetTime(): Long {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Europe/Kiev"))
        if (cal.get(java.util.Calendar.HOUR_OF_DAY) >= 10) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 10); cal.set(java.util.Calendar.MINUTE, 0); cal.set(java.util.Calendar.SECOND, 0)
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
            if (getActiveKey() == null) { delay(10000); attempts++; continue }
            val models = listOf("gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.6-flash"); translatedText = callGeminiApi(prompt, models[attempts % 3])
            if (translatedText == "[SAFETY_BLOCK]") return "Текст заблоковано фільтрами безпеки Gemini."
            if (translatedText == null) attempts++
        }
        return translatedText
    }

    suspend fun processAllNewsWithAi(newsList: List<NewsItem>, context: Context? = null, onItemProcessed: (NewsItem) -> Unit) {
        context?.let { NewsProcessingService.start(it) }
        try {
            val newsToProcess = mutableListOf<NewsItem>()
            for (item in newsList) {
                if (!processingNewsIds.contains(item.id)) {
                    processingNewsIds.add(item.id)
                    newsToProcess.add(item)
                }
            }
            if (newsToProcess.isEmpty()) return
            LogManager.log("AI_START", "Обробка ${newsToProcess.size} новин")
            var isQueueStopped = false

            for (item in newsToProcess) {
                if (isQueueStopped) { onItemProcessed(item.copy(status = "Ліміт AI")); processingNewsIds.remove(item.id); continue }
                try {
                    val prompt = "Зроби пост для Telegram українською. СТИСЛО!\n1. Яскравий заголовок.\n2. 2 речення суті.\n3. 3 головні факти булітами (•).\nБез вступів, без \"Ось переклад\", без **. Джерело не пиши.\n\nЗаголовок: ${item.title.replace("\"", "'").replace("\n", " ").replace("🚀", "")}\nТекст: ${item.description.replace("\"", "'").replace("\n", " ")}"
                    var translatedText: String? = null
                    var attempts = 0

                    while (translatedText == null && attempts < (apiKeys.size * 2)) {
                        LogManager.log("AI_PROCESS", "Спроба ${attempts + 1} для новини: '${item.title.take(20)}...'")
                        
                        if (getActiveKey() == null) { 
                            val allKeysExhausted = apiKeys.isNotEmpty() && apiKeys.all { key ->
                                (keyCooldowns[key] ?: 0L) > System.currentTimeMillis() + 3600000L
                            }
                            
                            if (isGloballyBlocked() && allKeysExhausted) {
                                LogManager.log("AI_ERR", "УСІ ключі вичерпали ліміти. Чергу зупинено до 10:00.")
                                isQueueStopped = true
                                break
                            }
                            LogManager.log("AI_WAIT", "Немає активних ключів, чекаємо 15 сек...")
                            delay(15000)
                            attempts++ 
                            continue
                        }
                        
                        val models = listOf("gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.6-flash"); translatedText = callGeminiApi(prompt, models[attempts % 3])
                        if (translatedText == "[SAFETY_BLOCK]") break
                        if (translatedText == null) attempts++
                    }

                    val finalItem = if (!translatedText.isNullOrEmpty() && translatedText != "[SAFETY_BLOCK]") {
                        var cleanResult = translatedText.replace("**", "").trim().replace("(?i)^текст новини:\\s*".toRegex(), "")
                        val parts = cleanResult.split("\n", limit = 2)
                        val rawTitle = parts.getOrNull(0)?.replace(Regex("^[#*\\s🚀]+"), "")?.trim() ?: item.title
                        var newDesc = parts.getOrNull(1)?.trim() ?: cleanResult
                        val sourceIndex = newDesc.indexOf("Джерело:", ignoreCase = true)
                        if (sourceIndex != -1) newDesc = newDesc.substring(0, sourceIndex).trimEnd(' ', '\n', '•', '\r')
                        val sourceLinkHtml = if (item.link.isNotEmpty()) "• <b>Джерело:</b> <a href=\"${item.link}\">${item.source}</a>" else "• <b>Джерело:</b> ${item.source}"
                        item.copy(title = rawTitle, description = "$newDesc\n\n• Джерело: ${item.source}", telegramCaption = "🚀 <b>$rawTitle</b> 🚀\n\n$newDesc\n\n$sourceLinkHtml", status = "Готово")
                    } else {
                        if (translatedText == "[SAFETY_BLOCK]") LogManager.log("AI_WARN", "Пропущено (Safety): '${item.title.take(20)}...'")
                        val cleanOrigTitle = item.title.replace("🚀", "").trim()
                        item.copy(title = cleanOrigTitle, description = "${item.description}\n\n• Джерело: ${item.source}", status = "Не перекладено")
                    }
                    onItemProcessed(finalItem)
                } catch (e: Exception) {
                    LogManager.log("AI_ITEM_ERR", "Помилка обробки новини: ${e.message}")
                    onItemProcessed(item.copy(status = "Не перекладено"))
                } finally {
                    processingNewsIds.remove(item.id)
                }
            }
        } finally { context?.let { NewsProcessingService.stop(it) } }
    }

    suspend fun callGeminiApi(prompt: String, modelName: String = "gemini-3.8-flash"): String? {
        enforceRateLimit()
        val active = getActiveKey()
        
        if (active == null) {
            LogManager.log("AI_REQ", "❌ Немає доступних ключів для виклику API.")
            return null
        }
        
        val apiKey = active.first
        val keyIndex = active.second 

        incrementKeyUsage(keyIndex)
        LogManager.log("AI_REQ", "🔄 Відправка (Ключ №${keyIndex + 1}, Модель: $modelName)...")

        return try {
            val response = client.post("https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey") {
                contentType(ContentType.Application.Json)
                setBody(JSONObject().apply { 
                    put("contents", JSONArray().apply { put(JSONObject().apply { put("parts", JSONArray().apply { put(JSONObject().apply { put("text", prompt) }) }) }) }) 
                    put("safetySettings", JSONArray().apply {
                        listOf("HARM_CATEGORY_HARASSMENT", "HARM_CATEGORY_HATE_SPEECH", "HARM_CATEGORY_SEXUALLY_EXPLICIT", "HARM_CATEGORY_DANGEROUS_CONTENT").forEach { category ->
                            put(JSONObject().apply { put("category", category); put("threshold", "BLOCK_NONE") })
                        }
                    })
                }.toString())
            }

            val respBody = response.bodyAsText()
            LogManager.log("AI_RES", "📥 Отримано статус: ${response.status.value} (Ключ №${keyIndex + 1})")
            
            val cleanBodyLog = respBody.replace("\n", " ").let { if (it.length > 250) it.take(250) + "..." else it }
            LogManager.log("AI_RES_BODY", "Тіло: $cleanBodyLog")
            
            if (response.status.value == 200) {
                val json = JSONObject(respBody)
                val candidates = json.optJSONArray("candidates")
                val text = candidates?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")
                
                if (text.isNullOrEmpty()) {
                    val finishReason = candidates?.optJSONObject(0)?.optString("finishReason", "UNKNOWN")
                    if (finishReason == "SAFETY") {
                        LogManager.log("AI_WARN", "⚠️ Ключ №${keyIndex + 1}: Заблоковано фільтром безпеки.")
                        return "[SAFETY_BLOCK]"
                    }
                    LogManager.log("AI_WARN", "⚠️ Ключ №${keyIndex + 1}: Порожній текст. Причина: $finishReason")
                    return null
                }
                
                LogManager.log("AI_OK", "✅ Ключ №${keyIndex + 1}: Текст згенеровано (${text.length} симв.)")
                text
            } else {
                val errBody = respBody.lowercase()
                if (response.status.value == 401 || errBody.contains("api_key_invalid")) {
                    LogManager.log("AI_ERR", "🚫 Ключ №${keyIndex + 1} недійсний (401). Блок 24г.")
                    keyCooldowns[apiKey] = System.currentTimeMillis() + 60_000L
                } else if (response.status.value == 429) {
                    val usageCount = if (isInitialized) prefs.getInt("key_count_$keyIndex", 0) else 0
                    // Якщо лічильник малий, це точно RPM/TPM хвилинний ліміт, а не кінець денної квоти
                    if (usageCount >= 1400 || errBody.contains("billing") || errBody.contains("quota")) {
                        LogManager.log("AI_ERR", "🛑 Ключ №${keyIndex + 1}: Ліміт API (429). Пауза 60с.")
                        keyCooldowns[apiKey] = System.currentTimeMillis() + 60_000L
                    } else {
                        LogManager.log("AI_WARN", "⏳ Ключ №${keyIndex + 1}: Ліміт RPM/TPM (429). Пауза 60с.")
                        keyCooldowns[apiKey] = System.currentTimeMillis() + 60_000L
                    }
                } else if (response.status.value == 404) {
                    LogManager.log("AI_ERR", "❌ Ключ №${keyIndex + 1}: Модель $modelName не знайдено (404).")
                    keyCooldowns[apiKey] = System.currentTimeMillis() + 60_000L
                } else {
                    LogManager.log("AI_ERR", "⚠️ Помилка HTTP ${response.status.value}. Пауза 30с.")
                    keyCooldowns[apiKey] = System.currentTimeMillis() + 30_000L
                }
                null
            }
        } catch (e: Exception) {
            LogManager.log("AI_CRASH", "💥 Збій запиту до Gemini: ${e.message}")
            keyCooldowns[apiKey] = System.currentTimeMillis() + 15_000L
            null 
        }
    }
}
