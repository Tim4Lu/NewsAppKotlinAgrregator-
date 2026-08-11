package com.newsapp.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.newsapp.data.LogManager
import com.newsapp.data.TelegramBotService
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
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
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
        "https://www.nasa.gov/news-release/feed/",
        "https://www.space.com/feeds/all"
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
                            telegramCaption = obj.optString("telegramCaption")
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
                    val response = client.get(url) { header(HttpHeaders.UserAgent, "Mozilla/5.0") }
                    if (response.status.value in 200..299) {
                        rawNews.addAll(parseRss(response.bodyAsText(), URL(url).host))
                    }
                } catch (e: Exception) { }
            }

            if (rawNews.isNotEmpty()) {
                val currentTitles = _newsList.value.map { it.title }.toSet()
                val freshNews = rawNews.filter { !currentTitles.contains(it.title) }

                if (freshNews.isNotEmpty()) {
                    val freshInitial = freshNews.map { it.copy(status = "В черзі", telegramCaption = "Обробка...") }
                    _newsList.value = freshInitial + _newsList.value
                    _isLoading.value = false

                    processNewsWithScraperAndAi(freshNews)
                } else {
                    _isLoading.value = false
                }
            } else {
                _isLoading.value = false
            }
        }
    }

    // ПОКРАЩЕНИЙ ПОШУК ОРИГІНАЛЬНИХ ЗОБРАЖЕНЬ ЗІ СТОРІНКИ
    private suspend fun scrapeArticle(url: String): Pair<String, String?> {
        try {
            val response: HttpResponse = client.get(url) {
                header(HttpHeaders.UserAgent, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            }
            val html = response.bodyAsText()

            var imageUrl: String? = null

            // 1. Пошук og:image або twitter:image у meta-тегах
            val ogMatch = Regex("<meta[^>]+(?:property|name)=[\"'](?:og:image|twitter:image)[\"'][^>]+content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(html)
            if (ogMatch != null) {
                imageUrl = ogMatch.groupValues[1]
            }

            // 2. Якщо meta немає — шукаємо в JSON-LD структурі сайту ("image": "https://...")
            if (imageUrl.isNullOrEmpty()) {
                val jsonLdMatch = Regex("\"image\"\\s*:\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(html)
                if (jsonLdMatch != null) {
                    imageUrl = jsonLdMatch.groupValues[1]
                }
            }

            // 3. Якщо і там немає — шукаємо перший тег <img> у тілі статті
            if (imageUrl.isNullOrEmpty()) {
                val imgMatch = Regex("<img[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(html)
                if (imgMatch != null) {
                    imageUrl = imgMatch.groupValues[1]
                }
            }

            // Перетворення відносного шлях (/images/...) у повноцінну URL-адресу
            if (!imageUrl.isNullOrEmpty() && !imageUrl.startsWith("http")) {
                val baseUrl = URL(url)
                imageUrl = "${baseUrl.protocol}://${baseUrl.host}$imageUrl"
            }

            if (!imageUrl.isNullOrEmpty()) {
                LogManager.log("Image_OK", "Знайдено оригінальну картинку: ${imageUrl.take(40)}...")
            } else {
                LogManager.log("Image_WARN", "Оригінальну картинку не знайдено для: $url")
            }

            // Витягуємо текст
            val cleanHtml = html.replace(Regex("<(nav|header|footer|script|style|button|aside|noscript)[^>]*>[\\s\\S]*?<\\/\\1>", RegexOption.IGNORE_CASE), "")
            val pTags = Regex("<p[^>]*>([^<]{50,})<\\/p>", RegexOption.IGNORE_CASE).findAll(cleanHtml).map { it.groupValues[1] }.toList()
            val validParagraphs = pTags
                .map { it.replace(Regex("<[^>]*>"), "").trim() }
                .filter { t -> t.length > 100 && t.contains(".") }

            val scrapedText = validParagraphs.take(4).joinToString("\n\n")
            return Pair(if (scrapedText.length >= 200) scrapedText else "", imageUrl)

        } catch (e: Exception) {
            LogManager.log("Scrape_ERR", "Помилка скрейпінгу сторінки: ${e.message}")
            return Pair("", null)
        }
    }

    private suspend fun processNewsWithScraperAndAi(rawNews: List<NewsItem>) {
        val updatedList = rawNews.map { item ->
            val (fullText, scrapedImage) = scrapeArticle(item.link)
            item.copy(
                description = if (fullText.isNotEmpty()) fullText else item.description,
                image = scrapedImage ?: item.image
            )
        }

        aiRewriter.processAllNewsWithAi(updatedList) { finishedItem ->
            _newsList.value = _newsList.value.map { current ->
                if (current.id == finishedItem.id || current.title == finishedItem.title) finishedItem else current
            }
            saveNewsToDisk(_newsList.value)
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
            telegramBotService.sendNewsToChannel(newsItem)
            _newsList.value = _newsList.value.map { if (it.id == newsItem.id) it.copy(status = "Опубліковано") else it }
            saveNewsToDisk(_newsList.value)
        }
    }

    private fun parseRss(xml: String, sourceName: String): List<NewsItem> {
        val items = mutableListOf<NewsItem>()
        try {
            val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
            parser.setInput(StringReader(xml))

            var eventType = parser.eventType
            var title: String? = null
            var link: String? = null
            var desc: String? = null
            var insideItem = false
            var currentTag = ""

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name?.lowercase() ?: ""
                        if (currentTag == "item" || currentTag == "entry") {
                            insideItem = true; title = null; link = null; desc = null
                        } else if (insideItem && currentTag == "link") {
                            val href = parser.getAttributeValue(null, "href")
                            if (!href.isNullOrEmpty()) link = href
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (insideItem && parser.text?.isNotBlank() == true) {
                            when (currentTag) {
                                "title" -> title = (title ?: "") + parser.text
                                "link" -> if (link.isNullOrEmpty()) link = parser.text
                                "description", "summary" -> desc = (desc ?: "") + parser.text
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val name = parser.name ?: ""
                        if (name.equals("item", true) || name.equals("entry", true)) {
                            if (!title.isNullOrEmpty()) {
                                items.add(NewsItem(title = title!!.trim(), link = link?.trim() ?: "", description = desc?.replace(Regex("<.*?>"), "")?.trim() ?: "", source = sourceName))
                            }
                            insideItem = false
                        }
                        currentTag = ""
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) { }
        return items
    }
}
