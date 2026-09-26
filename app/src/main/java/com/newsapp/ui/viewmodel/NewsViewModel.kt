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
        "https://www.space.com/feeds/all/",
        "https://www.nature.com/subjects/astronomy-and-planetary-science.rss",
        "https://www.universetoday.com/feed/",
        "https://www.spacedaily.com/spacedaily.xml",
        "https://phys.org/rss-feed/space-news/"
    )

    init {
        AiRewriter.init(application)
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
        val cached = cacheManager.loadNews()
        if (cached.isNotEmpty()) {
            val threeDaysAgo = System.currentTimeMillis() - (3L * 24 * 60 * 60 * 1000)
            val uniqueCached = cached.distinctBy {
                val normLink = it.link.normalizeUrl()
                if (normLink.isNotEmpty()) normLink else it.originalTitle.ifEmpty { it.title }
            }.filter { it.timestamp > threeDaysAgo }

            _newsList.value = uniqueCached.sortedByDescending { it.timestamp }
            cacheManager.saveNews(_newsList.value)
        }
    }

    fun checkAndRetryUntranslatedNews() {
        val untranslated = _newsList.value.filter { it.status == "В черзі" }
        if (untranslated.isNotEmpty()) {
            if (AiRewriter.isGloballyBlocked()) return
            viewModelScope.launch(Dispatchers.IO) {
                processNewsWithScraperAndAi(untranslated)
            }
        }
    }

    fun loadNews() {
        if (_newsList.value.isEmpty()) _isLoading.value = true

        viewModelScope.launch(Dispatchers.IO) {
            val rawNews = mutableListOf<NewsItem>()

            for (url in rssUrls) {
                try {
                    LogManager.log("FETCH", "Запит: ${url.take(45)}...")
                    var fetchedItems = listOf<NewsItem>()
                    var successDirect = false

                    val response = client.get(url) {
                        header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
                        header("Accept", "application/rss+xml, application/xml, text/xml;q=0.9, */*;q=0.8")
                        header("Accept-Language", "en-US,en;q=0.9,uk;q=0.8")
                    }

                    if (response.status.value in 200..299) {
                        val parser = NewsParserFactory.getParser(url)
                        fetchedItems = parser.parse(response.bodyAsText())
                        if (fetchedItems.isNotEmpty()) successDirect = true
                    }

                    if (!successDirect) {
                        LogManager.log("FETCH", "Cloudflare блок. Спроба через AllOrigins...")
                        val proxyUrl = "https://api.allorigins.win/get?url=${URLEncoder.encode(url, "UTF-8")}"
                        val proxyResponse = client.get(proxyUrl)
                        
                        if (proxyResponse.status.value in 200..299) {
                            val json = JSONObject(proxyResponse.bodyAsText())
                            val rawXml = json.optString("contents", "")
                            if (rawXml.isNotEmpty()) {
                                val parser = NewsParserFactory.getParser(url)
                                fetchedItems = parser.parse(rawXml)
                                LogManager.log("FETCH_OK", "Проксі успішно витягнув ${fetchedItems.size} новин")
                            }
                        }
                    }
                    rawNews.addAll(fetchedItems)
                } catch (e: Exception) {
                    LogManager.log("FETCH_ERR", "Збій завантаження ${url.take(30)}: ${e.message}")
                }
            }

            if (rawNews.isNotEmpty()) {
                val existingTitles = _newsList.value.flatMap { listOf(it.title.trim().lowercase(), it.originalTitle.trim().lowercase()) }.filter { it.isNotEmpty() }.toSet()
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

    private suspend fun scrapeArticle(url: String): Triple<String, List<String>, Boolean> {
        try {
            if (url.isEmpty()) return Triple("", emptyList(), false)
            val response = client.get(url) { header("User-Agent", "Mozilla/5.0") }
            val html = response.bodyAsText()
            val imageList = mutableListOf<String>()

            val ogMatch = Regex("<meta[^>]+(?:property|name)=['\"](?:og:image|twitter:image)['\"][^>]+content=['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE).find(html)
            if (ogMatch != null) {
                var img = ogMatch.groupValues[1]
                if (!img.startsWith("http")) { val b = java.net.URL(url); img = "${b.protocol}://${b.host}$img" }
                imageList.add(img)
            }

            val cleanHtml = html.replace(Regex("<(nav|header|footer|script|style|button|aside|noscript)[^>]*>[\\s\\S]*?<\\/\\1>", RegexOption.IGNORE_CASE), "")
            val scrapedText = Regex("<p[^>]*>(.*?)</p>", RegexOption.IGNORE_CASE).findAll(cleanHtml).map { it.groupValues[1].replace(Regex("<[^>]*>"), "").trim() }.filter { it.length > 80 && it.contains(".") }.joinToString("\n\n")
            val hasVideo = html.contains("<video", ignoreCase=true) || html.contains("<iframe", ignoreCase=true) || html.contains("og:video", ignoreCase=true)
            return Triple(if (scrapedText.length >= 150) scrapedText else "", imageList, hasVideo)
        } catch (e: Exception) { return Triple("", emptyList(), false) }
    }

    private suspend fun processNewsWithScraperAndAi(rawNews: List<NewsItem>) {
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
            viewModelScope.launch { cacheManager.saveNews(_newsList.value) }
        }
    }

    fun rewriteSingleNews(newsItem: NewsItem) {
        viewModelScope.launch(Dispatchers.IO) {
            _newsList.value = _newsList.value.map { if (it.id == newsItem.id) it.copy(status = "Переклад...", telegramCaption = "Обробка AI...") else it }
            AiRewriter.processAllNewsWithAi(listOf(newsItem), getApplication()) { finishedItem ->
                _newsList.value = _newsList.value.map { if (it.id == newsItem.id) finishedItem else it }
                viewModelScope.launch { cacheManager.saveNews(_newsList.value) }
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
            val success = telegramBotService.sendToTelegram(newsItem.telegramCaption, if (selectedImages.isNotEmpty()) selectedImages else if (newsItem.image.isNotEmpty()) listOf(newsItem.image) else emptyList())
            _newsList.value = _newsList.value.map { if (it.id == newsItem.id) it.copy(status = if (success) "Опубліковано" else "Помилка") else it }
            cacheManager.saveNews(_newsList.value)
        }
    }
}
