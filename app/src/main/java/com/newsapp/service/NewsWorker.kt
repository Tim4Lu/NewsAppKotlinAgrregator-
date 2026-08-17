package com.newsapp.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.newsapp.data.LogManager
import com.newsapp.data.NewsParserFactory
import com.newsapp.data.api.AiRewriter
import com.newsapp.model.NewsItem
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

class NewsWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val client = HttpClient(CIO) { 
        expectSuccess = false 
        followRedirects = true 
    }
    
    private val aiRewriter = AiRewriter()
    private val cacheFile = File(appContext.filesDir, "saved_news.json")

    private fun String.normalizeUrl(): String {
        return this.lowercase()
            .replace(Regex("^https?://"), "")
            .replace(Regex("^www\\."), "")
            .split("?")[0]
            .trimEnd('/')
    }

    override suspend fun doWork(): Result {
        LogManager.log("WORKER", "Запуск фонової перевірки новин...")

        val rssUrls = listOf(
            "https://www.nasa.gov/feed/",
            "https://www.space.com/feeds/all",
            "https://www.universetoday.com/feed",
            "https://www.spacedaily.com/spacedaily.xml",
            "https://phys.org/rss-feed/space-news/"
        )

        val (existingTitles, existingLinks) = getCachedTitlesAndLinks()
        val rawNews = mutableListOf<NewsItem>()

        for (url in rssUrls) {
            try {
                var fetchedItems = listOf<NewsItem>()
                var successDirect = false

                val response = client.get(url) {
                    header(HttpHeaders.UserAgent, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                }

                if (response.status.value in 200..299) {
                    val parser = NewsParserFactory.getParser(url)
                    fetchedItems = parser.parse(response.bodyAsText())
                    if (fetchedItems.isNotEmpty()) successDirect = true
                }

                if (!successDirect) {
                    val apiUrl = "https://api.rss2json.com/v1/api.json?rss_url=${URLEncoder.encode(url, "UTF-8")}"
                    val jsonResponse = client.get(apiUrl)
                    if (jsonResponse.status.value in 200..299) {
                        val json = JSONObject(jsonResponse.bodyAsText())
                        if (json.optString("status") == "ok") {
                            val itemsArray = json.optJSONArray("items")
                            val fallbackItems = mutableListOf<NewsItem>()
                            
                            val sourceName = when {
                                url.contains("nasa.gov") -> "NASA"
                                url.contains("space.com") -> "Space.com"
                                url.contains("spacedaily") -> "Space Daily"
                                url.contains("universetoday") -> "Universe Today"
                                url.contains("phys.org") -> "Phys.org"
                                else -> "Новина"
                            }

                            for (i in 0 until (itemsArray?.length() ?: 0)) {
                                val obj = itemsArray!!.getJSONObject(i)
                                var rawTitle = obj.optString("title").replace("(?i)APOD:\\s*(-\\s*)?".toRegex(), "").trim()
                                var rawDesc = obj.optString("description", "")
                                rawDesc = rawDesc.replace(Regex("<[^>]*>"), "").trim()
                                if (rawDesc.length > 300) rawDesc = rawDesc.take(300) + "..."
                                
                                var img = obj.optString("thumbnail", "")
                                if (img.isEmpty()) {
                                    val enc = obj.optJSONObject("enclosure")
                                    if (enc != null) img = enc.optString("link", "")
                                }

                                var ts = System.currentTimeMillis()
                                val pubDate = obj.optString("pubDate", "")
                                if (pubDate.isNotEmpty()) {
                                    try {
                                        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
                                        ts = sdf.parse(pubDate)?.time ?: ts
                                    } catch(e: Exception) {}
                                }

                                fallbackItems.add(
                                    NewsItem(
                                        title = rawTitle,
                                        originalTitle = obj.optString("title"),
                                        link = obj.optString("link").split(" ")[0],
                                        description = rawDesc,
                                        source = sourceName,
                                        image = img,
                                        timestamp = ts
                                    )
                                )
                            }
                            fetchedItems = fallbackItems
                        }
                    }
                }
                rawNews.addAll(fetchedItems)
            } catch (e: Exception) {
                val errorMsg = e.message ?: e.javaClass.simpleName
                LogManager.log("WORKER_ERR", "Помилка RSS $url: $errorMsg")
            }
        }

        val uniqueRawNews = rawNews.distinctBy { 
            val norm = it.link.normalizeUrl()
            if (norm.isNotEmpty()) norm else it.originalTitle.trim().lowercase()
        }

        val freshNews = uniqueRawNews.filter { item ->
            val normTitle = item.title.trim().lowercase()
            val origTitle = item.originalTitle.trim().lowercase()
            val normLink = item.link.normalizeUrl()
            
            val isTitleDuplicate = existingTitles.contains(normTitle) || (origTitle.isNotEmpty() && existingTitles.contains(origTitle))
            val isLinkDuplicate = normLink.isNotEmpty() && existingLinks.contains(normLink)
            
            !isTitleDuplicate && !isLinkDuplicate
        }

        if (freshNews.isNotEmpty()) {
            LogManager.log("WORKER", "Знайдено ${freshNews.size} нових новин. Обробка ШІ...")
            val processedNews = mutableListOf<NewsItem>()

            aiRewriter.processAllNewsWithAi(freshNews) { item ->
                processedNews.add(item)
                showNewsNotification(item)
            }

            saveToCache(processedNews)
        }

        return Result.success()
    }

    private fun getCachedTitlesAndLinks(): Pair<Set<String>, Set<String>> {
        val titles = mutableSetOf<String>()
        val links = mutableSetOf<String>()
        try {
            if (cacheFile.exists()) {
                val jsonArray = JSONArray(cacheFile.readText())
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val t = obj.optString("title").trim().lowercase()
                    val orig = obj.optString("originalTitle").trim().lowercase()
                    val link = obj.optString("link")
                    
                    if (t.isNotEmpty()) titles.add(t)
                    if (orig.isNotEmpty()) titles.add(orig)
                    if (link.isNotEmpty()) links.add(link.normalizeUrl())
                }
            }
        } catch (e: Exception) { }
        return Pair(titles, links)
    }

    private fun saveToCache(newItems: List<NewsItem>) {
        try {
            val jsonArray = if (cacheFile.exists()) JSONArray(cacheFile.readText()) else JSONArray()
            newItems.forEach { item ->
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("title", item.title)
                    put("originalTitle", item.originalTitle)
                    put("link", item.link)
                    put("description", item.description)
                    put("source", item.source)
                    put("image", item.image)
                    put("status", item.status)
                    put("telegramCaption", item.telegramCaption)
                    put("timestamp", item.timestamp)
                }
                jsonArray.put(obj)
            }
            cacheFile.writeText(jsonArray.toString())
        } catch (e: Exception) { }
    }

    private fun showNewsNotification(item: NewsItem) {
        try {
            val channelId = "news_updates_channel"
            val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "Нові новини",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                notificationManager.createNotificationChannel(channel)
            }

            val notification = NotificationCompat.Builder(appContext, channelId)
                .setSmallIcon(appContext.applicationInfo.icon)
                .setContentTitle("🚀 " + item.title)
                .setContentText(item.description)
                .setStyle(NotificationCompat.BigTextStyle().bigText(item.description))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(item.id.hashCode(), notification)
        } catch (e: Exception) {
            val errorMsg = e.message ?: e.javaClass.simpleName
            LogManager.log("WORKER_ERR", "Сповіщення не показано: $errorMsg")
        }
    }
}
