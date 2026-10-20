package com.newsapp.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.newsapp.BuildConfig
import com.newsapp.data.LogManager
import com.newsapp.data.NewsCacheManager
import com.newsapp.data.SnapiParser
import com.newsapp.data.NewsParserFactory
import com.newsapp.data.api.TelegramBotService
import com.newsapp.data.api.AiRewriter
import com.newsapp.model.NewsItem
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.endpoint
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URLEncoder

class NewsViewModel(application: Application) : AndroidViewModel(application) {

    private val _newsList = MutableStateFlow<List<NewsItem>>(emptyList())
    val newsList: StateFlow<List<NewsItem>> = _newsList.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val client = HttpClient(CIO) { 
        expectSuccess = false 
        followRedirects = true
        engine { requestTimeout = 30_000; endpoint { connectTimeout = 30_000; socketTimeout = 30_000 } } 
    }
    
    private val telegramBotService = TelegramBotService()
    private val cacheManager = NewsCacheManager(application)

    private val rssUrls = listOf(
        "https://www.nasa.gov/feed/",
        "https://science.nasa.gov/feed/",
        "https://www.esa.int/rssfeed/TopNews",
        "https://www.esa.int/rssfeed/Our_Activities/Space_Science",
        "https://www.nature.com/subjects/astronomy-and-planetary-science.rss",
        "https://www.universetoday.com/feed",
        "https://www.spacedaily.com/spacedaily.xml",
        "https://phys.org/rss-feed/space-news",
        "https://www.space.com/feeds/all"
    )

    init {
        AiRewriter.init(application)
        viewModelScope.launch { loadCachedNews(); loadNews() }
    }

    private fun String.normalizeUrl() = this.lowercase().replace(Regex("^https?://"), "").replace(Regex("^www\\."), "").split("?")[0].trimEnd('/')

    private suspend fun loadCachedNews() {
        val cached = cacheManager.loadNews()
        if (cached.isNotEmpty()) {
            val threeDaysAgo = System.currentTimeMillis() - (7L * 24 * 60 * 60 * 1000)
            val uniqueCached = cached.distinctBy {
                val normLink = it.link.normalizeUrl()
                if (normLink.isNotEmpty()) normLink else it.originalTitle.ifEmpty { it.title }
            }.filter { it.timestamp > threeDaysAgo }
            _newsList.value = uniqueCached.sortedByDescending { it.timestamp }
            cacheManager.saveNews(_newsList.value)
        }
    }

    fun checkAndRetryUntranslatedNews() {
        val untranslated = _newsList.value.filter { it.status == "В черзі" || it.status == "Ліміт AI" }
        if (untranslated.isNotEmpty() && !AiRewriter.isGloballyBlocked()) {
            viewModelScope.launch(Dispatchers.IO) { processNewsWithScraperAndAi(untranslated) }
        }
    }

