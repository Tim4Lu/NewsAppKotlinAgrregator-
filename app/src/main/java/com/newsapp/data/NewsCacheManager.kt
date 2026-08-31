package com.newsapp.data

import android.content.Context
import com.newsapp.model.NewsItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class NewsCacheManager(context: Context) {
    private val cacheFile = File(context.filesDir, "saved_news.json")

    companion object {
        private val fileMutex = Mutex()
    }

    suspend fun loadNews(): List<NewsItem> = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            try {
                LogManager.log("TRACE", "Викликано функцію: NewsCacheManager.loadNews")
                if (!cacheFile.exists()) return@withContext emptyList()
                val jsonArray = JSONArray(cacheFile.readText())
                val cached = mutableListOf<NewsItem>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    cached.add(
                        NewsItem(
                            id = obj.optString("id"),
                            title = obj.optString("title"),
                            originalTitle = obj.optString("originalTitle"),
                            link = obj.optString("link"),
                            description = obj.optString("description"),
                            source = obj.optString("source"),
                            image = obj.optString("image"),
                            status = obj.optString("status", "Готово"),
                            telegramCaption = obj.optString("telegramCaption"),
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                        )
                    )
                }
                cached
            } catch (e: Exception) {
                LogManager.log("CACHE_ERR", "Помилка читання кешу: ${e.message}")
                emptyList()
            }
        }
    }

    suspend fun saveNews(list: List<NewsItem>) = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            try {
                LogManager.log("TRACE", "Викликано функцію: NewsCacheManager.saveNews")
                val jsonArray = JSONArray()
                list.take(250).forEach { item ->
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
            } catch (e: Exception) {
                LogManager.log("CACHE_ERR", "Помилка запису кешу: ${e.message}")
            }
        }
    }
}
