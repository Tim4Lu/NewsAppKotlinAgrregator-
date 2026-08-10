package com.newsapp.data.api

import android.util.Log
import com.newsapp.data.model.NewsItem
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*

object NewsRepository {
    private const val TAG = "NewsRepository"
    private val client = HttpClient(CIO)

    // Розширений список українських та світових RSS-джерел
    private val SOURCES = listOf(
        // Українські джерела
        "AIN.ua" to "https://ain.ua/feed/",
        "ITC.ua" to "https://itc.ua/ua/feed/",
        "Dev.ua" to "https://dev.ua/feed",
        "Mezha.Media" to "https://mezha.media/feed/",
        
        // Світові джерела (Технології, Космос, Наука)
        "BBC Tech" to "https://feeds.bbci.co.uk/news/technology/rss.xml",
        "NASA News" to "https://www.nasa.gov/rss/dyn/breaking_news.rss",
        "SpaceNews" to "https://spacenews.com/feed/",
        "TechCrunch" to "https://techcrunch.com/feed/",
        "Wired" to "https://www.wired.com/feed/rss",
        "MIT Tech Review" to "https://www.technologyreview.com/feed/",
        "Nature" to "https://www.nature.com/nature.rss"
    )

    suspend fun fetchAllNews(): List<NewsItem> {
        Log.d(TAG, "[LOG] Початок завантаження розширеного списку новин з усіх джерел...")
        val newsList = mutableListOf<NewsItem>()

        for ((sourceName, url) in SOURCES) {
            try {
                Log.d(TAG, "[LOG] Завантаження RSS із джерела: $sourceName ($url)...")
                val response: HttpResponse = client.get(url) {
                    header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:109.0) Gecko/109.0 Firefox/119.0")
                }
                
                if (response.status.value == 200) {
                    val xmlText = response.bodyAsText()
                    val parsedItems = parseRssItems(xmlText, sourceName)
                    Log.d(TAG, "[LOG] З джерела $sourceName успішно отримано ${parsedItems.size} новин")
                    newsList.addAll(parsedItems)
                } else {
                    Log.e(TAG, "[LOG] Помилка завантаження $sourceName: HTTP код ${response.status.value}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "[LOG] Виняток при отриманні $sourceName: ${e.message}")
            }
        }

        Log.d(TAG, "[LOG] Загалом зібрано ${newsList.size} новин з усіх джерел.")
        return newsList
    }

    private fun parseRssItems(xml: String, sourceName: String): List<NewsItem> {
        val items = mutableListOf<NewsItem>()
        val itemRegex = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
        val titleRegex = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
        val linkRegex = Regex("<link>(.*?)</link>", RegexOption.DOT_MATCHES_ALL)
        val descRegex = Regex("<description>(.*?)</description>", RegexOption.DOT_MATCHES_ALL)
        val mediaRegex = Regex("<media:content[^>]+url=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
        val enclosureRegex = Regex("<enclosure[^>]+url=\"([^\"]+)\"", RegexOption.IGNORE_CASE)

        val matches = itemRegex.findAll(xml)
        for ((index, match) in matches.withIndex()) {
            val itemXml = match.groupValues[1]
            
            val title = cleanTagContent(titleRegex.find(itemXml)?.groupValues?.get(1) ?: "Без заголовка")
            val link = cleanTagContent(linkRegex.find(itemXml)?.groupValues?.get(1) ?: "")
            val description = cleanTagContent(descRegex.find(itemXml)?.groupValues?.get(1) ?: "")
            
            // Парсинг зображення, якщо воно є в RSS
            val imageUrl = mediaRegex.find(itemXml)?.groupValues?.get(1)
                ?: enclosureRegex.find(itemXml)?.groupValues?.get(1)

            if (title.isNotBlank()) {
                items.add(
                    NewsItem(
                        id = "${sourceName.replace(" ", "_")}_${System.currentTimeMillis()}_$index",
                        title = title,
                        description = description,
                        link = link,
                        sourceName = sourceName,
                        imageUrl = imageUrl
                    )
                )
            }
        }
        return items
    }

    private fun cleanTagContent(content: String): String {
        return content
            .replace("<![CDATA[", "")
            .replace("]]>", "")
            .replace(Regex("<.*?>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()
    }
}
