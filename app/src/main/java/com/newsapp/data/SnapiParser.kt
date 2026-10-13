package com.newsapp.data

import com.newsapp.model.NewsItem
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeFormatter

object SnapiParser {
    private val client = HttpClient(CIO) { expectSuccess = false }

    // ISO 8601 з мікросекундами: 2026-10-08T13:57:38.112233Z
    private val dateFormat = DateTimeFormatter.ISO_INSTANT

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
                try {
                    timestamp = Instant.parse(publishedAt).toEpochMilli()
                } catch (e: Exception) {
                    LogManager.log("SNAPI_DATE_ERR", "$newsSite: не вдалось розпарсити '$publishedAt': ${e.message}")
                }
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
            val now = System.currentTimeMillis()
            val ages = items.map { (now - it.timestamp) / (1000L * 60 * 60) }
            val freshCount = ages.count { it < 72 }
            val staleCount = ages.count { it >= 72 }
            LogManager.log("SNAPI_OK", "$newsSite: ${items.size} новин (свіжих<3д: $freshCount, старих: $staleCount)")
            if (items.isNotEmpty()) {
                val newest = items.minByOrNull { now - it.timestamp }
                val oldest = items.maxByOrNull { now - it.timestamp }
                LogManager.log("SNAPI_DATE", "$newsSite: найновіша ${(now - (newest?.timestamp ?: now)) / 3600000}год тому, найстаріша ${(now - (oldest?.timestamp ?: now)) / 3600000}год тому")
            }
            items
        } catch (e: Exception) {
            LogManager.log("SNAPI_ERR", "$newsSite: ${e.message}")
            emptyList()
        }
    }
}
