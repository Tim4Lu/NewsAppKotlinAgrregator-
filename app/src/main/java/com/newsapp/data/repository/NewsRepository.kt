package com.newsapp.data.repository

import android.util.Log
import com.newsapp.data.model.NewsItem
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

class NewsRepository {
    private val client = HttpClient(CIO)
    
    // Список джерел для прикладу (можна додавати свої)
    private val rssUrls = listOf(
        "https://www.pravda.com.ua/rss/view_news/",
        "https://tsn.ua/rss/full.rss"
    )

    suspend fun fetchNews(): List<NewsItem> {
        val allNews = mutableListOf<NewsItem>()
        Log.d("NewsRepository", "[LOG] Початок завантаження реальних новин...")
        
        for (url in rssUrls) {
            try {
                val response: HttpResponse = client.get(url)
                val xmlString = response.bodyAsText()
                val parsedNews = parseRss(xmlString)
                allNews.addAll(parsedNews)
                Log.d("NewsRepository", "[LOG] Завантажено ${parsedNews.size} новин з $url")
            } catch (e: Exception) {
                Log.e("NewsRepository", "[LOG] Помилка завантаження з $url: ${e.message}")
            }
        }
        return allNews
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
                            // Очищаємо опис від HTML-тегів для красивого відображення
                            val cleanDescription = currentDescription.replace(Regex("<[^>]*>"), "").trim()
                            newsList.add(NewsItem(currentTitle, cleanDescription, currentLink))
                            inItem = false
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e("NewsRepository", "[LOG] Помилка парсингу XML: ${e.message}")
        }
        return newsList
    }
}
