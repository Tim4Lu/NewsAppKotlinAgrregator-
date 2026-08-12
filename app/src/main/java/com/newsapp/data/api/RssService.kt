
package com.newsapp.data.api

import com.prof18.rssparser.RssParser
import com.newsapp.data.model.NewsItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RssService {
    private val parser = RssParser()

    private val sources = mapOf(
        "NASA" to "https://www.nasa.gov/rss/dyn/breaking_news.rss",
        "Space.com" to "https://www.space.com/feeds/all",
        "Space Daily" to "https://www.spacedaily.com/spacedaily.xml",
        "Universe Today" to "https://www.universetoday.com/feed"
    )

    suspend fun fetchAllNews(): List<NewsItem> = withContext(Dispatchers.IO) {
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
                // Якщо одне джерело не відповідає, інші продовжують працювати
            }
        }
        return@withContext allNews
    }
}
