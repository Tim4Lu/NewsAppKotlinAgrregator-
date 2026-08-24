import re

# 1. Вичищаємо NewsWorker.kt (allorigins)
worker_path = "app/src/main/java/com/newsapp/service/NewsWorker.kt"
with open(worker_path, "r", encoding="utf-8") as f:
    worker_code = f.read()

worker_old = r'val rssUrls = listOf\([\s\S]*?\)'
worker_new = """val rssUrls = listOf(
        "https://www.nasa.gov/feed/",
        "https://www.nasa.gov/news-release/feed/",
        "https://blogs.nasa.gov/feed/",
        "https://www.esa.int/rssfeed/Our_Activities/Space_Science",
        "https://www.space.com/feeds/all",
        "https://www.universetoday.com/feed",
        "https://www.spacedaily.com/spacedaily.xml",
        "https://phys.org/rss-feed/space-news/"
    )"""
worker_code = re.sub(worker_old, worker_new, worker_code)
with open(worker_path, "w", encoding="utf-8") as f:
    f.write(worker_code)

# 2. Оновлюємо NewsViewModel.kt (URL + Фільтр старих новин)
vm_path = "app/src/main/java/com/newsapp/ui/viewmodel/NewsViewModel.kt"
with open(vm_path, "r", encoding="utf-8") as f:
    vm_code = f.read()

vm_urls_old = r'private val rssUrls = listOf\([\s\S]*?\)'
vm_urls_new = """private val rssUrls = listOf(
        "https://www.nasa.gov/feed/",
        "https://www.nasa.gov/news-release/feed/",
        "https://blogs.nasa.gov/feed/",
        "https://www.esa.int/rssfeed/Our_Activities/Space_Science",
        "https://www.space.com/feeds/all",
        "https://www.universetoday.com/feed",
        "https://www.spacedaily.com/spacedaily.xml",
        "https://phys.org/rss-feed/space-news/"
    )"""
vm_code = re.sub(vm_urls_old, vm_urls_new, vm_code)

# Знищуємо привидів з кешу (видаляємо старіші за 30 днів)
cache_old = r'val uniqueCached = cached\.distinctBy \{[\s\S]*?\}'
cache_new = """val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
                val uniqueCached = cached.distinctBy {
                    val normLink = it.link.normalizeUrl()
                    if (normLink.isNotEmpty()) normLink else it.originalTitle.ifEmpty { it.title }
                }.filter { it.timestamp > thirtyDaysAgo }"""
vm_code = re.sub(cache_old, cache_new, vm_code)

with open(vm_path, "w", encoding="utf-8") as f:
    f.write(vm_code)

print("Усі помилки виправлено!")
