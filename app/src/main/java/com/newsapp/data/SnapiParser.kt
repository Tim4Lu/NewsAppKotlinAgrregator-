package com.newsapp.data

import com.newsapp.model.NewsItem
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object SnapiParser {
    private val client = HttpClient(CIO) { expectSuccess = false }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ENGLISH).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    suspend fun fetch(newsSite: String, limit: Int = 15): List<NewsItem> {
        return try {
            val encoded = java.net.URLEncoder.encode(newsSite, "UTF-8")
            val url = "https://api.spaceflightnewsapi.net/v4/articles/?news_site=$encoded&limit=$limit"
            LogManager.log("SNAPI", "Запит: $newsSite")
            val response = client.get(url)
            if (response.status.value !in 200..299) {
                LogManager.log("SNAPI_ERR", "HTTP ${response.status.value} для $newsSite")
                return emptyList()
            }
            val json = JSONObject(response.bodyAsText())
            val results = json.optJSONArray("results") ?: return emptyList()
            val items = mutableListOf<NewsItem>()
            for (i in 0 until results.length()) {
                val obj = results.getJSONObject(i)
                val title = obj.optString("title", "")
                val link = obj.optString("url", "")
                val summary = obj.optString("summary", "").replace(Regex("<[^>]*>"), "").trim()
                val image = obj.optString("image_url", "")
                val publishedAt = obj.optString("published_at", "")
                var timestamp = System.currentTimeMillis()
                try { dateFormat.parse(publishedAt)?.let { timestamp = it.time } } catch (_: Exception) {}
                if (title.isNotEmpty() && link.isNotEmpty()) {
                    items.add(NewsItem(
                        title = title,
                        originalTitle = title,
                        link = link,
                        description = summary,
                        source = newsSite,
                        image = image,
                        timestamp = timestamp
                    ))
                }
            }
            LogManager.log("SNAPI_OK", "$newsSite: ${items.size} новин")
            items
        } catch (e: Exception) {
            LogManager.log("SNAPI_ERR", "$newsSite: ${e.message}")
            emptyList()
        }
    }
}
