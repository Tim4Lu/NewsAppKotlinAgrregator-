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
            val rawNews = mutableListOf<NewsItem>()

            for (url in rssUrls) {
                try {
                    val response = client.get(url) { header(HttpHeaders.UserAgent, "Mozilla/5.0") }
                    if (response.status.value in 200..299) {
                        rawNews.addAll(parseRss(response.bodyAsText(), URL(url).host))
                    }
                } catch (e: Exception) { /* ігноруємо для стабільності */ }
            }

            if (rawNews.isNotEmpty()) {
                if (_newsList.value.isEmpty()) {
                    _newsList.value = rawNews.map { it.copy(status = "В черзі", telegramCaption = "Обробка...") }
                }
                _isLoading.value = false

                aiRewriter.processAllNewsWithAi(rawNews) { finishedItem ->
                    _newsList.value = _newsList.value.map { current ->
                        if (current.id == finishedItem.id || current.title == finishedItem.title) finishedItem else current
                    }
                }
            } else {
                _isLoading.value = false
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
            var image = ""
            var insideItem = false
            var currentTag = ""

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name?.lowercase() ?: ""
                        if (currentTag == "item" || currentTag == "entry") {
                            insideItem = true; title = null; link = null; desc = null; image = ""
                        } else if (insideItem) {
                            if (currentTag == "link") {
                                val href = parser.getAttributeValue(null, "href")
                                if (!href.isNullOrEmpty()) link = href
                            } else if (currentTag.contains("enclosure") || currentTag.contains("content") || currentTag.contains("thumbnail")) {
                                // Агресивний пошук лінка на картинку в атрибутах URL
                                val urlAttr = parser.getAttributeValue(null, "url") ?: parser.getAttributeValue("", "url")
                                if (!urlAttr.isNullOrEmpty() && (image.isEmpty() || currentTag == "enclosure")) image = urlAttr
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (insideItem && parser.text?.isNotBlank() == true) {
                            when (currentTag) {
                                "title" -> title = (title ?: "") + parser.text
                                "link" -> if (link.isNullOrEmpty()) link = parser.text
                                "description", "summary", "content" -> desc = (desc ?: "") + parser.text
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val name = parser.name ?: ""
                        if (name.equals("item", true) || name.equals("entry", true)) {
                            if (!title.isNullOrEmpty()) {
                                // Якщо картинки немає в XML, шукаємо в HTML тегу <img>
                                if (image.isEmpty() && !desc.isNullOrEmpty()) {
                                    val imgMatch = Regex("<img[^>]+src\\s*=\\s*['\"]([^'\"]+)['\"]").find(desc!!)
                                    if (imgMatch != null) image = imgMatch.groupValues[1]
                                }
                                items.add(NewsItem(title!!.trim(), link?.trim() ?: "", desc?.replace(Regex("<.*?>"), "")?.trim() ?: "", sourceName, image))
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
