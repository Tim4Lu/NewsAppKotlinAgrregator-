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

    private val _showLimitError = MutableStateFlow(false)
    val showLimitError: StateFlow<Boolean> = _showLimitError.asStateFlow()

    private val client = HttpClient(CIO)
    private val aiRewriter = AiRewriter()
    private val telegramBotService = TelegramBotService()

    private val rssUrls = listOf(
        "https://www.nasa.gov/rss/dyn/breaking_news.rss",
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
            Log.d(TAG, "=== START: ПОЧАТОК ЗАВАНТАЖЕННЯ НОВИН ===")

            try {
                val rawNews = mutableListOf<NewsItem>()

                for (url in rssUrls) {
                    try {
                        Log.d(TAG, "NETWORK -> Запит: $url")
                        val response: HttpResponse = client.get(url) {
                            header(HttpHeaders.UserAgent, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        }
                        
                        Log.d(TAG, "NETWORK -> Відповідь від $url: Код status=${response.status}")
                        val xmlBody = response.bodyAsText()
                        Log.d(TAG, "NETWORK -> Отримано XML розміром: ${xmlBody.length} символів")

                        val parsedItems = parseRss(xmlBody)
                        Log.d(TAG, "PARSER -> Розпарсено новин з $url: ${parsedItems.size}")
                        rawNews.addAll(parsedItems)

                    } catch (e: Exception) {
                        Log.e(TAG, "NETWORK_ERROR -> Не вдалося завантажити $url", e)
                    }
                }

                Log.d(TAG, "RAW_SUMMARY -> Загальна кількість сирих новин: ${rawNews.size}")

                if (rawNews.isEmpty()) {
                    Log.e(TAG, "DIAGNOSTIC -> Порожній список! Всі RSS джерела повернули 0 новин або заблокували запити.")
                }

                val processedNews = mutableListOf<NewsItem>()

                for (item in rawNews) {
                    Log.d(TAG, "AI_REWRITE -> Обробка: ${item.title}")
                    
                    var translatedTitle: String? = null
                    var translatedDesc: String? = null

                    try {
                        translatedTitle = aiRewriter.rewrite(item.title, "Ukrainian")
                        translatedDesc = aiRewriter.rewrite(item.description, "Ukrainian")
                    } catch (limitEx: LimitExceededException) {
                        Log.e(TAG, "LIMIT_EXCEEDED -> Вичерпано ліміт API!", limitEx)
                        _showLimitError.value = true
                    } catch (aiEx: Exception) {
                        Log.e(TAG, "AI_ERROR -> Помилка рерайту для: ${item.title}", aiEx)
                    }

                    if (!translatedTitle.isNullOrEmpty()) {
                        val localizedItem = item.copy(
                            title = translatedTitle,
                            description = if (!translatedDesc.isNullOrEmpty()) translatedDesc else item.description
                        )
                        processedNews.add(localizedItem)
                        Log.d(TAG, "AI_SUCCESS -> Перекладено: ${localizedItem.title}")
                    } else {
                        Log.w(TAG, "AI_FALLBACK -> AI не повернув текст. Виводимо оригінал: ${item.title}")
                        processedNews.add(item)
                    }
                }

                _newsList.value = processedNews
                Log.d(TAG, "=== FINISH: Успішно оновлено стейт. Новин всього: ${processedNews.size} ===")

            } catch (e: Exception) {
                Log.e(TAG, "CRITICAL -> Загальний збій у fetchAndProcessNews", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun sendNews(newsItem: NewsItem) {
        viewModelScope.launch(Dispatchers.IO) {
            Log.d(TAG, "TELEGRAM -> Відправка: ${newsItem.title}")
            telegramBotService.sendNewsToChannel(newsItem)
        }
    }

    private fun parseRss(xml: String): List<NewsItem> {
        val items = mutableListOf<NewsItem>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var eventType = parser.eventType
            var currentTitle: String? = null
            var currentLink: String? = null
            var currentDesc: String? = null
            var insideItem = false

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val name = parser.name
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (name.equals("item", ignoreCase = true)) {
                            insideItem = true
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (insideItem) {
                            when {
                                name.equals("title", ignoreCase = true) -> currentTitle = parser.text
                                name.equals("link", ignoreCase = true) -> currentLink = parser.text
                                name.equals("description", ignoreCase = true) -> currentDesc = parser.text
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (name.equals("item", ignoreCase = true)) {
                            if (!currentTitle.isNullOrEmpty()) {
                                items.add(
                                    NewsItem(
                                        title = currentTitle.trim(),
                                        link = currentLink?.trim() ?: "",
                                        description = currentDesc?.trim() ?: ""
                                    )
                                )
                            }
                            currentTitle = null
                            currentLink = null
                            currentDesc = null
                            insideItem = false
                        }
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
