package com.newsapp.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.newsapp.data.LogManager
import com.newsapp.data.NewsParserFactory
import com.newsapp.data.api.TelegramBotService
import com.newsapp.data.api.AiRewriter
import com.newsapp.model.NewsItem
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

class NewsViewModel(application: Application) : AndroidViewModel(application) {

    private val _newsList = MutableStateFlow<List<NewsItem>>(emptyList())
    val newsList: StateFlow<List<NewsItem>> = _newsList.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val client = HttpClient(CIO) { 
        expectSuccess = false 
        followRedirects = true 
    }
    
    private val aiRewriter = AiRewriter()
    private val telegramBotService = TelegramBotService()
    private val cacheFile = File(application.filesDir, "saved_news.json")

    private val rssUrls = listOf(
        "https://www.nasa.gov/feed/",
        "https://www.space.com/feeds/all",
        "https://www.universetoday.com/feed",
        "https://www.spacedaily.com/spacedaily.xml",
        "https://phys.org/rss-feed/space-news/"
    )

    init {
        loadCachedNews()
        loadNews()
    }

    private fun String.normalizeUrl(): String {
        return this.lowercase()
            .replace(Regex("^https?://"), "")
            .replace(Regex("^www\\."), "")
            .split("?")[0]
            .trimEnd('/')
    }

