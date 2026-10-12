package com.newsapp.data

import com.newsapp.model.NewsItem
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

object Rss2JsonParser {
    private val client = HttpClient(CIO) { expectSuccess = false }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)

    suspend fun fetch(rssUrl: String, sourceName: String): List<NewsItem> {
        return try {
            val apiUrl = "https://api.rss2json.com/v1/api.json?rss_url=${java.net.URLEncoder.encode(rssUrl, "UTF-8")}&count=15"
            LogManager.log("RSS2JSON", "Запит: $sourceName")
            val response = client.get(apiUrl)
            if (response.status.value !in 200..299) {
                LogManager.log("RSS2JSON_ERR", "HTTP ${response.status.value} для $sourceName")
                return emptyList()
            }
            val json = JSONObject(response.bodyAsText())
            if (json.optString("status") != "ok") {
                LogManager.log("RSS2JSON_ERR", "Статус не ok для $sourceName: ${json.optString("message")}")
                return emptyList()
            }
            val itemsArray = json.optJSONArray("items") ?: return emptyList()
            val items = mutableListOf<NewsItem>()
            for (i in 0 until itemsArray.length()) {
                val obj = itemsArray.getJSONObject(i)
                val title = obj.optString("title", "")
                val link = obj.optString("link", "")
                val description = obj.optString("description", "").replace(Regex("<[^>]*>"), "").trim()
                val pubDate = obj.optString("pubDate", "")
                var timestamp = System.currentTimeMillis()
                try { dateFormat.parse(pubDate)?.let { timestamp = it.time } } catch (_: Exception) {}
                if (title.isNotEmpty() && link.isNotEmpty()) {
                    items.add(NewsItem(
                        title = title,
                        originalTitle = title,
                        link = link,
                        description = description,
                        source = sourceName,
                        timestamp = timestamp
                    ))
                }
            }
            LogManager.log("RSS2JSON_OK", "$sourceName: ${items.size} новин")
            items
        } catch (e: Exception) {
            LogManager.log("RSS2JSON_ERR", "$sourceName: ${e.message}")
            emptyList()
        }
    }
}
