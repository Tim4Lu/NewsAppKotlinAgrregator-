import re

# 1. Виправляємо NewsViewModel.kt (гібридний запуск без allorigins)
vm = "app/src/main/java/com/newsapp/ui/viewmodel/NewsViewModel.kt"
with open(vm, "r", encoding="utf-8") as f:
    text = f.read()

# Замінюємо список URL на чисті сайти без allorigins
old_urls = """    private val rssUrls = listOf\(
        "https://api.allorigins.win/raw\?url=https://www.nasa.gov/feed/",
        "https://api.allorigins.win/raw\?url=https://www.nasa.gov/news-release/feed/",
        "https://api.allorigins.win/raw\?url=https://blogs.nasa.gov/feed/",
        "https://www.esa.int/rssfeed/TopNews",
        "https://api.allorigins.win/raw\?url=https://www.space.com/feeds/all",
        "https://www.universetoday.com/feed",
        "https://www.spacedaily.com/spacedaily.xml",
        "https://phys.org/rss-feed/space-news/"
    \)"""

new_urls = """    private val rssUrls = listOf(
        "https://www.nasa.gov/feed/",
        "https://www.nasa.gov/news-release/feed/",
        "https://blogs.nasa.gov/feed/",
        "https://www.esa.int/rssfeed/TopNews",
        "https://www.space.com/feeds/all",
        "https://www.universetoday.com/feed",
        "https://www.spacedaily.com/spacedaily.xml",
        "https://phys.org/rss-feed/space-news/"
    )"""

text = re.sub(old_urls, new_urls, text)

# Повністю переписуємо цикл завантаження для чистого гібрида (Прямий XML -> rss2json)
old_loop = re.search(r'for \(url in rssUrls\) \{.*?rawNews\.addAll\(fetchedItems\)\n\s*\}', text, re.DOTALL)
if old_loop:
    hybrid_loop = """for (url in rssUrls) {
                try {
                    com.newsapp.data.LogManager.log("FETCH", "Запит: ${url.take(45)}")
                    var fetchedItems = listOf<com.newsapp.model.NewsItem>()
                    var success = false

                    try {
                        val response = client.get(url) {
                            header(io.ktor.http.HttpHeaders.UserAgent, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                            header(io.ktor.http.HttpHeaders.Accept, "application/rss+xml, application/xml, text/xml, */*")
                        }
                        if (response.status.value in 200..299) {
                            val bodyText = response.bodyAsText()
                            val parser = com.newsapp.data.NewsParserFactory.getParser(url)
                            fetchedItems = parser.parse(bodyText)
                            if (fetchedItems.isNotEmpty()) {
                                success = true
                                com.newsapp.data.LogManager.log("FETCH_OK", "Знайдено ${fetchedItems.size} (прямо)")
                            }
                        }
                    } catch (e: Exception) {
                        // Прямий запит заблоковано або таймаут, йдемо на резерв
                    }

                    if (!success) {
                        val apiUrl = "https://api.rss2json.com/v1/api.json?rss_url=" + java.net.URLEncoder.encode(url, "UTF-8")
                        val jsonResponse = client.get(apiUrl)
                        if (jsonResponse.status.value in 200..299) {
                            val json = org.json.JSONObject(jsonResponse.bodyAsText())
                            if (json.optString("status") == "ok") {
                                val itemsArray = json.optJSONArray("items")
                                val fallbackItems = mutableListOf<com.newsapp.model.NewsItem>()
                                val sourceName = when {
                                    url.contains("nasa.gov") -> "NASA"
                                    url.contains("esa.int") -> "ESA"
                                    url.contains("space.com") -> "Space.com"
                                    url.contains("spacedaily") -> "Space Daily"
                                    url.contains("universetoday") -> "Universe Today"
                                    url.contains("phys.org") -> "Phys.org"
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
                                if (fetchedItems.isNotEmpty()) {
                                    com.newsapp.data.LogManager.log("FETCH_OK", "Знайдено ${fetchedItems.size} (через rss2json)")
                                }
                            }
                        }
                    }
                    rawNews.addAll(fetchedItems)
                } catch (e: Exception) {
                    com.newsapp.data.LogManager.log("FETCH_ERR", "Помилка джерела: ${e.message}")
                }
            }"""
    text = text.replace(old_loop.group(0), hybrid_loop)

with open(vm, "w", encoding="utf-8") as f:
    f.write(text)

# 2. Робимо те ж саме для NewsWorker.kt
nw = "app/src/main/java/com/newsapp/service/NewsWorker.kt"
with open(nw, "r", encoding="utf-8") as f:
    text_nw = f.read()

text_nw = re.sub(old_urls, new_urls, text_nw)
old_loop_nw = re.search(r'for \(url in rssUrls\) \{.*?rawNews\.addAll\(fetchedItems\)\n\s*\}', text_nw, re.DOTALL)
if old_loop_nw:
    text_nw = text_nw.replace(old_loop_nw.group(0), hybrid_loop)

with open(nw, "w", encoding="utf-8") as f:
    f.write(text_nw)
