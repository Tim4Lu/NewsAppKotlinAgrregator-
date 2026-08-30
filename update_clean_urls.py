import re

for filepath in ["app/src/main/java/com/newsapp/ui/viewmodel/NewsViewModel.kt", "app/src/main/java/com/newsapp/service/NewsWorker.kt"]:
    with open(filepath, "r", encoding="utf-8") as f:
        content = f.read()

    # Шукаємо та замінюємо блок rssUrls на чистий без allorigins
    old_block_pattern = r'private val rssUrls = listOf\([\s\S]*?\)'
    
    clean_urls_block = """private val rssUrls = listOf(
        "https://www.nasa.gov/feed/",
        "https://www.nasa.gov/news-release/feed/",
        "https://blogs.nasa.gov/feed/",
        "https://www.esa.int/rssfeed/TopNews",
        "https://www.space.com/feeds/all",
        "https://www.universetoday.com/feed",
        "https://www.spacedaily.com/spacedaily.xml",
        "https://phys.org/rss-feed/space-news/"
    )"""

    new_content = re.sub(old_block_pattern, clean_urls_block, content)
    
    with open(filepath, "w", encoding="utf-8") as f:
        f.write(new_content)

print("Clean URLs applied!")
