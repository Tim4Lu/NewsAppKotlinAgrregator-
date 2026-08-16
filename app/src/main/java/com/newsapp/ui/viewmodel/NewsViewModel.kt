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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL

class NewsViewModel(application: Application) : AndroidViewModel(application) {

    private val _newsList = MutableStateFlow<List<NewsItem>>(emptyList())
    val newsList: StateFlow<List<NewsItem>> = _newsList.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val client = HttpClient(CIO) { followRedirects = true }
    private val aiRewriter = AiRewriter()
    private val telegramBotService = TelegramBotService()
    private val cacheFile = File(application.filesDir, "saved_news.json")

    private val rssUrls = listOf(
        "https://www.nasa.gov/rss/dyn/breaking_news.rss",
        "https://www.space.com/feeds/all",
        "https://www.universetoday.com/feed",
        "https://www.spacedaily.com/spacedaily.xml",
        "https://phys.org/rss-feed/space-news/"
    )

    init {
        loadCachedNews()
        loadNews()
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
                            link = obj.optString("link"),
                            description = obj.optString("description"),
                            source = obj.optString("source"),
                            image = obj.optString("image"),
                            status = obj.optString("status", "Готово"),
                            telegramCaption = obj.optString("telegramCaption"), timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                        )
                    )
                }
                _newsList.value = cached
                LogManager.log("Cache", "Завантажено ${cached.size} новин із диска")
            }
        } catch (e: Exception) {
            LogManager.log("Cache_ERR", "Збій кешу: ${e.message}")
        }
    }

    private fun saveNewsToDisk(list: List<NewsItem>) {
        try {
            val jsonArray = JSONArray()
            list.filter { it.status == "Готово" || it.status == "Опубліковано" }.forEach { item ->
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("title", item.title)
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
        } catch (e: Exception) {
            LogManager.log("Cache_ERR", "Збій збереження: ${e.message}")
        }
    }

    fun loadNews() {
        if (_newsList.value.isEmpty()) _isLoading.value = true

        viewModelScope.launch(Dispatchers.IO) {
            val rawNews = mutableListOf<NewsItem>()

            for (url in rssUrls) {
                try {
                    val response = client.get(url) {
                        header(HttpHeaders.UserAgent, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                        header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                        header(HttpHeaders.AcceptLanguage, "en-US,en;q=0.5")
                    }
                    if (response.status.value in 200..299) {
                        val xml = response.bodyAsText()
                        val parser = NewsParserFactory.getParser(url)
                        val parsedItems = parser.parse(xml)
                        rawNews.addAll(parsedItems)
                    }
                } catch (e: Exception) {
                    LogManager.log("RSS_ERR", "Збій завантаження $url: ${e.message}")
                }
            }

            if (rawNews.isNotEmpty()) {
                val existingTitles = _newsList.value.map { it.title.trim().lowercase() }.toSet()
                val existingLinks = _newsList.value.map { it.link.trim().lowercase() }.toSet()

                val freshNews = rawNews.filter { item ->
                    val cleanTitle = item.title.trim().lowercase()
                    val cleanLink = item.link.trim().lowercase()
                    cleanTitle.isNotEmpty() &&
                            !existingTitles.contains(cleanTitle) &&
                            (cleanLink.isEmpty() || !existingLinks.contains(cleanLink))
                }

                if (freshNews.isNotEmpty()) {
                    LogManager.log("NEWS", "Знайдено ${freshNews.size} нових новин")
                    val freshInitial = freshNews.map { it.copy(status = "В черзі", telegramCaption = "Обробка...") }
                    
                    // Обєднуємо і сортуємо за часом від найновішого
                    val combinedList = freshInitial + _newsList.value
                    _newsList.value = combinedList.sortedByDescending { it.timestamp }
                    _isLoading.value = false

                    processNewsWithScraperAndAi(freshNews)
                } else {
                    LogManager.log("NEWS", "Нових новин немає")
                    _isLoading.value = false
                }
            } else {
                _isLoading.value = false
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
            if (ogMatch != null) {
                imageUrl = ogMatch.groupValues[1]
            }

            if (imageUrl.isNullOrEmpty()) {
                val jsonLdMatch = Regex("\"image\"\\s*:\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(html)
                if (jsonLdMatch != null) {
                    imageUrl = jsonLdMatch.groupValues[1]
                }
            }

            if (imageUrl.isNullOrEmpty()) {
                val imgMatch = Regex("<img[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(html)
                if (imgMatch != null) {
                    imageUrl = imgMatch.groupValues[1]
                }
            }

            if (!imageUrl.isNullOrEmpty() && !imageUrl.startsWith("http")) {
                val baseUrl = URL(url)
                imageUrl = "${baseUrl.protocol}://${baseUrl.host}$imageUrl"
            }

            val cleanHtml = html.replace(Regex("<(nav|header|footer|script|style|button|aside|noscript)[^>]*>[\\s\\S]*?<\\/\\1>", RegexOption.IGNORE_CASE), "")
            val pTags = Regex("<p[^>]*>([^<]{50,})<\\/p>", RegexOption.IGNORE_CASE).findAll(cleanHtml).map { it.groupValues[1] }.toList()
            val validParagraphs = pTags
                .map { it.replace(Regex("<[^>]*>"), "").trim() }
                .filter { t -> t.length > 100 && t.contains(".") }

            val scrapedText = validParagraphs.take(4).joinToString("\n\n")
            return Pair(if (scrapedText.length >= 200) scrapedText else "", imageUrl)

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

        aiRewriter.processAllNewsWithAi(updatedList) { finishedItem ->
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
            aiRewriter.processAllNewsWithAi(listOf(newsItem)) { finishedItem ->
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
            if (it.id == id) it.copy(description = newText, telegramCaption = "🚀 <b>${it.title}</b>\n\n$newText\n\n• <b>Джерело:</b> ${it.source}") else it
        }
        saveNewsToDisk(_newsList.value)
    }

    fun sendNews(newsItem: NewsItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = telegramBotService.sendToTelegram(newsItem.telegramCaption, newsItem.image)
            if (success) {
                _newsList.value = _newsList.value.map { if (it.id == newsItem.id) it.copy(status = "Опубліковано") else it }
                saveNewsToDisk(_newsList.value)
            }
        }
    }
}
