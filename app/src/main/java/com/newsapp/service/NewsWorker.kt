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

        val existingTitlesAndLinks = getCachedTitlesAndLinks()
        val rawNews = mutableListOf<NewsItem>()

        for (url in rssUrls) {
            try {
                val response = client.get(url) {
                    header(HttpHeaders.UserAgent, "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36")
                }
                if (response.status.value in 200..299) {
                    val parser = NewsParserFactory.getParser(url)
                    rawNews.addAll(parser.parse(response.bodyAsText()))
                }
            } catch (e: Exception) {
                LogManager.log("WORKER_ERR", "Помилка RSS $url: ${e.message}")
            }
        }

        val freshNews = rawNews.filter { item ->
            val normTitle = item.title.trim().lowercase()
            normTitle.isNotEmpty() && !existingTitlesAndLinks.contains(normTitle)
        }

        if (freshNews.isNotEmpty()) {
            LogManager.log("WORKER", "Знайдено ${freshNews.size} нових новин. Обробка ШІ...")
            val processedNews = mutableListOf<NewsItem>()

            aiRewriter.processAllNewsWithAi(freshNews) { item ->
                processedNews.add(item)
                showNewsNotification(item)
            }

            saveToCache(processedNews)
        } else {
            LogManager.log("WORKER", "Нових новин під час фонової перевірки немає")
        }

        return Result.success()
    }

    private fun getCachedTitlesAndLinks(): Set<String> {
        com.newsapp.data.LogManager.log("TRACE", "Викликано функцію: getCachedTitlesAndLinks")
        val set = mutableSetOf<String>()
        try {
            if (cacheFile.exists()) {
                val jsonArray = JSONArray(cacheFile.readText())
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val t = obj.optString("title").trim().lowercase()
                    val orig = obj.optString("originalTitle").trim().lowercase()
                    if (t.isNotEmpty()) set.add(t)
                    if (orig.isNotEmpty()) set.add(orig)
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
            com.newsapp.data.LogManager.log("WORKER_ERR", "Сповіщення не показано: ${e.message}")
        }
    }
}
