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
import com.newsapp.BuildConfig
import com.newsapp.data.LogManager
import com.newsapp.data.NewsCacheManager
import com.newsapp.data.SnapiParser
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
        "https://www.nasa.gov/feed",
        "https://science.nasa.gov/feed",
        "https://www.esa.int/rssfeed/TopNews",
        "https://www.esa.int/rssfeed/Our_Activities/Space_Science",
        "https://www.nature.com/nature.rss",
        "https://www.universetoday.com/feed",
        "https://www.spacedaily.com/spacedaily.xml",
        "https://phys.org/rss-feed/space-news",
        "https://www.space.com/feeds/all"
    )

    private fun String.normalizeUrl() = this.lowercase().replace(Regex("^https?://"), "").replace(Regex("^www\\."), "").split("?")[0].trimEnd('/')

    private data class ScrapeResult(val text: String, val images: List<String>, val hasVideo: Boolean, val videoUrl: String = "")

    private suspend fun scrapeArticle(url: String): ScrapeResult {
        try {
            if (url.isEmpty()) return ScrapeResult("", emptyList(), false)

            // v466: 3-кроковий fetch
            // 1. Прямий fetch статті (статті рідко заблоковані — блокують RSS)
            // 2. Google Script proxy (bypass Cloudflare, повертає HTML)
            // 3. r.jina.ai (markdown, тільки для тексту)

            var html = ""
            try {
                val resp = client.get(url) { header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)") }
                if (resp.status.value in 200..299) {
                    val h = resp.bodyAsText()
                    if (h.length > 1000) html = h
                }
            } catch (_: Exception) {}

            // Якщо не знайшли og-тегів або сторінка коротка — пробуємо proxy
            if (html.isEmpty() || (!html.contains("og:image", true) && !html.contains("og:video", true))) {
                try {
                    val proxyUrl = "https://script.google.com/macros/s/AKfycbyPXHUAKXY3Cf3UKFumrDfF_LjhTRi5gK6-YGgsnrOgbYmFVn59GbWl860qyEHvtuam/exec?url=" + URLEncoder.encode(url, "UTF-8")
                    val resp = client.get(proxyUrl)
                    if (resp.status.value in 200..299) {
                        val h = resp.bodyAsText()
                        if (h.length > html.length) html = h
                    }
                } catch (_: Exception) {}
            }

            // Якщо досі немає <p> і og — остання спроба через r.jina.ai
            if (html.isEmpty() || (!html.contains("<p", true) && !html.contains("og:image", true))) {
                try {
                    val jinaUrl = "https://r.jina.ai/" + URLEncoder.encode(url, "UTF-8")
                    val resp = client.get(jinaUrl)
                    if (resp.status.value in 200..299) {
                        val h = resp.bodyAsText()
                        if (h.length > html.length) html = h
                    }
                } catch (_: Exception) {}
            }

            // Гнучкий regex: content може бути до property або після
            fun extractMeta(content: String, vararg props: String): String? {
                val propPattern = props.joinToString("|")
                val re1 = Regex("<meta[^>]+(?:property|name)=['\"](?:$propPattern)['\"][^>]+content=['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE)
                val re2 = Regex("<meta[^>]+content=['\"]([^'\"]+)['\"][^>]+(?:property|name)=['\"](?:$propPattern)['\"]", RegexOption.IGNORE_CASE)
                return (re1.find(content) ?: re2.find(content))?.groupValues?.getOrNull(1)
            }

            val imageList = mutableListOf<String>()
            val ogImage = extractMeta(html, "og:image", "twitter:image")
            if (ogImage != null) {
                var img = ogImage
                if (!img.startsWith("http")) {
                    val b = java.net.URL(url)
                    img = b.protocol + "://" + b.host + (if (img.startsWith("/")) img else "/" + img)
                }
                imageList.add(img)
            }

            var videoUrl = ""
            val ogVideo = extractMeta(html, "og:video", "og:video:url", "twitter:player", "twitter:player:stream")
            if (ogVideo != null) { videoUrl = ogVideo }
            if (videoUrl.isEmpty()) {
                val srcMatch = Regex("<video[^>]*>.*?<source[^>]+src=['\"]([^'\"]+)['\"]", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)
                if (srcMatch != null) videoUrl = srcMatch.groupValues[1]
            }
            if (videoUrl.isEmpty()) {
                val ytMatch = Regex("(?:youtube\\.com/(?:watch\\?v=|embed/|shorts/)|youtu\\.be/)([A-Za-z0-9_-]{11})").find(html)
                if (ytMatch != null) videoUrl = "https://www.youtube.com/watch?v=" + ytMatch.groupValues[1]
            }
            if (videoUrl.isNotEmpty() && !videoUrl.startsWith("http")) {
                try {
                    val b = java.net.URL(url)
                    videoUrl = b.protocol + "://" + b.host + (if (videoUrl.startsWith("/")) videoUrl else "/" + videoUrl)
                } catch (_: Exception) {}
            }

            val cleanHtml = html.replace(Regex("<(nav|header|footer|script|style|button|aside|noscript)[^>]*>[\\s\\S]*?<\\/\\1>", RegexOption.IGNORE_CASE), "")
            val scrapedText = Regex("<p[^>]*>(.*?)</p>", RegexOption.IGNORE_CASE).findAll(cleanHtml).map { it.groupValues[1].replace(Regex("<[^>]*>"), "").trim() }.filter { it.length > 80 && it.contains(".") }.joinToString("\n\n")
            val hasVideo = videoUrl.isNotEmpty() || html.contains("<video", ignoreCase=true) || html.contains("<iframe", ignoreCase=true) || html.contains("og:video", ignoreCase=true)
            return ScrapeResult(if (scrapedText.length >= 150) scrapedText else "", imageList, hasVideo, videoUrl)
        } catch (e: Exception) { return ScrapeResult("", emptyList(), false) }
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
                val isHardBlocked = url.contains("spacenews") || url.contains("space.com") || url.contains("spacedaily") || url.contains("phys.org") || url.contains("universetoday")

                if (isHardBlocked) {
                    val scraperKey = BuildConfig.SCRAPER_API_KEY
                    if (true) {
                        try {
                            val proxyUrl = "https://script.google.com/macros/s/AKfycbyPXHUAKXY3Cf3UKFumrDfF_LjhTRi5gK6-YGgsnrOgbYmFVn59GbWl860qyEHvtuam/exec?url=${URLEncoder.encode(url, "UTF-8")}"
                            val response = client.get(proxyUrl)
                            if (response.status.value in 200..299) {
                                fetchedItems = parser.parse(response.bodyAsText())
                            } else { LogManager.log("FETCH_ERR", "GoogleProxy відхилив запит HTTP ${response.status.value}") }
                        } catch (e: Exception) {}
                    } else {
                        LogManager.log("FETCH_WARN", "Пропущено $url: Немає SCRAPER_API_KEY")
                    }
                } else {
                    try {
                        val response = client.get(url) {
                            header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                            header("Accept", "application/xml, text/xml")
                        }
                        if (response.status.value in 200..299) {
                            val xml = response.bodyAsText()
                            if (xml.contains("<rss") || xml.contains("<feed") || xml.contains("<?xml") || xml.contains("rdf:RDF")) {
                                fetchedItems = parser.parse(xml)
                            }
                        }
                    } catch (e: Exception) {}

                    if (fetchedItems.isEmpty()) {
                        try {
                            val proxyUrl = "https://api.allorigins.win/get?disableCache=true&url=${URLEncoder.encode(url, "UTF-8")}"
                            val response = client.get(proxyUrl)
                            if (response.status.value in 200..299) {
                                val json = JSONObject(response.bodyAsText())
                                val xml = json.optString("contents", "")
                                if (xml.contains("<rss") || xml.contains("<feed") || xml.contains("<?xml") || xml.contains("rdf:RDF")) {
                                    fetchedItems = parser.parse(xml)
                                }
                            }
                        } catch (e: Exception) {}
                    }
                }
                rawNews.addAll(fetchedItems)
            } catch (e: Exception) {}
        }

        // SNAPI — окремий API, не блокується Cloudflare
        for (site in listOf("SpaceNews")) {
            rawNews.addAll(SnapiParser.fetch(site, 15))
        }

        val uniqueRawNews = rawNews.distinctBy { 
            val norm = it.link.normalizeUrl()
            if (norm.isNotEmpty()) norm else it.originalTitle.trim().lowercase()
        }

        val maxAgeMillis = System.currentTimeMillis() - (7L * 24 * 60 * 60 * 1000)
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
                val r = scrapeArticle(item.link)
                item.copy(
                    description = if (r.text.isNotEmpty()) r.text else item.description,
                    image = if (r.images.isNotEmpty()) r.images.first() else item.image,
                    images = if (r.images.isNotEmpty()) r.images else if (item.image.isNotEmpty()) listOf(item.image) else emptyList(),
                    hasVideo = r.hasVideo,
                    videoUrl = r.videoUrl,
                    status = "В черзі"
                )
            }
            val updatedCache = (enrichedNews + cachedNews).sortedByDescending { it.timestamp }.take(250)
            cacheManager.saveNews(updatedCache)
        }

        val allCached = cacheManager.loadNews()
        val toProcess = allCached.filter { it.status == "В черзі" || it.status == "Ліміт AI" }

        if (toProcess.isNotEmpty() && !AiRewriter.isGloballyBlocked()) {
            AiRewriter.processAllNewsWithAi(toProcess, appContext) { item ->
                updateItemInCacheSafely(item)
                if (freshNews.any { it.originalTitle == item.originalTitle }) {
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
