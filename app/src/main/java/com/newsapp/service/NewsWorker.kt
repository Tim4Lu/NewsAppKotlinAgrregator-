package com.newsapp.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.newsapp.data.LogManager
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
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.net.URL

class NewsWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val client = HttpClient(CIO) { followRedirects = true }
    private val aiRewriter = AiRewriter()
    private val cacheFile = File(appContext.filesDir, "saved_news.json")

    override suspend fun doWork(): Result {
        com.newsapp.data.LogManager.log("TRACE", "Викликано функцію: doWork")
        LogManager.log("WORKER", "Запуск фонової перевірки новин...")

        val rssUrls = listOf(
            "https://www.nasa.gov/rss/dyn/breaking_news.rss",
            "https://www.space.com/feeds/all",
            "https://www.universetoday.com/feed",
            "https://www.spacedaily.com/spacedaily.xml",
            "https://phys.org/rss-feed/space-news/"
        )

        val cachedTitles = getCachedTitles()
        val rawNews = mutableListOf<NewsItem>()

        for (url in rssUrls) {
            try {
                val response = client.get(url) { header(HttpHeaders.UserAgent, "Mozilla/5.0") }
                if (response.status.value in 200..299) {
                    rawNews.addAll(parseRss(response.bodyAsText(), getSourceName(url)))
                }
            } catch (e: Exception) {
                LogManager.log("WORKER_ERR", "Помилка RSS: ${e.message}")
            }
        }

        val freshNews = rawNews.filter { !cachedTitles.contains(it.title) }

        if (freshNews.isNotEmpty()) {
            LogManager.log("WORKER", "Знайдено ${freshNews.size} новин. Обробка ШІ...")
            val processedNews = mutableListOf<NewsItem>()

            aiRewriter.processAllNewsWithAi(freshNews) { item ->
                processedNews.add(item)
            }

            saveToCache(processedNews)

            processedNews.forEach { item ->
                showNewsNotification(item)
            }
        }

        return Result.success()
    }

    private fun getCachedTitles(): Set<String> {
        com.newsapp.data.LogManager.log("TRACE", "Викликано функцію: getCachedTitles")
        val set = mutableSetOf<String>()
        try {
            if (cacheFile.exists()) {
                val jsonArray = JSONArray(cacheFile.readText())
                for (i in 0 until jsonArray.length()) {
                    set.add(jsonArray.getJSONObject(i).optString("title"))
                }
            }
        } catch (e: Exception) { }
        return set
    }

    private fun saveToCache(newItems: List<NewsItem>) {
        com.newsapp.data.LogManager.log("TRACE", "Викликано функцію: saveToCache")
        try {
            val jsonArray = if (cacheFile.exists()) JSONArray(cacheFile.readText()) else JSONArray()
            newItems.forEach { item ->
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("title", item.title)
                    put("link", item.link)
                    put("description", item.description)
                    put("source", item.source)
                    put("image", item.image)
                    put("status", item.status)
                    put("telegramCaption", item.telegramCaption)
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
            com.newsapp.data.LogManager.log("WORKER_ERR", "Сповіщення не показано: ${e.message}")
        }
    }

    
    private fun getSourceName(url: String): String {
        com.newsapp.data.LogManager.log("TRACE", "Викликано функцію: getSourceName")
        return when {
            url.contains("nasa.gov") -> "NASA"
            url.contains("space.com") -> "Space.com"
            url.contains("universetoday.com") -> "Universe Today"
            url.contains("spacedaily.com") -> "Space Daily"
            url.contains("phys.org") -> "Phys.org"
            else -> java.net.URL(url).host
        }
    }

    private fun parseRss(xml: String, sourceName: String): List<NewsItem> {
        com.newsapp.data.LogManager.log("TRACE", "Викликано функцію: parseRss")
        val items = mutableListOf<NewsItem>()
        try {
            val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
            parser.setInput(StringReader(xml))

            var eventType = parser.eventType
            var title: String? = null
            var link: String? = null
            var desc: String? = null
            var insideItem = false
            var currentTag = ""

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name?.lowercase() ?: ""
                        if (currentTag == "item" || currentTag == "entry") {
                            insideItem = true; title = null; link = null; desc = null
                        } else if (insideItem && currentTag == "link") {
                            val href = parser.getAttributeValue(null, "href")
                            if (!href.isNullOrEmpty()) link = href
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (insideItem && parser.text?.isNotBlank() == true) {
                            when (currentTag) {
                                "title" -> title = (title ?: "") + parser.text
                                "link" -> if (link.isNullOrEmpty()) link = parser.text
                                "description", "summary" -> desc = (desc ?: "") + parser.text
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val name = parser.name ?: ""
                        if (name.equals("item", true) || name.equals("entry", true)) {
                            if (!title.isNullOrEmpty()) {
                                items.add(NewsItem(title = title!!.trim(), link = link?.trim() ?: "", description = desc?.replace(Regex("<.*?>"), "")?.trim() ?: "", source = sourceName))
                            }
                            insideItem = false
                        }
                        currentTag = ""
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) { }
        return items
    }
}