    fun loadNews() {
        if (_newsList.value.isEmpty()) _isLoading.value = true

        viewModelScope.launch(Dispatchers.IO) {
            val rawNews = mutableListOf<NewsItem>()

            for (url in rssUrls) {
                try {
                    LogManager.log("FETCH", "Запит: ${url.take(40)}")
                    var fetchedItems = listOf<NewsItem>()
                    val parser = NewsParserFactory.getParser(url)
                    val isHardBlocked = url.contains("spacenews") || url.contains("space.com") || url.contains("spacedaily") || url.contains("phys.org") || url.contains("universetoday")

                    if (isHardBlocked) {
                        val scraperKey = BuildConfig.SCRAPER_API_KEY
                        if (true) {
                            try {
                                val proxyUrl = "https://script.google.com/macros/s/AKfycbyPXHUAKXY3Cf3UKFumrDfF_LjhTRi5gK6-YGgsnrOgbYmFVn59GbWl860qyEHvtuam/exec?url=${URLEncoder.encode(url, "UTF-8")}"
                                val response = client.get(proxyUrl)
                                if (response.status.value in 200..299) {
                                    fetchedItems = parser.parse(response.bodyAsText())
                                    if (fetchedItems.isNotEmpty()) LogManager.log("FETCH_OK", "ScraperAPI: ${fetchedItems.size} новин")
                                } else { LogManager.log("FETCH_ERR", "GoogleProxy відхилив запит HTTP ${response.status.value}") }
                            } catch (e: Exception) { LogManager.log("FETCH_ERR", "ScraperAPI fail: ${e.message}") }
                        } else {
                            LogManager.log("FETCH_WARN", "Jina fallback: $url")
                        }
                    } else {
                        try {
                            val response = client.get(url) {
                                header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                                header("Accept", "application/xml, text/xml")
                            }
                            if (response.status.value in 200..299) {
                                val xml = response.bodyAsText()
                                if (xml.contains("<rss") || xml.contains("<feed") || xml.contains("<?xml")) {
                                    fetchedItems = parser.parse(xml)
                                    if (fetchedItems.isNotEmpty()) LogManager.log("FETCH_OK", "Direct: ${fetchedItems.size} новин")
                                }
                            }
                        } catch (e: Exception) {}

                        if (fetchedItems.isEmpty()) {
                            try {
                                val proxyUrl = "https://api.allorigins.win/get?disableCache=true&url=${URLEncoder.encode(url, "UTF-8")}"
                                val response = client.get(proxyUrl)
                                if (response.status.value in 200..299) {
                                    val json = JSONObject(response.bodyAsText())
                                    val xml = json.optString("contents", "")
                                    if (xml.contains("<rss") || xml.contains("<feed") || xml.contains("<?xml")) {
                                        fetchedItems = parser.parse(xml)
                                        if (fetchedItems.isNotEmpty()) LogManager.log("FETCH_OK", "AllOrigins: ${fetchedItems.size} новин")
                                    }
                                }
                            } catch (e: Exception) {}
                        }
                    }
                    rawNews.addAll(fetchedItems)
                } catch (e: Exception) {
                    LogManager.log("FETCH_FATAL", "Збій ${url.take(30)}: ${e.message}")
                }
            }

            // SNAPI — окремі джерела, які не можна отримати через RSS
        // SNAPI — окремий API, не блокується Cloudflare
        for (site in listOf("SpaceNews")) {
            rawNews.addAll(SnapiParser.fetch(site, 15))
        }

            if (rawNews.isNotEmpty()) {
                val existingTitles = _newsList.value.flatMap { listOf(it.title.trim().lowercase(), it.originalTitle.trim().lowercase()) }.filter { it.isNotEmpty() }.toSet()
                val existingLinks = _newsList.value.map { it.link.normalizeUrl() }.filter { it.isNotEmpty() }.toSet()

                val uniqueRawNews = rawNews.distinctBy { 
                    val norm = it.link.normalizeUrl()
                    if (norm.isNotEmpty()) norm else it.originalTitle.trim().lowercase()
                }

                val maxAgeMillis = System.currentTimeMillis() - (7L * 24 * 60 * 60 * 1000)
                val freshNews = uniqueRawNews.filter { item ->
                    val normTitle = item.title.trim().lowercase()
                    val origTitle = item.originalTitle.trim().lowercase()
                    val normLink = item.link.normalizeUrl()
                    val isTitleDuplicate = existingTitles.contains(normTitle) || (origTitle.isNotEmpty() && existingTitles.contains(origTitle))
                    val isLinkDuplicate = normLink.isNotEmpty() && existingLinks.contains(normLink)
                    !isTitleDuplicate && !isLinkDuplicate && (item.timestamp > maxAgeMillis)
                }

                if (freshNews.isNotEmpty()) {
                    val freshInitial = freshNews.map { it.copy(status = "В черзі", telegramCaption = "Обробка...") }
                    _newsList.value = (freshInitial + _newsList.value).sortedByDescending { it.timestamp }.take(250)
                    cacheManager.saveNews(_newsList.value)
                }
            }
            _isLoading.value = false
            checkAndRetryUntranslatedNews()
        }
    }

    private data class ScrapeResult(val text: String, val images: List<String>, val hasVideo: Boolean, val videoUrl: String = "")

