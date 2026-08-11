package com.newsapp.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

    companion object {
        private const val TAG = "NewsViewModelTag"
    }

    private val _newsList = MutableStateFlow<List<NewsItem>>(emptyList())
    val newsList: StateFlow<List<NewsItem>> = _newsList.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _showLimitError = MutableStateFlow(false)
    val showLimitError: StateFlow<Boolean> = _showLimitError.asStateFlow()

    private val client = HttpClient(CIO) {
        followRedirects = true
    }
    
    private val aiRewriter = AiRewriter()
    private val telegramBotService = TelegramBotService()

    private val rssUrls = listOf(
        "https://www.nasa.gov/news-release/feed/",
        "https://www.space.com/feeds/all",
        "https://phys.org/rss-feed/space-news/",
        "https://www.sciencedaily.com/rss/space_time.xml"
    )

    init {
        loadNews()
    }

    fun dismissLimitError() {
        _showLimitError.value = false
    }

    fun loadNews() {
        if (_newsList.value.isEmpty()) {
            _isLoading.value = true
        }
        _errorMessage.value = null

        viewModelScope.launch(Dispatchers.IO) {
            Log.d(TAG, "=== ПОЧАТОК ЗАВАНТАЖЕННЯ RSS ===")
            val rawNews = mutableListOf<NewsItem>()

            for (url in rssUrls) {
                try {
                    val response: HttpResponse = client.get(url) {
                        header(HttpHeaders.UserAgent, "Mozilla/5.0")
                    }
                    if (response.status.value in 200..299) {
                        val host = URL(url).host
                        val parsedItems = parseRss(response.bodyAsText(), host)
                        Log.d(TAG, "Знайдено ${parsedItems.size} новин з $host")
                        rawNews.addAll(parsedItems)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "NETWORK_ERROR для $url", e)
                }
            }

            if (rawNews.isEmpty() && _newsList.value.isEmpty()) {
                _errorMessage.value = "Не вдалося завантажити новини з джерел."
                _isLoading.value = false
                return@launch
            }

            if (rawNews.isNotEmpty()) {
                val initialData = rawNews.map { 
                    it.copy(status = "В черзі", telegramCaption = "Обробка...") 
                }
                
                if (_newsList.value.isEmpty()) {
                    _newsList.value = initialData
                }
                _isLoading.value = false

                Log.d(TAG, "=== ЗАПУСК ШІ ОБРОБКИ ДЛЯ ${rawNews.size} НОВИН ===")
                try {
                    aiRewriter.processAllNewsWithAi(rawNews) { finishedItem ->
                        Log.d(TAG, "ШІ обробив новину: ${finishedItem.title.take(25)}")
                        _newsList.value = _newsList.value.map { current ->
                            if (current.id == finishedItem.id || current.title == finishedItem.title) {
                                finishedItem
                            } else {
                                current
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "ПОМИЛКА ШІ ОБРОБКИ", e)
                }
            } else {
                _isLoading.value = false
            }
        }
    }

    fun toggleEdit(id: String) {
        _newsList.value = _newsList.value.map {
            if (it.id == id) it.copy(isEditing = !it.isEditing) else it
        }
    }

    fun updateNewsText(id: String, newText: String) {
        _newsList.value = _newsList.value.map {
            if (it.id == id) {
                val newCaption = "🚀 <b>${it.title}</b>\n\n$newText\n\n• <b>Джерело:</b> ${it.source}"
                it.copy(description = newText, telegramCaption = newCaption)
            } else item@{ it }
        }
    }

    fun sendNews(newsItem: NewsItem) {
        viewModelScope.launch(Dispatchers.IO) {
            telegramBotService.sendNewsToChannel(newsItem)
            _newsList.value = _newsList.value.map {
                if (it.id == newsItem.id) it.copy(status = "Опубліковано") else it
            }
        }
    }

    private fun parseRss(xml: String, sourceName: String): List<NewsItem> {
        val items = mutableListOf<NewsItem>()
        try {
            val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
            val parser = factory.newPullParser().apply { setInput(StringReader(xml)) }

            var eventType = parser.eventType
            var currentTitle: String? = null
            var currentLink: String? = null
            var currentDesc: String? = null
            var currentImage: String = ""
            var insideItem = false
            var currentTag = ""

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.name ?: ""
                        currentTag = name.lowercase()
                        
                        if (currentTag == "item" || currentTag == "entry") {
                            insideItem = true
                            currentTitle = null
                            currentLink = null
                            currentDesc = null
                            currentImage = ""
                        } else if (insideItem) {
                            if (currentTag == "link") {
                                val href = parser.getAttributeValue(null, "href")
                                if (!href.isNullOrEmpty()) currentLink = href
                            } else if (currentTag == "enclosure" || currentTag == "content" || currentTag == "thumbnail") {
                                val urlAttr = parser.getAttributeValue(null, "url")
                                if (!urlAttr.isNullOrEmpty() && (currentImage.isEmpty() || currentTag == "enclosure")) {
                                    currentImage = urlAttr
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (insideItem) {
                            val text = parser.text ?: ""
                            if (text.isNotBlank()) {
                                when (currentTag) {
                                    "title" -> currentTitle = (currentTitle ?: "") + text
                                    "link" -> if (currentLink.isNullOrEmpty()) currentLink = text
                                    "description", "summary", "content" -> currentDesc = (currentDesc ?: "") + text
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val name = parser.name ?: ""
                        if (name.equals("item", ignoreCase = true) || name.equals("entry", ignoreCase = true)) {
                            if (!currentTitle.isNullOrEmpty()) {
                                if (currentImage.isEmpty() && !currentDesc.isNullOrEmpty()) {
                                    val imgMatch = Regex("src=\"(https?://[^\"]+)\"").find(currentDesc!!)
                                    if (imgMatch != null) {
                                        currentImage = imgMatch.groupValues[1]
                                    }
                                }

                                items.add(
                                    NewsItem(
                                        title = currentTitle!!.trim(),
                                        link = currentLink?.trim() ?: "",
                                        description = currentDesc?.replace(Regex("<.*?>"), "")?.trim() ?: "",
                                        source = sourceName,
                                        image = currentImage
                                    )
                                )
                            }
                            insideItem = false
                        }
                        currentTag = ""
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) { Log.e(TAG, "PARSER_ERROR", e) }
        return items
    }
}