    private fun loadCachedNews() {
        try {
            if (cacheFile.exists()) {
                val jsonText = cacheFile.readText()
                val jsonArray = JSONArray(jsonText)
                val cached = mutableListOf<NewsItem>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    cached.add(
                        NewsItem(
                            id = obj.optString("id"),
                            title = obj.optString("title"),
                            originalTitle = obj.optString("originalTitle"),
                            link = obj.optString("link"),
                            description = obj.optString("description"),
                            source = obj.optString("source"),
                            image = obj.optString("image"),
                            status = obj.optString("status", "Готово"),
                            telegramCaption = obj.optString("telegramCaption"),
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                        )
                    )
                }

                val uniqueCached = cached.distinctBy {
                    val normLink = it.link.normalizeUrl()
                    if (normLink.isNotEmpty()) normLink else it.originalTitle.ifEmpty { it.title }
                }

                _newsList.value = uniqueCached.sortedByDescending { it.timestamp }
                saveNewsToDisk(_newsList.value)
                checkAndRetryUntranslatedNews()
            }
        } catch (e: Exception) {}
    }

    private fun saveNewsToDisk(list: List<NewsItem>) {
        try {
            val jsonArray = JSONArray()
            list.take(250).forEach { item ->
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("title", item.title)
                    put("originalTitle", item.originalTitle)
                    put("link", item.link)
                    put("description", item.description)
                    put("source", item.source)
                    put("image", item.image)
                    put("status", item.status)
                    put("telegramCaption", item.telegramCaption)
                    put("timestamp", item.timestamp)
                }
                jsonArray.put(obj)
            }
            cacheFile.writeText(jsonArray.toString())
        } catch (e: Exception) {}
    }

    fun checkAndRetryUntranslatedNews() {
        val untranslated = _newsList.value.filter { 
            it.status == "Не перекладено" || it.status == "В черзі" 
        }
        if (untranslated.isNotEmpty()) {
            LogManager.log("AI_AUTO_RETRY", "Автоперевірка: знайдено ${untranslated.size} неперекладених новин. Запуск ШІ...")
            viewModelScope.launch(Dispatchers.IO) {
                processNewsWithScraperAndAi(untranslated)
            }
        } else {
            LogManager.log("AI_AUTO_RETRY", "Автоперевірка: усі новини вже перекладені.")
        }
    }

    fun loadNews() {
        if (_newsList.value.isEmpty()) _isLoading.value = true

        viewModelScope.launch(Dispatchers.IO) {
            val rawNews = mutableListOf<NewsItem>()

            for (url in rssUrls) {
                try {
                    var fetchedItems = listOf<NewsItem>()
                    var successDirect = false

                    val response = client.get(url) {
                        header(HttpHeaders.UserAgent, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        header(HttpHeaders.Accept, "application/rss+xml, application/xml, text/xml")
                    }

                    if (response.status.value in 200..299) {
                        val parser = NewsParserFactory.getParser(url)
                        fetchedItems = parser.parse(response.bodyAsText())
                        if (fetchedItems.isNotEmpty()) successDirect = true
                    }

                    if (!successDirect && !url.contains("nasa.gov")) {
                        val apiUrl = "https://api.rss2json.com/v1/api.json?rss_url=${URLEncoder.encode(url, "UTF-8")}"
                        val jsonResponse = client.get(apiUrl)
                        if (jsonResponse.status.value in 200..299) {
                            val json = JSONObject(jsonResponse.bodyAsText())
                            if (json.optString("status") == "ok") {
                                val itemsArray = json.optJSONArray("items")
                                val fallbackItems = mutableListOf<NewsItem>()
                                
                                val sourceName = when {
                                    url.contains("nasa.gov") -> "NASA"
                                    url.contains("space.com") -> "Space.com"
                                    url.contains("spacedaily") -> "Space Daily"
                                    url.contains("universetoday") -> "Universe Today"
                                    url.contains("phys.org") -> "Phys.org"
                                    else -> "Новина"
                                }

                                for (i in 0 until (itemsArray?.length() ?: 0)) {
                                    val obj = itemsArray!!.getJSONObject(i)
                                    var rawTitle = obj.optString("title").replace("(?i)APOD:\\s*(-\\s*)?".toRegex(), "").trim()
                                    var rawDesc = obj.optString("description", "").replace(Regex("<[^>]*>"), "").trim()
                                    if (rawDesc.length > 300) rawDesc = rawDesc.take(300) + "..."
                                    
                                    var img = obj.optString("thumbnail", "")
                                    if (img.isEmpty()) {
                                        val enc = obj.optJSONObject("enclosure")
                                        if (enc != null) img = enc.optString("link", "")
                                    }

                                    var ts = System.currentTimeMillis()
                                    val pubDate = obj.optString("pubDate", "")
                                    if (pubDate.isNotEmpty()) {
                                        try {
                                            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
                                            ts = sdf.parse(pubDate)?.time ?: ts
                                        } catch(e: Exception) {}
                                    }

                                    fallbackItems.add(
                                        NewsItem(
                                            title = rawTitle,
                                            originalTitle = obj.optString("title"),
                                            link = obj.optString("link").split(" ")[0],
                                            description = rawDesc,
                                            source = sourceName,
                                            image = img,
                                            timestamp = ts
                                        )
                                    )
                                }
                                fetchedItems = fallbackItems
                            }
                        }
                    }
                    rawNews.addAll(fetchedItems)
                } catch (e: Exception) {}
            }

            if (rawNews.isNotEmpty()) {
                val existingTitles = _newsList.value.flatMap {
                    listOf(it.title.trim().lowercase(), it.originalTitle.trim().lowercase())
                }.filter { it.isNotEmpty() }.toSet()

                val existingLinks = _newsList.value.map { it.link.normalizeUrl() }.filter { it.isNotEmpty() }.toSet()

                val uniqueRawNews = rawNews.distinctBy { 
                    val norm = it.link.normalizeUrl()
                    if (norm.isNotEmpty()) norm else it.originalTitle.trim().lowercase()
                }

                val freshNews = uniqueRawNews.filter { item ->
                    val normTitle = item.title.trim().lowercase()
                    val origTitle = item.originalTitle.trim().lowercase()
                    val normLink = item.link.normalizeUrl()

                    val isTitleDuplicate = existingTitles.contains(normTitle) || (origTitle.isNotEmpty() && existingTitles.contains(origTitle))
                    val isLinkDuplicate = normLink.isNotEmpty() && existingLinks.contains(normLink)

                    !isTitleDuplicate && !isLinkDuplicate
                }

                if (freshNews.isNotEmpty()) {
                    val freshInitial = freshNews.map { it.copy(status = "В черзі", telegramCaption = "Обробка...") }
                    _newsList.value = (freshInitial + _newsList.value).sortedByDescending { it.timestamp }
                    saveNewsToDisk(_newsList.value)
                    _isLoading.value = false

                    processNewsWithScraperAndAi(freshNews)
                } else {
                    _isLoading.value = false
                    checkAndRetryUntranslatedNews()
                }
            } else {
                _isLoading.value = false
                checkAndRetryUntranslatedNews()
            }
        }
    }

    private suspend fun scrapeArticle(url: String): Pair<String, String?> {
        try {
            if (url.isEmpty()) return Pair("", null)
            val response: HttpResponse = client.get(url) {
                header(HttpHeaders.UserAgent, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            }
            val html = response.bodyAsText()
            var imageUrl: String? = null

            val ogMatch = Regex("<meta[^>]+(?:property|name)=[\"'](?:og:image|twitter:image)[\"'][^>]+content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(html)
            if (ogMatch != null) imageUrl = ogMatch.groupValues[1]

            if (!imageUrl.isNullOrEmpty() && !imageUrl.startsWith("http")) {
                val baseUrl = URL(url)
                imageUrl = "${baseUrl.protocol}://${baseUrl.host}$imageUrl"
            }

            val cleanHtml = html.replace(Regex("<(nav|header|footer|script|style|button|aside|noscript)[^>]*>[\\s\\S]*?<\\/\\1>", RegexOption.IGNORE_CASE), "")
            val pMatches = Regex("<p[^>]*>(.*?)</p>", RegexOption.IGNORE_CASE).findAll(cleanHtml)
            val validParagraphs = pMatches
                .map { it.groupValues[1].replace(Regex("<[^>]*>"), "").trim() }
                .filter { t -> t.length > 80 && t.contains(".") }
                .toList()

            val scrapedText = validParagraphs.joinToString("\n\n")
            return Pair(if (scrapedText.length >= 150) scrapedText else "", imageUrl)
        } catch (e: Exception) {
            return Pair("", null)
        }
    }

    private suspend fun processNewsWithScraperAndAi(rawNews: List<NewsItem>) {
        val updatedList = rawNews.map { item ->
            val (fullText, scrapedImage) = scrapeArticle(item.link)
            item.copy(
                description = if (fullText.isNotEmpty()) fullText else item.description,
                image = if (!scrapedImage.isNullOrEmpty()) scrapedImage else item.image
            )
        }

        aiRewriter.processAllNewsWithAi(updatedList, getApplication()) { finishedItem ->
            _newsList.value = _newsList.value.map { current ->
                if (current.id == finishedItem.id || current.title == finishedItem.title) finishedItem else current
            }
            saveNewsToDisk(_newsList.value)
        }
    }

    fun rewriteSingleNews(newsItem: NewsItem) {
        viewModelScope.launch(Dispatchers.IO) {
            _newsList.value = _newsList.value.map {
                if (it.id == newsItem.id) it.copy(status = "Переклад...", telegramCaption = "Обробка AI...") else it
            }
            aiRewriter.processAllNewsWithAi(listOf(newsItem), getApplication()) { finishedItem ->
                _newsList.value = _newsList.value.map { if (it.id == newsItem.id) finishedItem else it }
                saveNewsToDisk(_newsList.value)
            }
        }
    }

    fun toggleEdit(id: String) {
        _newsList.value = _newsList.value.map { if (it.id == id) it.copy(isEditing = !it.isEditing) else it }
    }

    fun updateNewsText(id: String, newText: String) {
        _newsList.value = _newsList.value.map {
            if (it.id == id) {
                val cleanTitle = it.title.replace("🚀", "").trim()
                var cleanDesc = newText
                val sourceIndex = cleanDesc.indexOf("Джерело:", ignoreCase = true)
                if (sourceIndex != -1) {
                    cleanDesc = cleanDesc.substring(0, sourceIndex).trimEnd(' ', '\n', '•', '\r')
                }
                
                it.copy(
                    description = cleanDesc, 
                    telegramCaption = "🚀 <b>$cleanTitle</b> 🚀\n\n$cleanDesc\n\n• <b>Джерело:</b> ${it.source}"
                )
            } else it
        }
        saveNewsToDisk(_newsList.value)
    }

    suspend fun sendNews(newsItem: NewsItem) {
        if (newsItem.status == "Опубліковано" || newsItem.status == "Відправляється...") {
            LogManager.log("TELEGRAM", "Блокування подвійного кліку: новина вже ${newsItem.status}")
            return
        }

        _newsList.value = _newsList.value.map { 
            if (it.id == newsItem.id) it.copy(status = "Відправляється...") else it 
        }

        withContext(Dispatchers.IO) {
            LogManager.log("TELEGRAM", "Надсилання новини: ${newsItem.title}")
            val success = telegramBotService.sendToTelegram(newsItem.telegramCaption, newsItem.image)
            
            if (success) {
                _newsList.value = _newsList.value.map { 
                    if (it.id == newsItem.id) it.copy(status = "Опубліковано") else it 
                }
                LogManager.log("TELEGRAM", "Успішно опубліковано!")
            } else {
                _newsList.value = _newsList.value.map { 
                    if (it.id == newsItem.id) it.copy(status = "Помилка") else it 
                }
                LogManager.log("TELEGRAM_ERR", "Помилка відправки новини")
            }
            saveNewsToDisk(_newsList.value)
        }
    }
}
