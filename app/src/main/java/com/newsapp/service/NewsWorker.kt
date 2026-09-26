package com.newsapp.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.newsapp.data.LogManager
import com.newsapp.data.NewsCacheManager
import com.newsapp.data.NewsParserFactory
import com.newsapp.data.api.AiRewriter
import com.newsapp.model.NewsItem
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.endpoint
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import org.json.JSONObject
import java.net.URLEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NewsWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val client = HttpClient(CIO) { 
        expectSuccess = false 
        followRedirects = true
        engine { requestTimeout = 30_000; endpoint { connectTimeout = 30_000; socketTimeout = 30_000 } } 
    }
    
    private val cacheManager = NewsCacheManager(appContext)

    private val rssUrls = listOf(
        "https://www.nasa.gov/feed/",
        "https://science.nasa.gov/feed/",
        "https://www.esa.int/rssfeed/TopNews",
        "https://www.esa.int/rssfeed/Our_Activities/Space_Science",
        "https://www.space.com/feeds/all",
        "https://www.nature.com/subjects/astronomy-and-planetary-science.rss",
        "https://www.universetoday.com/feed",
        "https://www.spacedaily.com/spacedaily.xml",
        "https://phys.org/rss-feed/space-news"
    )

    private fun String.normalizeUrl() = this.lowercase().replace(Regex("^https?://"), "").replace(Regex("^www\\."), "").split("?")[0].trimEnd('/')

    private suspend fun scrapeArticle(url: String): Triple<String, List<String>, Boolean> {
        try {
            if (url.isEmpty()) return Triple("", emptyList(), false)
            val response = client.get(url) { header("User-Agent", "Mozilla/5.0") }
            val html = response.bodyAsText()
            val imageList = mutableListOf<String>()

            val ogMatch = Regex("<meta[^>]+(?:property|name)=['\"](?:og:image|twitter:image)['\"][^>]+content=['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE).find(html)
            if (ogMatch != null) {
                var img = ogMatch.groupValues[1]
                if (!img.startsWith("http")) { val b = java.net.URL(url); img = "${b.protocol}://${b.host}$img" }
                imageList.add(img)
            }

            val cleanHtml = html.replace(Regex("<(nav|header|footer|script|style|button|aside|noscript)[^>]*>[\\s\\S]*?<\\/\\1>", RegexOption.IGNORE_CASE), "")
            val scrapedText = Regex("<p[^>]*>(.*?)</p>", RegexOption.IGNORE_CASE).findAll(cleanHtml).map { it.groupValues[1].replace(Regex("<[^>]*>"), "").trim() }.filter { it.length > 80 && it.contains(".") }.joinToString("\n\n")
            val hasVideo = html.contains("<video", ignoreCase=true) || html.contains("<iframe", ignoreCase=true) || html.contains("og:video", ignoreCase=true)
            return Triple(if (scrapedText.length >= 150) scrapedText else "", imageList, hasVideo)
        } catch (e: Exception) { return Triple("", emptyList(), false) }
    }

    override suspend fun doWork(): Result {
        AiRewriter.init(appContext)
        LogManager.log("WORKER", "Запуск фонової перевірки новин...")

        try {
            val channelId = "news_worker_channel"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(channelId, "Фоновий пошук новин", NotificationManager.IMPORTANCE_LOW)
                val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(channel)
            }
            val notification = NotificationCompat.Builder(appContext, channelId)
                .setSmallIcon(appContext.applicationInfo.icon)
                .setContentTitle("ШІ працює у фоні 🚀")
                .setContentText("NewsApp шукає нові статті...")
                .setOngoing(true)
                .build()
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setForeground(ForegroundInfo(1005, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
            } else {
                setForeground(ForegroundInfo(1005, notification))
            }
        } catch (e: Exception) { LogManager.log("WORKER_ERR", "Помилка Foreground: ${e.message}") }

        val cachedNews = cacheManager.loadNews()
        val existingTitles = cachedNews.flatMap { listOf(it.title.trim().lowercase(), it.originalTitle.trim().lowercase()) }.filter { it.isNotEmpty() }.toSet()
        val existingLinks = cachedNews.map { it.link.normalizeUrl() }.filter { it.isNotEmpty() }.toSet()

        val rawNews = mutableListOf<NewsItem>()

        for (url in rssUrls) {
            try {
                var fetchedItems = listOf<NewsItem>()
                val parser = NewsParserFactory.getParser(url)

                try {
                    val response = client.get(url) {
                        header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        header("Accept", "application/xml, text/xml")
                    }
                    if (response.status.value in 200..299) {
                        val xml = response.bodyAsText()
                        if (xml.contains("<rss") || xml.contains("<feed") || xml.contains("<?xml")) {
                            fetchedItems = parser.parse(xml)
                        }
                    }
                } catch (e: Exception) {}

                if (fetchedItems.isEmpty()) {
                    try {
                        val proxyUrl = "https://api.allorigins.win/get?url=${URLEncoder.encode(url, "UTF-8")}"
                        val response = client.get(proxyUrl)
                        if (response.status.value in 200..299) {
                            val json = JSONObject(response.bodyAsText())
                            val xml = json.optString("contents", "")
                            if (xml.contains("<rss") || xml.contains("<feed") || xml.contains("<?xml")) {
                                fetchedItems = parser.parse(xml)
                            }
                        }
                    } catch (e: Exception) {}
                }

                if (fetchedItems.isEmpty()) {
                    try {
                        val r2jUrl = "https://api.rss2json.com/v1/api.json?rss_url=${URLEncoder.encode(url, "UTF-8")}"
                        val response = client.get(r2jUrl)
                        if (response.status.value in 200..299) {
                            val json = JSONObject(response.bodyAsText())
                            if (json.optString("status") == "ok") {
                                val itemsArray = json.optJSONArray("items")
                                val fallbackItems = mutableListOf<NewsItem>()
                                val sourceName = when {
                                    url.contains("nasa.gov") -> "NASA"
                                    url.contains("esa.int") -> "ESA"
                                    url.contains("space.com") -> "Space.com"
                                    url.contains("spacedaily") -> "Space Daily"
                                    url.contains("universetoday") -> "Universe Today"
                                    url.contains("phys.org") -> "Phys.org"
                                    url.contains("nature.com") -> "Nature"
                                    else -> "Новина"
                                }
                                for (i in 0 until (itemsArray?.length() ?: 0)) {
                                    val obj = itemsArray!!.getJSONObject(i)
                                    var rawTitle = obj.optString("title").replace("(?i)APOD:\\s*(-\\s*)?".toRegex(), "").trim()
                                    var rawDesc = obj.optString("description", "").replace(Regex("<[^>]*>"), "").trim()
                                    if (rawDesc.length > 300) rawDesc = rawDesc.take(300) + "..."
                                    var img = obj.optString("thumbnail", "")
                                    if (img.isEmpty()) img = obj.optJSONObject("enclosure")?.optString("link", "") ?: ""
                                    var ts = System.currentTimeMillis()
                                    val pubDate = obj.optString("pubDate", "")
                                    if (pubDate.isNotEmpty()) {
                                        try { ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ENGLISH).parse(pubDate)?.time ?: ts } catch(e: Exception) {}
                                    }
                                    fallbackItems.add(NewsItem(title = rawTitle, originalTitle = obj.optString("title"), link = obj.optString("link").split(" ")[0], description = rawDesc, source = sourceName, image = img, timestamp = ts))
                                }
                                fetchedItems = fallbackItems
                            }
                        }
                    } catch (e: Exception) {}
                }
                rawNews.addAll(fetchedItems)
            } catch (e: Exception) {}
        }

        val uniqueRawNews = rawNews.distinctBy { 
            val norm = it.link.normalizeUrl()
            if (norm.isNotEmpty()) norm else it.originalTitle.trim().lowercase()
        }

        val maxAgeMillis = System.currentTimeMillis() - (3L * 24 * 60 * 60 * 1000)
        val freshNews = uniqueRawNews.filter { item ->
            val normTitle = item.title.trim().lowercase()
            val origTitle = item.originalTitle.trim().lowercase()
            val normLink = item.link.normalizeUrl()
            val isTitleDuplicate = existingTitles.contains(normTitle) || (origTitle.isNotEmpty() && existingTitles.contains(origTitle))
            val isLinkDuplicate = normLink.isNotEmpty() && existingLinks.contains(normLink)
            !isTitleDuplicate && !isLinkDuplicate && (item.timestamp > maxAgeMillis)
        }

        if (freshNews.isNotEmpty()) {
            val enrichedNews = freshNews.map { item ->
                val (fullText, scrapedImages, hasVid) = scrapeArticle(item.link)
                item.copy(
                    description = if (fullText.isNotEmpty()) fullText else item.description,
                    image = if (scrapedImages.isNotEmpty()) scrapedImages.first() else item.image,
                    images = if (scrapedImages.isNotEmpty()) scrapedImages else if (item.image.isNotEmpty()) listOf(item.image) else emptyList(),
                    hasVideo = hasVid,
                    status = "В черзі"
                )
            }
            
            val updatedCache = (enrichedNews + cachedNews).sortedByDescending { it.timestamp }.take(250)
            cacheManager.saveNews(updatedCache)

            if (!AiRewriter.isGloballyBlocked()) {
                AiRewriter.processAllNewsWithAi(enrichedNews, appContext) { item ->
                    CoroutineScope(Dispatchers.IO).launch { updateItemInCacheSafely(item) }
                    showNewsNotification(item)
                }
            }
        }
        return Result.success()
    }

    private suspend fun updateItemInCacheSafely(item: NewsItem) {
        val currentNews = cacheManager.loadNews().toMutableList()
        val index = currentNews.indexOfFirst { it.id == item.id }
        if (index != -1) {
            currentNews[index] = item
            cacheManager.saveNews(currentNews)
        }
    }

    private fun showNewsNotification(item: NewsItem) {
        try {
            val channelId = "news_updates_channel"
            val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(channelId, "Нові новини", NotificationManager.IMPORTANCE_DEFAULT)
                notificationManager.createNotificationChannel(channel)
            }
            val intent = android.content.Intent(appContext, com.newsapp.MainActivity::class.java).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val pendingIntent = android.app.PendingIntent.getActivity(appContext, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(appContext, channelId)
                .setSmallIcon(appContext.applicationInfo.icon)
                .setContentTitle("🚀 " + item.title)
                .setContentText(item.description)
                .setStyle(NotificationCompat.BigTextStyle().bigText(item.description))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
            notificationManager.notify(item.id.hashCode(), notification)
        } catch (e: Exception) {}
    }
}
