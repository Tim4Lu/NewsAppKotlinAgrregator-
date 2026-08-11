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
        // Автоматично завантажуємо новини при старті
        loadNews()
    }

    fun dismissLimitError() {
        _showLimitError.value = false
    }

    fun loadNews() {
        // Якщо список вже є, не показуємо лоадер на весь екран, робимо оновлення у фоні
        if (_newsList.value.isEmpty()) {
            _isLoading.value = true
        }
        _errorMessage.value = null

        viewModelScope.launch(Dispatchers.IO) {
            Log.d(TAG, "=== START: Завантаження новин у фоні ===")

            val rawNews = mutableListOf<NewsItem>()

            for (url in rssUrls) {
                try {
                    val response: HttpResponse = client.get(url) {
                        header(HttpHeaders.UserAgent, "Mozilla/5.0")
                    }
                    if (response.status.value in 200..299) {
                        val host = URL(url).host
                        val parsedItems = parseRss(response.bodyAsText(), host)
                        rawNews.addAll(parsedItems)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "NETWORK_ERROR -> Не вдалося завантажити $url", e)
                }
            }

            if (rawNews.isEmpty() && _newsList.value.isEmpty()) {
                _errorMessage.value = "Не вдалося завантажити новини з джерел."
                _isLoading.value = false
                return@launch
            }

            if (rawNews.isNotEmpty()) {
                val initialData = rawNews.map { 
                    it.copy(status = "В черзі", description = "Обробка ШІ...", telegramCaption = "Обробка...") 
                }
                
                // Зберігаємо первинні дані на екран одразу
                if (_newsList.value.isEmpty()) {
                    _newsList.value = initialData
                }
                _isLoading.value = false

                // Фонова AI обробка
                processNewsInBackground(initialData)
            } else {
                _isLoading.value = false
            }
        }
    }

    private suspend fun processNewsInBackground(rawData: List<NewsItem>) {
        try {
            Log.d(TAG, "[BG_PROCESS] Фонова обробка ШІ...")
            val processed = aiRewriter.processAllNewsWithAi(rawData)
            
            // Оновлюємо стан без втрати вже відредагованих користувачем даних
            _newsList.value = _newsList.value.map { current ->
                val updated = processed.find { it.id == current.id }
                if (updated != null && current.status == "В черзі") {
                    updated.copy(status = "Готово")
                } else current
            }
            Log.d(TAG, "[BG_PROCESS] Фонове оновлення завершено")
        } catch (e: Exception) {
            Log.e(TAG, "[BG_ERR] Помилка фонової обробки", e)
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
            } else it
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
            var insideItem = false
            var currentTag = ""

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name?.lowercase() ?: ""
                        if (currentTag == "item" || currentTag == "entry") {
                            insideItem = true
                            currentTitle = null; currentLink = null; currentDesc = null
                        } else if (insideItem && currentTag == "link") {
                            val href = parser.getAttributeValue(null, "href")
                            if (!href.isNullOrEmpty()) currentLink = href
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
                                items.add(
                                    NewsItem(
                                        title = currentTitle!!.trim(),
                                        link = currentLink?.trim() ?: "",
                                        description = currentDesc?.trim() ?: "",
                                            source = sourceName
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
