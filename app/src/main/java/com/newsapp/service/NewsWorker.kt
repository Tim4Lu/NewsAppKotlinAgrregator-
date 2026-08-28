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
    
    private val cacheFile = File(appContext.filesDir, "saved_news.json")

    private fun String.normalizeUrl(): String {
        return this.lowercase()
            .replace(Regex("^https?://"), "")
            .replace(Regex("^www\\."), "")
            .split("?")[0]
            .trimEnd('/')
    }

    private suspend fun scrapeArticle(url: String): Pair<String, String?> {
        try {
            if (url.isEmpty()) return Pair("", null)
            val response = client.get(url) {
                header(io.ktor.http.HttpHeaders.UserAgent, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            }
            val html = response.bodyAsText()
            var imageUrl: String? = null

            val ogMatch = Regex("<meta[^>]+(?:property|name)=[\"'](?:og:image|twitter:image)[\"'][^>]+content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(html)
            if (ogMatch != null) imageUrl = ogMatch.groupValues[1]

            if (!imageUrl.isNullOrEmpty() && !imageUrl.startsWith("http")) {
                val baseUrl = java.net.URL(url)
                imageUrl = "${baseUrl.protocol}://${baseUrl.host}$imageUrl"
            }

            val cleanHtml = html.replace(Regex("<(nav|header|footer|script|style|button|aside|noscript)[^>]*>[\\s\\S]*?<\\/\\1>", RegexOption.IGNORE_CASE), "")
            val pMatches = Regex("<p[^>]*>(.*?)</p>", RegexOption.IGNORE_CASE).findAll(cleanHtml)
            val validParagraphs = pMatches
                .map { it.groupValues[1].replace(Regex("<[^>]*>"), "").trim() }
                .filter { t -> t.length > 80 && t.contains(".") }
                .toList()

            val scrapedText = validParagraphs.joinToString("\n\n")
            return Pair(if (scrapedText.length >= 150) scrapedText else "", imageUrl)
        } catch (e: Exception) {
            return Pair("", null)
        }
    }

    override suspend fun doWork(): Result {
        LogManager.log("WORKER", "Запуск фонової перевірки новин...")

        try {
            val channelId = "news_worker_channel"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "Фоновий пошук новин",
                    NotificationManager.IMPORTANCE_LOW
                )
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
        } catch (e: Exception) {
            LogManager.log("WORKER_ERR", "Не вдалося закріпити Foreground: ${e.message}")
        }

        val rssUrls = listOf(
            "https://www.nasa.gov/feed/",
            "https://www.nasa.gov/news-release/feed/",
            "https://blogs.nasa.gov/feed/",
            "https://www.esa.int/rssfeed/Our_Activities/Space_Science",
            "https://www.space.com/feeds/all/",
            "https://www.universetoday.com/feed",
            "https://www.spacedaily.com/spacedaily.xml",
            "https://phys.org/rss-feed/space-news/"
        )

        val (existingTitles, existingLinks) = getCachedTitlesAndLinks()
        val rawNews = mutableListOf<NewsItem>()

        for (url in rssUrls) {
                try {
                    com.newsapp.data.LogManager.log("FETCH", "Запит: ${url.take(45)}...")
                    var fetchedItems = listOf<com.newsapp.model.NewsItem>()
                    var successDirect = false

                    val response = client.get(url) {
                        header(io.ktor.http.HttpHeaders.UserAgent, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        header(io.ktor.http.HttpHeaders.Accept, "application/rss+xml, application/xml, text/xml")
                    }

                    com.newsapp.data.LogManager.log("FETCH", "HTTP ${response.status.value} для ${url.take(30)}")

                    if (response.status.value in 200..299) {
                        val bodyText = response.bodyAsText()
                        com.newsapp.data.LogManager.log("FETCH", "Розмір тіла: ${bodyText.length} симв.")
                        
                        val parser = com.newsapp.data.NewsParserFactory.getParser(url)
                        fetchedItems = parser.parse(bodyText)
                        
                        if (fetchedItems.isNotEmpty()) {
                            successDirect = true
                            com.newsapp.data.LogManager.log("FETCH_OK", "Знайдено ${fetchedItems.size} новин (прямо)")
                        } else {
                            com.newsapp.data.LogManager.log("FETCH_WARN", "Парсер не знайшов новин (можливо XML змінився)")
                        }
                    } else {
                        com.newsapp.data.LogManager.log("FETCH_ERR", "Помилка сервера: HTTP ${response.status.value}")
                    }

                    if (!successDirect) {
                        com.newsapp.data.LogManager.log("FETCH", "Спроба через резервний rss2json...")
                        val cleanUrl = if (url.contains("allorigins")) url.substringAfter("url=") else url
                        val apiUrl = "https://api.rss2json.com/v1/api.json?rss_url=${java.net.URLEncoder.encode(cleanUrl, "UTF-8")}"
                        
                        val jsonResponse = client.get(apiUrl)
                        com.newsapp.data.LogManager.log("FETCH", "rss2json HTTP: ${jsonResponse.status.value}")
                        
                        if (jsonResponse.status.value in 200..299) {
                            val jsonBody = jsonResponse.bodyAsText()
                            com.newsapp.data.LogManager.log("FETCH", "rss2json тіло: ${jsonBody.length} симв.")
                            
                            val json = org.json.JSONObject(jsonBody)
                            if (json.optString("status") == "ok") {
                                val itemsArray = json.optJSONArray("items")
                                val fallbackItems = mutableListOf<com.newsapp.model.NewsItem>()
                                
                                val sourceName = when {
                                    cleanUrl.contains("nasa.gov") -> "NASA"
                                    cleanUrl.contains("esa.int") -> "ESA"
                                    cleanUrl.contains("space.com") -> "Space.com"
                                    cleanUrl.contains("spacedaily") -> "Space Daily"
                                    cleanUrl.contains("universetoday") -> "Universe Today"
                                    cleanUrl.contains("phys.org") -> "Phys.org"
                                    else -> "Новина"
                                }

                                for (i in 0 until (itemsArray?.length() ?: 0)) {
                                    val obj = itemsArray!!.getJSONObject(i)
                                    var rawTitle = obj.optString("title").replace("(?i)APOD:\\s*(-\\s*)?".toRegex(), "").trim()
                                    var rawDesc = obj.optString("description", "").replace(Regex("<[^>]*>"), "").trim()
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
                                            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ENGLISH)
                                            ts = sdf.parse(pubDate)?.time ?: ts
                                        } catch(e: Exception) {}
                                    }

                                    fallbackItems.add(
                                        com.newsapp.model.NewsItem(
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
                                com.newsapp.data.LogManager.log("FETCH_OK", "Знайдено ${fetchedItems.size} новин (через rss2json)")
                            } else {
                                com.newsapp.data.LogManager.log("FETCH_ERR", "Помилка rss2json: ${json.optString("message")}")
                            }
                        }
                    }
                    rawNews.addAll(fetchedItems)
                } catch (e: Exception) {
                    com.newsapp.data.LogManager.log("FETCH_ERR", "Критичний збій завантаження ${url.take(30)}: ${e.message}")
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
            com.newsapp.data.LogManager.log("WORKER", "Знайдено ${freshNews.size} нових новин. Фіксуємо в кеш...")

            val enrichedNews = freshNews.map { item ->
                val (fullText, scrapedImage) = scrapeArticle(item.link)
                item.copy(
                    description = if (fullText.isNotEmpty()) fullText else item.description,
                    image = if (!scrapedImage.isNullOrEmpty()) scrapedImage else item.image,
                    status = "В черзі"
                )
            }
            saveToCache(enrichedNews)

            if (AiRewriter.isGloballyBlocked()) {
                LogManager.log("WORKER", "ШІ заблоковано до ${AiRewriter.getBlockTimeFormatted()}. Новини додано в чергу.")
            } else {
                AiRewriter.processAllNewsWithAi(enrichedNews, appContext) { item ->
                updateItemInCache(item)
                showNewsNotification(item)
                }
            }
        }
        return Result.success()
    }

    private fun updateItemInCache(item: NewsItem) {
        try {
            if (cacheFile.exists()) {
                val jsonArray = JSONArray(cacheFile.readText())
                val newArray = JSONArray()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    if (obj.optString("id") == item.id) {
                        newArray.put(JSONObject().apply {
                            put("id", item.id); put("title", item.title); put("originalTitle", item.originalTitle)
                            put("link", item.link); put("description", item.description); put("source", item.source)
                            put("image", item.image); put("status", item.status); put("telegramCaption", item.telegramCaption)
                            put("timestamp", item.timestamp)
                        })
                    } else { newArray.put(obj) }
                }
                cacheFile.writeText(newArray.toString())
            }
        } catch(e: Exception) {}
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
            val existingArray = if (cacheFile.exists()) JSONArray(cacheFile.readText()) else JSONArray()
            val newArray = JSONArray()
            
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
                newArray.put(obj)
            }
            
            val limit = 250 - newItems.size
            var added = 0
            for (i in 0 until existingArray.length()) {
                if (added >= limit) break
                newArray.put(existingArray.getJSONObject(i))
                added++
            }
            cacheFile.writeText(newArray.toString())
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
            LogManager.log("WORKER_ERR", "Сповіщення не показано: ${e.message}")
        }
    }
}
