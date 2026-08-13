package com.newsapp.ui.viewmodel

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.newsapp.data.LogManager
import com.newsapp.data.UpdateManager
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
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.net.URL

class NewsViewModel(application: Application) : AndroidViewModel(application) {

    private val MAX_NEWS_LIMIT = 280

    private val _newsList = MutableStateFlow<List<NewsItem>>(emptyList())
    val newsList: StateFlow<List<NewsItem>> = _newsList.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val client = HttpClient(CIO) { followRedirects = true }
    private val aiRewriter = AiRewriter()
    private val telegramBotService = TelegramBotService()
    private val updateManager = UpdateManager(application)
    private val cacheFile = File(application.filesDir, "saved_news.json")

    private val rssSources = listOf(
        "NASA News" to "https://www.nasa.gov/rss/dyn/breaking_news.rss",
        "SpaceNews" to "https://spacenews.com/feed/",
        "BBC Tech" to "https://feeds.bbci.co.uk/news/technology/rss.xml",
        "TechCrunch" to "https://techcrunch.com/feed/",
        "Wired" to "https://www.wired.com/feed/rss",
        "MIT Tech Review" to "https://www.technologyreview.com/feed/",
        "Nature" to "https://www.nature.com/nature.rss"
    )

    init {
        loadCachedNews()
        loadNews()
        checkForAppUpdates()
    }

    private fun getCurrentVersionCode(): Int {
        return try {
            val pInfo = getApplication<Application>().packageManager.getPackageInfo(getApplication<Application>().packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (e: Exception) {
            1
        }
    }

    private fun checkForAppUpdates() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val currentVersion = getCurrentVersionCode()
                val updateInfo = updateManager.checkForUpdate(currentBuildNumber = currentVersion)
                if (updateInfo != null) {
                    LogManager.log("UPDATE", "Знайдено нову версію: ${updateInfo.tagName}. Починаємо завантаження...")
                    updateManager.downloadAndInstallApk(updateInfo.downloadUrl)
                } else {
                    LogManager.log("UPDATE", "Встановлено актуальну версію ($currentVersion).")
                }
            } catch (e: Exception) {
                LogManager.log("UPDATE_ERR", "Помилка оновлень: ${e.message}")
            }
        }
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
                _newsList.value = cached.take(MAX_NEWS_LIMIT)
                LogManager.log("CACHE", "Завантажено ${_newsList.value.size} збережених новин з пам'яті")
            }
        } catch (e: Exception) {
            LogManager.log("CACHE_ERR", "Помилка читання кешу: ${e.message}")
        }
    }

    private fun saveNewsToDisk(list: List<NewsItem>) {
        try {
            val jsonArray = JSONArray()
            val trimmedList = list.take(MAX_NEWS_LIMIT)
            trimmedList.forEach { item ->
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
            LogManager.log("CACHE_ERR", "Помилка збереження кешу: ${e.message}")
        }
    }

    fun loadNews() {
        if (_newsList.value.isEmpty()) _isLoading.value = true

        viewModelScope.launch(Dispatchers.IO) {
            val rawNews = mutableListOf<NewsItem>()

            for ((sourceName, url) in rssSources) {
                try {
                    val response = client.get(url) { header(HttpHeaders.UserAgent, "Mozilla/5.0") }
                    if (response.status.value in 200..299) {
                        rawNews.addAll(parseRss(response.bodyAsText(), sourceName))
                    }
                } catch (e: Exception) { }
            }

            if (rawNews.isNotEmpty()) {
                val existingLinksAndTitles = _newsList.value.flatMap { listOf(it.link, it.title) }.filter { it.isNotBlank() }.toSet()
                val trulyNewItems = rawNews.filter { !existingLinksAndTitles.contains(it.link) && !existingLinksAndTitles.contains(it.title) }

                if (trulyNewItems.isNotEmpty()) {
                    LogManager.log("RSS", "Знайдено ${trulyNewItems.size} нових свіжих новин")
                    val freshInitial = trulyNewItems.map { it.copy(status = "В черзі", telegramCaption = "Обробка...") }
                    
                    val combinedList = (freshInitial + _newsList.value).take(MAX_NEWS_LIMIT)
                    _newsList.value = combinedList
                    _isLoading.value = false

                    processNewsWithScraperAndAi(trulyNewItems)
                } else {
                    LogManager.log("RSS", "Усі новини з RSS вже є у пам'яті.")
                    _isLoading.value = false
                }
            } else {
                _isLoading.value = false
            }
        }
    }

    private suspend fun scrapeArticle(url: String): Pair<String, String?> {
        try {
            val response: HttpResponse = client.get(url) {
                header(HttpHeaders.UserAgent, "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            }
            val html = response.bodyAsText()
            var imageUrl: String? = null

            val ogMatch = Regex("<meta[^>]+(?:property|name)=[\"'](?:og:image|twitter:image)[\"'][^>]+content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(html)
            if (ogMatch != null) imageUrl = ogMatch.groupValues[1]

            if (imageUrl.isNullOrEmpty()) {
                val jsonLdMatch = Regex("\"image\"\\s*:\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(html)
                if (jsonLdMatch != null) imageUrl = jsonLdMatch.groupValues[1]
            }

            if (!imageUrl.isNullOrEmpty() && !imageUrl.startsWith("http")) {
                val baseUrl = URL(url)
                imageUrl = "${baseUrl.protocol}://${baseUrl.host}$imageUrl"
            }

            val cleanHtml = html.replace(Regex("<(nav|header|footer|script|style|button|aside|noscript)[^>]*>[\\s\\S]*?<\\/\\1>", RegexOption.IGNORE_CASE), "")
            val pTags = Regex("<p[^>]*>([^<]{50,})<\\/p>", RegexOption.IGNORE_CASE).findAll(cleanHtml).map { it.groupValues[1] }.toList()
            val validParagraphs = pTags
                .map { cleanText(it) }
                .filter { t -> t.length > 100 && t.contains(".") }

            val scrapedText = validParagraphs.take(4).joinToString("\n\n")
            return Pair(if (scrapedText.length >= 200) scrapedText else "", imageUrl)
        } catch (e: Exception) {
            return Pair("", null)
        }
    }

    private fun cleanText(raw: String): String {
        return raw.replace(Regex("<.*?>"), "")
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()
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
            }.take(MAX_NEWS_LIMIT)
            
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
            val success = telegramBotService.sendToTelegram(newsItem.telegramCaption, newsItem.image)
            if (success) {
                _newsList.value = _newsList.value.map { if (it.id == newsItem.id) it.copy(status = "Опубліковано") else it }
                saveNewsToDisk(_newsList.value)
            }
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
                                items.add(NewsItem(
                                    title = cleanText(title!!),
                                    link = link?.trim() ?: "",
                                    description = cleanText(desc ?: ""),
                                    source = sourceName
                                ))
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
