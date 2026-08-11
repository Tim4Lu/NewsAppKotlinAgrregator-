package com.newsapp.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.newsapp.data.AiRewriter
import com.newsapp.data.LimitExceededException
import com.newsapp.data.TelegramBotService
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

    // Вмикаємо автоматичний перехід за редіректами (для виправлення 301 помилок)
    private val client = HttpClient(CIO) {
        followRedirects = true
    }
    
    private val aiRewriter = AiRewriter()
    private val telegramBotService = TelegramBotService()

    private val rssUrls = listOf(
        "https://www.nasa.gov/news-release/feed/", // Оновлений робочий RSS NASA
        "https://www.space.com/feeds/all",
        "https://phys.org/rss-feed/space-news/",
        "https://www.sciencedaily.com/rss/space_time.xml"
    )

    fun loadNews() {
        fetchAndProcessNews()
    }

    fun dismissLimitError() {
        _showLimitError.value = false
    }

    fun fetchAndProcessNews() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _errorMessage.value = null
            Log.d(TAG, "=== START: ПОЧАТОК ЗАВАНТАЖЕННЯ НОВИН ===")

            val rawNews = mutableListOf<NewsItem>()
            val errorsList = mutableListOf<String>()

            for (url in rssUrls) {
                try {
                    Log.d(TAG, "NETWORK -> Запит до $url")
                    val response: HttpResponse = client.get(url) {
                        header(HttpHeaders.UserAgent, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    }
                    
                    val status = response.status.value
                    Log.d(TAG, "NETWORK -> Відповідь від $url: Код HTTP $status")

                    if (status in 200..299) {
                        val xmlBody = response.bodyAsText()
                        val parsedItems = parseRss(xmlBody)
                        
                        if (parsedItems.isNotEmpty()) {
                            rawNews.addAll(parsedItems)
                            Log.d(TAG, "PARSER -> Отримано ${parsedItems.size} новин з $url")
                        } else {
                            val err = "RSS порожній або збій парсингу [HTTP $status]: $url"
                            errorsList.add(err)
                        }
                    } else {
                        val err = "Помилка сервера HTTP $status: $url"
                        errorsList.add(err)
                    }

                } catch (e: Exception) {
                    val err = "${e.javaClass.simpleName}: ${e.message ?: "Збій мережі"} ($url)"
                    errorsList.add(err)
                }
            }

            if (rawNews.isEmpty()) {
                val combinedError = if (errorsList.isNotEmpty()) {
                    "Не вдалося завантажити новини:\n" + errorsList.joinToString("\n")
                } else {
                    "Помилка: Жодне джерело RSS не повернуло новин"
                }
                _errorMessage.value = combinedError
                _isLoading.value = false
                return@launch
            }

            val processedNews = mutableListOf<NewsItem>()

            for (item in rawNews) {
                var translatedTitle: String? = null
                var translatedDesc: String? = null

                try {
                    translatedTitle = aiRewriter.rewrite(item.title, "Ukrainian")
                    translatedDesc = aiRewriter.rewrite(item.description, "Ukrainian")
                } catch (limitEx: LimitExceededException) {
                    _showLimitError.value = true
                } catch (aiEx: Exception) {
                    Log.e(TAG, "AI_ERROR -> Помилка рерайту", aiEx)
                }

                if (!translatedTitle.isNullOrEmpty()) {
                    processedNews.add(
                        item.copy(
                            title = translatedTitle,
                            description = if (!translatedDesc.isNullOrEmpty()) translatedDesc else item.description
                        )
                    )
                } else {
                    processedNews.add(item)
                }
            }

            _newsList.value = processedNews
            _isLoading.value = false
        }
    }

    fun sendNews(newsItem: NewsItem) {
        viewModelScope.launch(Dispatchers.IO) {
            telegramBotService.sendNewsToChannel(newsItem)
        }
    }

    // Повністю переписаний парсер, який правильно читає тексти всередині тегів та розуміє формат Atom/RSS
    private fun parseRss(xml: String): List<NewsItem> {
        val items = mutableListOf<NewsItem>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var eventType = parser.eventType
            var currentTitle: String? = null
            var currentLink: String? = null
            var currentDesc: String? = null
            var insideItem = false
            var currentTag = "" // Відстежуємо, в якому тегу ми зараз знаходимося

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
                        } else if (insideItem && currentTag == "link") {
                            // Формат Atom тримає посилання в атрибуті href
                            val href = parser.getAttributeValue(null, "href")
                            if (!href.isNullOrEmpty()) {
                                currentLink = href
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
                                items.add(
                                    NewsItem(
                                        title = currentTitle!!.trim(),
                                        link = currentLink?.trim() ?: "",
                                        description = currentDesc?.trim() ?: ""
                                    )
                                )
                            }
                            insideItem = false
                        }
                        currentTag = "" // Скидаємо тег
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "PARSER_ERROR -> Помилка XML", e)
        }
        return items
    }
}
