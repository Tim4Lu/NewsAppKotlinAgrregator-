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

    private fun String.normalizeUrl(): String {
        return this.lowercase()
            .replace(Regex("^https?://"), "")
            .replace(Regex("^www\\."), "")
            .split("?")[0]
            .trimEnd('/')
    }

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
                .setContentText("NewsApp шукає та перекладає нові статті...")
                .setOngoing(true)
                .build()
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setForeground(ForegroundInfo(1005, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
            } else {
                setForeground(ForegroundInfo(1005, notification))
            }
        } catch (e: Exception) { LogManager.log("WORKER_ERR", "Не вдалося закріпити Foreground: ${e.message}") }

        val rssUrls = listOf(
            "https://www.nasa.gov/feed/",
            "https://science.nasa.gov/feed/",
            "https://www.esa.int/rssfeed/TopNews",
            "https://www.esa.int/rssfeed/Our_Activities/Space_Science",
            "https://www.space.com/feeds/all/",
            "https://www.nature.com/subjects/astronomy-and-planetary-science.rss",
            "https://www.universetoday.com/feed/",
            "https://www.spacedaily.com/spacedaily.xml",
            "https://phys.org/rss-feed/space-news/"
        )

        val cachedNews = cacheManager.loadNews()
        val existingTitles = cachedNews.flatMap { listOf(it.title.trim().lowercase(), it.originalTitle.trim().lowercase()) }.filter { it.isNotEmpty() }.toSet()
        val existingLinks = cachedNews.map { it.link.normalizeUrl() }.filter { it.isNotEmpty() }.toSet()

        val rawNews = mutableListOf<NewsItem>()

        for (url in rssUrls) {
            try {
                LogManager.log("FETCH", "Worker Запит: ${url.take(45)}...")
                var fetchedItems = listOf<NewsItem>()
                var successDirect = false

                val response = client.get(url) {
                    header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
                    header("Accept", "application/rss+xml, application/xml, text/xml;q=0.9, */*;q=0.8")
                    header("Accept-Language", "en-US,en;q=0.9,uk;q=0.8")
                }

                if (response.status.value in 200..299) {
                    val parser = NewsParserFactory.getParser(url)
                    fetchedItems = parser.parse(response.bodyAsText())
                    if (fetchedItems.isNotEmpty()) successDirect = true
                }

                if (!successDirect) {
                    LogManager.log("FETCH", "Worker Cloudflare блок. Спроба через AllOrigins...")
                    val proxyUrl = "https://api.allorigins.win/get?url=${URLEncoder.encode(url, "UTF-8")}"
                    val proxyResponse = client.get(proxyUrl)
                    
                    if (proxyResponse.status.value in 200..299) {
                        val json = JSONObject(proxyResponse.bodyAsText())
                        val rawXml = json.optString("contents", "")
                        if (rawXml.isNotEmpty()) {
                            val parser = NewsParserFactory.getParser(url)
                            fetchedItems = parser.parse(rawXml)
                        }
                    }
                }
                rawNews.addAll(fetchedItems)
            } catch (e: Exception) {
                LogManager.log("FETCH_ERR", "Worker Збій завантаження ${url.take(30)}: ${e.message}")
            }
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
            LogManager.log("WORKER", "Знайдено ${freshNews.size} нових новин. Зберігаємо...")

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
                    updateItemInCacheSafely(item)
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
        } catch (e: Exception) { LogManager.log("WORKER_ERR", "Сповіщення не показано: ${e.message}") }
    }
}