    private suspend fun scrapeArticle(url: String): ScrapeResult {
        try {
            if (url.isEmpty()) return ScrapeResult("", emptyList(), false)
            
            val scraperKey = BuildConfig.SCRAPER_API_KEY
            val finalUrl = if (url.contains("spacenews") || url.contains("space.com") || url.contains("spacedaily") || url.contains("phys.org") || url.contains("universetoday")) {
                "https://r.jina.ai/${URLEncoder.encode(url, "UTF-8")}"
            } else { url }

            val response = client.get(finalUrl) { header("User-Agent", "Mozilla/5.0") }
            val html = response.bodyAsText()
            val imageList = mutableListOf<String>()

            val ogMatch = Regex("<meta[^>]+(?:property|name)=['\"](?:og:image|twitter:image)['\"][^>]+content=['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE).find(html)
            if (ogMatch != null) {
                var img = ogMatch.groupValues[1]
                if (!img.startsWith("http")) { val b = java.net.URL(url); img = "${b.protocol}://${b.host}$img" }
                imageList.add(img)
            }

            var videoUrl = ""
            val ogVideo = Regex("<meta[^>]+(?:property|name)=['\"](?:og:video|og:video:url|twitter:player|twitter:player:stream)['\"][^>]+content=['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE).find(html)
            if (ogVideo != null) { videoUrl = ogVideo.groupValues[1] }
            if (videoUrl.isEmpty()) {
                val srcMatch = Regex("<video[^>]*>.*?<source[^>]+src=['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE or RegexOption.DOT_MATCHES_ALL).find(html)
                if (srcMatch != null) videoUrl = srcMatch.groupValues[1]
            }
            if (videoUrl.isEmpty()) {
                val ytMatch = Regex("(?:youtube\\.com/(?:watch\\?v=|embed/|shorts/)|youtu\\.be/)([A-Za-z0-9_-]{11})").find(html)
                if (ytMatch != null) videoUrl = "https://www.youtube.com/watch?v=${ytMatch.groupValues[1]}"
            }
            if (videoUrl.isNotEmpty() && !videoUrl.startsWith("http")) {
                try { val b = java.net.URL(url); videoUrl = "${b.protocol}://${b.host}$videoUrl" } catch (_: Exception) {}
            }

            val cleanHtml = html.replace(Regex("<(nav|header|footer|script|style|button|aside|noscript)[^>]*>[\\s\\S]*?<\\/\\1>", RegexOption.IGNORE_CASE), "")
            val scrapedText = Regex("<p[^>]*>(.*?)</p>", RegexOption.IGNORE_CASE).findAll(cleanHtml).map { it.groupValues[1].replace(Regex("<[^>]*>"), "").trim() }.filter { it.length > 80 && it.contains(".") }.joinToString("\n\n")
            val hasVideo = videoUrl.isNotEmpty() || html.contains("<video", ignoreCase=true) || html.contains("<iframe", ignoreCase=true) || html.contains("og:video", ignoreCase=true)
            return ScrapeResult(if (scrapedText.length >= 150) scrapedText else "", imageList, hasVideo, videoUrl)
        } catch (e: Exception) { return ScrapeResult("", emptyList(), false) }
    }

    private suspend fun processNewsWithScraperAndAi(rawNews: List<NewsItem>) {
        val updatedList = rawNews.map { item ->
            val r = scrapeArticle(item.link)
            item.copy(
                description = if (r.text.isNotEmpty()) r.text else item.description,
                image = if (r.images.isNotEmpty()) r.images.first() else item.image,
                images = if (r.images.isNotEmpty()) r.images else if (item.image.isNotEmpty()) listOf(item.image) else emptyList(),
                hasVideo = r.hasVideo,
                videoUrl = r.videoUrl
            )
        }

        AiRewriter.processAllNewsWithAi(updatedList, getApplication()) { finishedItem ->
            _newsList.value = _newsList.value.map { current ->
                if (current.id == finishedItem.id || current.title == finishedItem.title) finishedItem else current
            }
            cacheManager.saveNews(_newsList.value)
        }
    }

    fun rewriteSingleNews(newsItem: NewsItem) {
        viewModelScope.launch(Dispatchers.IO) {
            _newsList.value = _newsList.value.map { if (it.id == newsItem.id) it.copy(status = "Переклад...", telegramCaption = "Обробка AI...") else it }
            AiRewriter.processAllNewsWithAi(listOf(newsItem), getApplication()) { finishedItem ->
                _newsList.value = _newsList.value.map { if (it.id == newsItem.id) finishedItem else it }
                cacheManager.saveNews(_newsList.value)
            }
        }
    }

    fun toggleEdit(id: String) {
        _newsList.value = _newsList.value.map { if (it.id == id) it.copy(isEditing = !it.isEditing) else it }
    }

    fun updateNewsText(id: String, newTitle: String, newText: String) {
        _newsList.value = _newsList.value.map {
            if (it.id == id) {
                val cleanTitle = newTitle.replace("🚀", "").trim()
                var cleanDesc = newText
                val sourceIndex = cleanDesc.indexOf("Джерело:", ignoreCase = true)
                if (sourceIndex != -1) cleanDesc = cleanDesc.substring(0, sourceIndex).trimEnd(' ', '\n', '•', '\r')
                it.copy(description = cleanDesc, telegramCaption = "🚀 <b>$cleanTitle</b> 🚀\n\n$cleanDesc\n\n• <b>Джерело:</b> ${it.source}")
            } else it
        }
        viewModelScope.launch { cacheManager.saveNews(_newsList.value) }
    }

    fun sendNews(newsItem: NewsItem, selectedImages: List<String> = emptyList()) {
        if (newsItem.status == "Опубліковано" || newsItem.status == "Відправляється..." || newsItem.status == "В черзі") return
        _newsList.value = _newsList.value.map { if (it.id == newsItem.id) it.copy(status = "Відправляється...") else it }
        viewModelScope.launch(Dispatchers.IO) {
            val success = telegramBotService.sendToTelegram(
                newsItem.telegramCaption,
                if (selectedImages.isNotEmpty()) selectedImages else if (newsItem.image.isNotEmpty()) listOf(newsItem.image) else emptyList(),
                newsItem.videoUrl
            )
            _newsList.value = _newsList.value.map { if (it.id == newsItem.id) it.copy(status = if (success) "Опубліковано" else "Помилка") else it }
            cacheManager.saveNews(_newsList.value)
        }
    }
}
