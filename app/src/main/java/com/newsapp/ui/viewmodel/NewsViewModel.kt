package com.newsapp.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
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
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.URL

class NewsViewModel : ViewModel() {

    private val _newsList = MutableStateFlow<List<NewsItem>>(emptyList())
    val newsList: StateFlow<List<NewsItem>> = _newsList.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val client = HttpClient(CIO) { followRedirects = true }
    private val aiRewriter = AiRewriter()
    private val telegramBotService = TelegramBotService()

    private val rssUrls = listOf(
        "https://www.nasa.gov/news-release/feed/",
        "https://www.space.com/feeds/all"
    )

    init { loadNews() }

    fun loadNews() {
        if (_newsList.value.isEmpty()) _isLoading.value = true

        viewModelScope.launch(Dispatchers.IO) {
            LogManager.log("ViewModel", "Завантаження RSS...")
            val rawNews = mutableListOf<NewsItem>()

            for (url in rssUrls) {
                try {
                    val response = client.get(url) { header(HttpHeaders.UserAgent, "Mozilla/5.0") }
                    if (response.status.value in 200..299) {
                        rawNews.addAll(parseRss(response.bodyAsText(), URL(url).host))
                    }
                } catch (e: Exception) {
                    LogManager.log("ViewModel_ERR", "Помилка RSS: ${e.message}")
                }
            }

            if (rawNews.isNotEmpty()) {
                if (_newsList.value.isEmpty()) {
                    _newsList.value = rawNews.map { it.copy(status = "В черзі", telegramCaption = "Обробка...") }
                }
                _isLoading.value = false

                // Запускаємо фоновий скрейпінг og:image та тексту статті + ШІ
                processNewsWithScraperAndAi(rawNews)
            } else {
                _isLoading.value = false
            }
        }
    }

    // Точна реалізація твоєї функції scrapeNasaArticle
    private suspend fun scrapeArticle(url: String): Pair<String, String?> {
        try {
            LogManager.log("Scraper", "Скрейпінг сторінки: ${url.take(30)}...")
            val response: HttpResponse = client.get(url) {
                header(HttpHeaders.UserAgent, "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            }
            val html = response.bodyAsText()

            // 1. Шукаємо зображення (og:image) ПЕРЕД тим, як чистити HTML
            val ogImageRegex = Regex("<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            var imageUrl = ogImageRegex.find(html)?.groupValues?.get(1)

            // Якщо og:image немає, шукаємо звичайний тег img
            if (imageUrl.isNullOrEmpty()) {
                val imgRegex = Regex("<img[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                imageUrl = imgRegex.find(html)?.groupValues?.get(1)
            }

            // 2. Прибираємо блоки-сміття
            val cleanHtml = html.replace(Regex("<(nav|header|footer|script|style|button|aside|noscript)[^>]*>[\\s\\S]*?<\\/\\1>", RegexOption.IGNORE_CASE), "")

            // 3. Беремо абзаци <p> більше 50 символів
            val pTags = Regex("<p[^>]*>([^<]{50,})<\\/p>", RegexOption.IGNORE_CASE).findAll(cleanHtml).map { it.groupValues[1] }.toList()

            val validParagraphs = pTags
                .map { it.replace(Regex("<[^>]*>"), "").trim() }
                .filter { t ->
                    t.length > 100 &&
                    t.contains(".") &&
                    !Regex("menu|login|copyright|privacy|contact|subscribe|home|search", RegexOption.IGNORE_CASE).containsMatchIn(t)
                }

            val scrapedText = validParagraphs.take(4).joinToString("\n\n")

            return Pair(if (scrapedText.length >= 200) scrapedText else "", imageUrl)
        } catch (e: Exception) {
            LogManager.log("Scraper_ERR", "Помилка скрейпінгу: ${e.message}")
            return Pair("", null)
        }
    }

    private suspend fun processNewsWithScraperAndAi(rawNews: List<NewsItem>) {
        val updatedList = rawNews.map { item ->
            // Викликаємо скрейпер для кожної статті за її посиланням
            val (fullText, scrapedImage) = scrapeArticle(item.link)
            item.copy(
                description = if (fullText.isNotEmpty()) fullText else item.description,
                image = scrapedImage ?: item.image
            )
        }

        // Передаємо оновлені новини із реальним фото та текстом до ШІ
        aiRewriter.processAllNewsWithAi(updatedList) { finishedItem ->
            _newsList.value = _newsList.value.map { current ->
                if (current.id == finishedItem.id || current.title == finishedItem.title) finishedItem else current
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
    }

    fun sendNews(newsItem: NewsItem) {
        viewModelScope.launch(Dispatchers.IO) {
            telegramBotService.sendNewsToChannel(newsItem)
            _newsList.value = _newsList.value.map { if (it.id == newsItem.id) it.copy(status = "Опубліковано") else it }
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
                                items.add(NewsItem(title!!.trim(), link?.trim() ?: "", desc?.replace(Regex("<.*?>"), "")?.trim() ?: "", sourceName))
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
