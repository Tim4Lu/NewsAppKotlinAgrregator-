package com.newsapp.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.newsapp.data.AiRewriter
import com.newsapp.data.LimitExceededException
import com.newsapp.data.TelegramBotService
import com.newsapp.model.NewsItem
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
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
            Log.d(TAG, "START: Початок завантаження та обробки новин...")

            try {
                val rawNews = mutableListOf<NewsItem>()

                for (url in rssUrls) {
                    try {
                        Log.d(TAG, "NETWORK: Запит до RSS: $url")
                        val response: HttpResponse = client.get(url)
                        val xmlBody = response.bodyAsText()
                        val parsedItems = parseRss(xmlBody)
                        rawNews.addAll(parsedItems)
                        Log.d(TAG, "NETWORK: Отримано ${parsedItems.size} новин з $url")
                    } catch (e: Exception) {
                        Log.e(TAG, "NETWORK_ERROR: Помилка завантаження з $url", e)
                    }
                }

                Log.d(TAG, "RAW_TOTAL: Всього сирих новин завантажено: ${rawNews.size}")

                val processedNews = mutableListOf<NewsItem>()

                for (item in rawNews) {
                    Log.d(TAG, "AI_REWRITE: Запуск обробки: ${item.title}")
                    
                    var translatedTitle: String? = null
                    var translatedDesc: String? = null

                    try {
                        translatedTitle = aiRewriter.rewrite(item.title, "Ukrainian")
                        translatedDesc = aiRewriter.rewrite(item.description, "Ukrainian")
                    } catch (limitEx: LimitExceededException) {
                        Log.e(TAG, "LIMIT_EXCEEDED: Вичерпано ліміти сервісів!", limitEx)
                        _showLimitError.value = true
                    } catch (aiEx: Exception) {
                        Log.e(TAG, "AI_REWRITE_EXCEPTION: Загальна помилка рерайту", aiEx)
                    }

                    if (!translatedTitle.isNullOrEmpty()) {
                        val localizedItem = item.copy(
                            title = translatedTitle,
                            description = if (!translatedDesc.isNullOrEmpty()) translatedDesc else item.description
                        )
                        processedNews.add(localizedItem)
                        Log.d(TAG, "AI_REWRITE: Успішно перекладено: ${localizedItem.title}")
                    } else {
                        Log.e(TAG, "AI_REWRITE: AI не відпрацював, залишаємо оригінал: ${item.title}")
                        processedNews.add(item)
                    }
                }

                _newsList.value = processedNews
                Log.d(TAG, "FINISH: Список оновлено. Всього новин: ${processedNews.size}")

            } catch (e: Exception) {
                Log.e(TAG, "CRITICAL: Загальна помилка у fetchAndProcessNews", e)
            } finally {
                _isLoading.value = false
                Log.d(TAG, "END: Процес завершено.")
            }
        }
    }

    fun sendNews(newsItem: NewsItem) {
        viewModelScope.launch(Dispatchers.IO) {
            Log.d(TAG, "TELEGRAM_ACTION: Запуск відправки в Telegram -> ${newsItem.title}")
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
                            if (currentTitle != null) {
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
            Log.e(TAG, "PARSER_ERROR: Помилка парсингу XML", e)
        }
        return items
    }
}
