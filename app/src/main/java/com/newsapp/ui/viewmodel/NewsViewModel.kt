package com.newsapp.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.newsapp.data.LogManager
import com.newsapp.data.NewsCacheManager
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
    
    private val telegramBotService = TelegramBotService()
    private val cacheManager = NewsCacheManager(application)

    private val rssUrls = listOf(
        "https://www.nasa.gov/feed/",
        "https://www.nasa.gov/news-release/feed/",
        "https://blogs.nasa.gov/feed/",
        "https://www.esa.int/rssfeed/TopNews",
        "https://www.esa.int/rssfeed/Our_Activities/Space_Science",
        "https://www.space.com/feeds/all/",
        "https://www.universetoday.com/feed",
        "https://www.spacedaily.com/spacedaily.xml",
        "https://phys.org/rss-feed/space-news/",
        "https://www.sciencedaily.com/rss/space_time.xml",
        "https://www.nature.com/subjects/physical-sciences.rss"
    )

    init {
        viewModelScope.launch {
            loadCachedNews()
            loadNews()
        }
    }

    private fun String.normalizeUrl(): String {
        return this.lowercase()
            .replace(Regex("^https?://"), "")
            .replace(Regex("^www\\."), "")
            .split("?")[0]
            .trimEnd('/')
    }

    private suspend fun loadCachedNews() {
        LogManager.log("TRACE", "Викликано функцію: loadCachedNews")
        val cached = cacheManager.loadNews()
        if (cached.isNotEmpty()) {
            val threeDaysAgo = System.currentTimeMillis() - (3L * 24 * 60 * 60 * 1000)
            val uniqueCached = cached.distinctBy {
                val normLink = it.link.normalizeUrl()
                if (normLink.isNotEmpty()) normLink else it.originalTitle.ifEmpty { it.title }
            }.filter { it.timestamp > threeDaysAgo }

            _newsList.value = uniqueCached.sortedByDescending { it.timestamp }
            saveNewsToDisk(_newsList.value)
        }
    }

    private fun saveNewsToDisk(list: List<NewsItem>) {
        LogManager.log("TRACE", "Викликано функцію: saveNewsToDisk")
        viewModelScope.launch {
            cacheManager.saveNews(list)
        }
    }

    fun checkAndRetryUntranslatedNews() {
        LogManager.log("TRACE", "Викликано функцію: checkAndRetryUntranslatedNews")
        val untranslated = _newsList.value.filter { 
            it.status == "В черзі" 
        }
        if (untranslated.isNotEmpty()) {
            if (AiRewriter.isGloballyBlocked()) {
                LogManager.log("AI_AUTO", "ШІ чекає до ${AiRewriter.getBlockTimeFormatted()}.")
                return
            }
            LogManager.log("AI_AUTO_RETRY", "Автоперевірка: знайдено ${untranslated.size} неперекладених новин. Запуск ШІ...")
            viewModelScope.launch(Dispatchers.IO) {
                processNewsWithScraperAndAi(untranslated)
            }
        } else {
            LogManager.log("AI_AUTO_RETRY", "Автоперевірка: усі новини вже перекладені.")
        }
    }

    fun loadNews() {
        LogManager.log("TRACE", "Викликано функцію: loadNews")
        if (_newsList.value.isEmpty()) _isLoading.value = true

        viewModelScope.launch(Dispatchers.IO) {
            val rawNews = mutableListOf<NewsItem>()

            for (url in rssUrls) {
                try {
                    LogManager.log("FETCH", "Запит: ${url.take(45)}...")
                    var fetchedItems = listOf<NewsItem>()
                    var successDirect = false

                    val response = client.get(url) {
                        header(HttpHeaders.UserAgent, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        header(HttpHeaders.Accept, "application/rss+xml, application/xml, text/xml")
                    }

                    LogManager.log("FETCH", "HTTP ${response.status.value} для ${url.take(30)}")

                    if (response.status.value in 200..299) {
                        val bodyText = response.bodyAsText()
                        LogManager.log("FETCH", "Розмір тіла: ${bodyText.length} симв.")
                        
                        val parser = NewsParserFactory.getParser(url)
                        fetchedItems = parser.parse(bodyText)
                        
                        if (fetchedItems.isNotEmpty()) {
                            successDirect = true
                            LogManager.log("FETCH_OK", "Знайдено ${fetchedItems.size} новин (прямо)")
                        } else {
                            LogManager.log("FETCH_WARN", "Парсер не знайшов новин (можливо XML змінився)")
                        }
                    } else {
                        LogManager.log("FETCH_ERR", "Помилка сервера: HTTP ${response.status.value}")
                    }

                    if (!successDirect) {
                        LogManager.log("FETCH", "Спроба через резервний rss2json...")
                        val cleanUrl = if (url.contains("allorigins")) url.substringAfter("url=") else url
                        val apiUrl = "https://api.rss2json.com/v1/api.json?rss_url=${URLEncoder.encode(cleanUrl, "UTF-8")}"
                        
                        val jsonResponse = client.get(apiUrl)
                        LogManager.log("FETCH", "rss2json HTTP: ${jsonResponse.status.value}")
                        
                        if (jsonResponse.status.value in 200..299) {
                            val jsonBody = jsonResponse.bodyAsText()
                            LogManager.log("FETCH", "rss2json тіло: ${jsonBody.length} симв.")
                            
                            val json = JSONObject(jsonBody)
                            if (json.optString("status") == "ok") {
                                val itemsArray = json.optJSONArray("items")
                                val fallbackItems = mutableListOf<NewsItem>()
                                
                                val sourceName = when {
                                    cleanUrl.contains("nasa.gov") -> "NASA"
                                    cleanUrl.contains("esa.int") -> "ESA"
                                    cleanUrl.contains("space.com") -> "Space.com"
                                    cleanUrl.contains("spacedaily") -> "Space Daily"
                                    cleanUrl.contains("universetoday") -> "Universe Today"
                                    cleanUrl.contains("phys.org") -> "Phys.org"
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
                                LogManager.log("FETCH_OK", "Знайдено ${fetchedItems.size} новин (через rss2json)")
                            } else {
                                LogManager.log("FETCH_ERR", "Помилка rss2json: ${json.optString("message")}")
                            }
                        }
                    }
                    rawNews.addAll(fetchedItems)
                } catch (e: Exception) {
                    LogManager.log("FETCH_ERR", "Критичний збій завантаження ${url.take(30)}: ${e.message}")
                }
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

                val maxAgeMillis = System.currentTimeMillis() - (3L * 24 * 60 * 60 * 1000)
                val freshNews = uniqueRawNews.filter { item ->
                    val normTitle = item.title.trim().lowercase()
                    val origTitle = item.originalTitle.trim().lowercase()
                    val normLink = item.link.normalizeUrl()

                    val isTitleDuplicate = existingTitles.contains(normTitle) || (origTitle.isNotEmpty() && existingTitles.contains(origTitle))
                    val isLinkDuplicate = normLink.isNotEmpty() && existingLinks.contains(normLink)
                    val isRecent = item.timestamp > maxAgeMillis

                    !isTitleDuplicate && !isLinkDuplicate && isRecent
                }

                if (freshNews.isNotEmpty()) {
                    val freshInitial = freshNews.map { it.copy(status = "В черзі", telegramCaption = "Обробка...") }
                    _newsList.value = (freshInitial + _newsList.value).sortedByDescending { it.timestamp }
                    saveNewsToDisk(_newsList.value)
                }
                
                _isLoading.value = false
                checkAndRetryUntranslatedNews()
            } else {
                _isLoading.value = false
                checkAndRetryUntranslatedNews()
            }
        }
    }

    private suspend fun scrapeArticle(url: String): Triple<String, List<String>, Boolean> {
        try {
            if (url.isEmpty()) return Triple("", emptyList(), false)
            val response = client.get(url) {
                io.ktor.client.request.header(io.ktor.http.HttpHeaders.UserAgent, "Mozilla/5.0")
            }
            val html = io.ktor.client.statement.bodyAsText(response)
            val imageList = mutableListOf<String>()

            val ogMatch = Regex("<meta[^>]+(?:property|name)=[\"'](?:og:image|twitter:image)[\"'][^>]+content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(html)
            if (ogMatch != null) {
                var img = ogMatch.groupValues[1]
                if (!img.startsWith("http")) { val b = java.net.URL(url); img = "${b.protocol}://${b.host}$img" }
                imageList.add(img)
            }

            val imgMatches = Regex("<img[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).findAll(html)
            val badWords = listOf("logo", "banner", "icon", "avatar", "sponsor", "advert", "sidebar", "footer", ".svg", ".gif")
            for (m in imgMatches) {
                var imgSrc = m.groupValues[1]
                if (!imgSrc.startsWith("http")) { 
                    try { val b = java.net.URL(url); imgSrc = "${b.protocol}://${b.host}$imgSrc" } catch(e:Exception){} 
                }
                if (imgSrc.startsWith("http") && badWords.none { imgSrc.lowercase().contains(it) }) {
                    if (!imageList.contains(imgSrc)) imageList.add(imgSrc)
                }
            }

            val cleanHtml = html.replace(Regex("<(nav|header|footer|script|style|button|aside|noscript)[^>]*>[\\s\\S]*?<\\/\\1>", RegexOption.IGNORE_CASE), "")
            val scrapedText = Regex("<p[^>]*>(.*?)</p>", RegexOption.IGNORE_CASE).findAll(cleanHtml).map { it.groupValues[1].replace(Regex("<[^>]*>"), "").trim() }.filter { it.length > 80 && it.contains(".") }.joinToString("\n\n")
            val hasVideo = html.contains("<video", ignoreCase=true) || html.contains("<iframe", ignoreCase=true) || html.contains("og:video", ignoreCase=true)
            
            return Triple(if (scrapedText.length >= 150) scrapedText else "", imageList, hasVideo)
        } catch (e: Exception) { 
            return Triple("", emptyList(), false) 
        }
    }
    private suspend fun processNewsWithScraperAndAi(rawNews: List<NewsItem>) {
        LogManager.log("TRACE", "Викликано функцію: processNewsWithScraperAndAi")
        val updatedList = rawNews.map { item ->
            val (fullText, scrapedImages, hasVid) = scrapeArticle(item.link)
            item.copy(
                description = if (fullText.isNotEmpty()) fullText else item.description,
                image = if (scrapedImages.isNotEmpty()) scrapedImages.first() else item.image,
                    images = if (scrapedImages.isNotEmpty()) scrapedImages else if (item.image.isNotEmpty()) listOf(item.image) else emptyList(),
                    hasVideo = hasVid
            )
        }

        AiRewriter.processAllNewsWithAi(updatedList, getApplication()) { finishedItem ->
            _newsList.value = _newsList.value.map { current ->
                if (current.id == finishedItem.id || current.title == finishedItem.title) finishedItem else current
            }
            saveNewsToDisk(_newsList.value)
        }
    }

    fun rewriteSingleNews(newsItem: NewsItem) {
        LogManager.log("TRACE", "Викликано функцію: rewriteSingleNews")
        viewModelScope.launch(Dispatchers.IO) {
            _newsList.value = _newsList.value.map {
                if (it.id == newsItem.id) it.copy(status = "Переклад...", telegramCaption = "Обробка AI...") else it
            }
            AiRewriter.processAllNewsWithAi(listOf(newsItem), getApplication()) { finishedItem ->
                _newsList.value = _newsList.value.map { if (it.id == newsItem.id) finishedItem else it }
                saveNewsToDisk(_newsList.value)
            }
        }
    }

    fun toggleEdit(id: String) {
        LogManager.log("TRACE", "Викликано функцію: toggleEdit")
        _newsList.value = _newsList.value.map { if (it.id == id) it.copy(isEditing = !it.isEditing) else it }
    }

    fun updateNewsText(id: String, newTitle: String, newText: String) {
        LogManager.log("TRACE", "Викликано функцію: updateNewsText")
        _newsList.value = _newsList.value.map {
            if (it.id == id) {
                val cleanTitle = newTitle.replace("🚀", "").trim()
                var cleanDesc = newText
                val sourceIndex = cleanDesc.indexOf("Джерело:", ignoreCase = true)
                if (sourceIndex != -1) {
                    cleanDesc = cleanDesc.substring(0, sourceIndex).trimEnd(' ', '\n', '•', '\r')
                }
                
                it.copy(
                    title = cleanTitle,
                    description = cleanDesc, 
                    telegramCaption = "🚀 <b>$cleanTitle</b> 🚀\n\n$cleanDesc\n\n• <b>Джерело:</b> ${it.source}"
                )
            } else it
        }
        saveNewsToDisk(_newsList.value)
    }

    fun sendNews(newsItem: NewsItem) {
        LogManager.log("TRACE", "Викликано функцію: sendNews")
        if (newsItem.status == "Опубліковано" || newsItem.status == "Відправляється...") {
            LogManager.log("TELEGRAM", "Блокування подвійного кліку: новина вже ${newsItem.status}")
            return
        }

        if (newsItem.status == "В черзі" || newsItem.status == "Переклад...") {
            LogManager.log("TELEGRAM", "Блокування: новина ще обробляється ШІ, зачекайте.")
            return
        }

        _newsList.value = _newsList.value.map { 
            if (it.id == newsItem.id) it.copy(status = "Відправляється...") else it 
        }

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            LogManager.log("TELEGRAM", "Надсилання новини: ${newsItem.title}")
            val success = telegramBotService.sendToTelegram(newsItem.telegramCaption, selectedImages.ifEmpty { listOf(newsItem.image).filter { it.isNotEmpty() } })
            
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
