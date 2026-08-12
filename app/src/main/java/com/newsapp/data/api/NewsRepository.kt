package com.newsapp.data.api

import com.newsapp.data.model.NewsItem
import com.prof18.rssparser.RssParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NewsRepository {
    private val parser = RssParser()

    private val sources = mapOf(
        "NASA" to "https://www.nasa.gov/rss/dyn/breaking_news.rss",
        "Space.com" to "https://www.space.com/feeds/all",
        "Space Daily" to "https://www.spacedaily.com/spacedaily.xml",
        "Universe Today" to "https://www.universetoday.com/feed"
    )

    suspend fun getNews(): List<NewsItem> = withContext(Dispatchers.IO) {
        val allNews = mutableListOf<NewsItem>()

        for ((sourceName, url) in sources) {
            try {
                val channel = parser.getRssChannel(url)
                val items = channel.items.map { item ->
                    NewsItem(
                        id = item.guid ?: item.link ?: item.title.hashCode().toString(),
                        title = item.title ?: "",
                        description = item.description ?: "",
                        link = item.link ?: "",
                        pubDate = item.pubDate ?: "",
                        imageUrl = item.image ?: item.audio,
                        source = sourceName
                    )
                }
                allNews.addAll(items)
            } catch (e: Exception) {
                // Якщо одне із джерел не відповідає, інші збираються далі
            }
        }
        return@withContext allNews
    }
}
