package com.newsapp.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.newsapp.data.api.AiRewriter
import com.newsapp.data.api.TelegramBotService
import com.newsapp.data.model.NewsItem
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

class NewsViewModel : ViewModel() {
    private val client = HttpClient(CIO)

    private val _newsList = MutableStateFlow<List<NewsItem>>(emptyList())
    val newsList: StateFlow<List<NewsItem>> = _newsList

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val botToken = "" 
    private val chatId = ""

    // Профільні космічні та наукові джерела
    private val rssUrls = listOf(
        "https://www.nasa.gov/rss/dyn/breaking_news.rss",
        "https://www.space.com/feeds/all",
        "https://phys.org/rss-feed/space-news/",
        "https://www.sciencedaily.com/rss/space_time.xml"
    )

    fun loadNews() {
        viewModelScope.launch {
            Log.d("NewsViewModel", "[LOG] Початок завантаження космічних новин...")
            _isLoading.value = true
            try {
                val allNews = mutableListOf<NewsItem>()
                for (url in rssUrls) {
                    try {
                        val response: HttpResponse = client.get(url)
                        val xmlString = response.bodyAsText()
                        allNews.addAll(parseRss(xmlString))
                    } catch (e: Exception) {
                        Log.e("NewsViewModel", "[LOG] Помилка з $url: ${e.message}")
                    }
                }
                _newsList.value = allNews
                Log.d("NewsViewModel", "[LOG] Успішно завантажено новин: ${allNews.size}")
            } catch (e: Exception) {
                Log.e("NewsViewModel", "[LOG] Помилка завантаження: ${e.message}")
            }
            _isLoading.value = false
        }
    }

    private fun parseRss(xmlData: String): List<NewsItem> {
        val newsList = mutableListOf<NewsItem>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xmlData))

            var eventType = parser.eventType
            var currentTitle = ""
            var currentLink = ""
            var currentDescription = ""
            var inItem = false

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tagName = parser.name
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (tagName.equals("item", ignoreCase = true)) {
                            inItem = true
                            currentTitle = ""
                            currentLink = ""
                            currentDescription = ""
                        } else if (inItem) {
                            when (tagName.lowercase()) {
                                "title" -> currentTitle = parser.nextText()
                                "link" -> currentLink = parser.nextText()
                                "description" -> currentDescription = parser.nextText()
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (tagName.equals("item", ignoreCase = true)) {
                            val cleanDescription = currentDescription.replace(Regex("<[^>]*>"), "").trim()
                            newsList.add(NewsItem(title = currentTitle, description = cleanDescription, link = currentLink))
                            inItem = false
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e("NewsViewModel", "[LOG] Помилка парсингу XML: ${e.message}")
        }
        return newsList
    }

    fun sendNews(news: NewsItem) {
        viewModelScope.launch {
            try {
                Log.d("NewsViewModel", "[LOG] Відправка новини: ${news.title}")
                val originalText = "${news.title}\n\n${news.description ?: ""}"
                val rewritten = AiRewriter.rewriteNews(originalText)
                
                if (botToken.isNotEmpty() && chatId.isNotEmpty()) {
                    TelegramBotService.sendMessage(botToken, chatId, rewritten)
                } else {
                    Log.e("NewsViewModel", "[LOG] Токени Telegram не вказані!")
                }
            } catch (e: Exception) {
                Log.e("NewsViewModel", "[LOG] Помилка відправки: ${e.message}")
            }
        }
    }
}
