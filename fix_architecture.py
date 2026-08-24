import re
import os

# 1. Виправляємо подвійний запуск в ViewModel
vm = "app/src/main/java/com/newsapp/ui/viewmodel/NewsViewModel.kt"
with open(vm, "r", encoding="utf-8") as f:
    text = f.read()
text = text.replace("saveNewsToDisk(_newsList.value)\n                checkAndRetryUntranslatedNews()", "saveNewsToDisk(_newsList.value)")
with open(vm, "w", encoding="utf-8") as f:
    f.write(text)

# 2. Виправляємо втрату даних у Worker та зберігаємо весь кеш перед ШІ
nw = "app/src/main/java/com/newsapp/service/NewsWorker.kt"
with open(nw, "r", encoding="utf-8") as f:
    text = f.read()

new_worker = """        if (freshNews.isNotEmpty()) {
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

            AiRewriter.processAllNewsWithAi(enrichedNews, appContext) { item ->
                updateItemInCache(item)
                showNewsNotification(item)
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

    private fun getCachedTitlesAndLinks():"""

text = re.sub(r"if \(freshNews\.isNotEmpty\(\)\) \{.*?return Result\.success\(\)\n\s*\}\n\n\s*private fun getCachedTitlesAndLinks\(\):", new_worker, text, flags=re.DOTALL)
with open(nw, "w", encoding="utf-8") as f:
    f.write(text)
