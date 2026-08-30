import os

def replace_in_file(path, old, new):
    with open(path, "r", encoding="utf-8") as f:
        code = f.read()
    code = code.replace(old, new)
    with open(path, "w", encoding="utf-8") as f:
        f.write(code)

# 1. Фікс дат у RssParsers.kt
path = "app/src/main/java/com/newsapp/data/RssParsers.kt"
replace_in_file(path, "var timestamp = System.currentTimeMillis()", "var timestamp = 0L")

old_formats = """                val formats = listOf(
                    "EEE, dd MMM yyyy HH:mm:ss Z",
                    "EEE, dd MMM yyyy HH:mm:ss zzz",
                    "yyyy-MM-dd'T'HH:mm:ss'Z'",
                    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                    "yyyy-MM-dd HH:mm:ss"
                )"""
new_formats = """                val formats = listOf(
                    "EEE, dd MMM yyyy HH:mm:ss Z",
                    "EEE, dd MMM yyyy HH:mm:ss zzz",
                    "EEE, dd MMM yyyy",
                    "yyyy-MM-dd'T'HH:mm:ss'Z'",
                    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                    "yyyy-MM-dd'T'HH:mm:ssXXX",
                    "yyyy-MM-dd HH:mm:ss"
                )"""
replace_in_file(path, old_formats, new_formats)

# 2. Оновлення фідів та додавання жорсткого фільтру в ViewModel і Worker
old_urls = """        val rssUrls = listOf(
            "https://www.nasa.gov/feed/",
            "https://www.nasa.gov/news-release/feed/",
            "https://blogs.nasa.gov/feed/",
            "https://www.esa.int/rssfeed/Our_Activities/Space_Science",
            "https://www.space.com/feeds/all/",
            "https://www.universetoday.com/feed",
            "https://www.spacedaily.com/spacedaily.xml",
            "https://phys.org/rss-feed/space-news/"
        )"""
new_urls = """        val rssUrls = listOf(
            "https://www.nasa.gov/feed/",
            "https://science.nasa.gov/feed/",
            "https://www.esa.int/rssfeed/TopNews",
            "https://www.esa.int/rssfeed/Our_Activities/Space_Science",
            "https://www.space.com/feeds/all/",
            "https://www.universetoday.com/feed",
            "https://www.spacedaily.com/spacedaily.xml",
            "https://phys.org/rss-feed/space-news/"
        )"""

old_filter = """        val freshNews = uniqueRawNews.filter { item ->
            val normTitle = item.title.trim().lowercase()
            val origTitle = item.originalTitle.trim().lowercase()
            val normLink = item.link.normalizeUrl()

            val isTitleDuplicate = existingTitles.contains(normTitle) || (origTitle.isNotEmpty() && existingTitles.contains(origTitle))
            val isLinkDuplicate = normLink.isNotEmpty() && existingLinks.contains(normLink)

            !isTitleDuplicate && !isLinkDuplicate
        }"""
new_filter = """        val maxAgeMillis = System.currentTimeMillis() - (3L * 24 * 60 * 60 * 1000) // 3 дні
        val freshNews = uniqueRawNews.filter { item ->
            val normTitle = item.title.trim().lowercase()
            val origTitle = item.originalTitle.trim().lowercase()
            val normLink = item.link.normalizeUrl()

            val isTitleDuplicate = existingTitles.contains(normTitle) || (origTitle.isNotEmpty() && existingTitles.contains(origTitle))
            val isLinkDuplicate = normLink.isNotEmpty() && existingLinks.contains(normLink)
            val isRecent = item.timestamp > maxAgeMillis

            isRecent && !isTitleDuplicate && !isLinkDuplicate
        }"""

for p in ["app/src/main/java/com/newsapp/ui/viewmodel/NewsViewModel.kt", "app/src/main/java/com/newsapp/service/NewsWorker.kt"]:
    replace_in_file(p, old_urls, new_urls)
    replace_in_file(p, "var ts = System.currentTimeMillis()", "var ts = 0L")
    replace_in_file(p, old_filter, new_filter)

# 3. Чистимо кеш ViewModel з 30 днів до 3
vm_path = "app/src/main/java/com/newsapp/ui/viewmodel/NewsViewModel.kt"
old_cache = """                val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
                val uniqueCached = cached.distinctBy {
                    val normLink = it.link.normalizeUrl()
                    if (normLink.isNotEmpty()) normLink else it.originalTitle.ifEmpty { it.title }
                }.filter { it.timestamp > thirtyDaysAgo }"""
new_cache = """                val threeDaysAgo = System.currentTimeMillis() - (3L * 24 * 60 * 60 * 1000)
                val uniqueCached = cached.distinctBy {
                    val normLink = it.link.normalizeUrl()
                    if (normLink.isNotEmpty()) normLink else it.originalTitle.ifEmpty { it.title }
                }.filter { it.timestamp > threeDaysAgo }"""
replace_in_file(vm_path, old_cache, new_cache)

print("Дати і фіди виправлено!")
