import re

# 1. Точковий фікс у NewsViewModel.kt
vm_path = "app/src/main/java/com/newsapp/ui/viewmodel/NewsViewModel.kt"
with open(vm_path, "r", encoding="utf-8") as f:
    vm_code = f.read()

vm_code = vm_code.replace('https://www.space.com/feeds/all"', 'https://www.space.com/feeds/all/"')
vm_code = vm_code.replace('https://www.esa.int/rssfeed/TopNews"', 'https://www.esa.int/rssfeed/Our_Activities/Space_Science"')

old_cache = """val uniqueCached = cached.distinctBy {
                    val normLink = it.link.normalizeUrl()
                    if (normLink.isNotEmpty()) normLink else it.originalTitle.ifEmpty { it.title }
                }"""

new_cache = """val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
                val uniqueCached = cached.distinctBy {
                    val normLink = it.link.normalizeUrl()
                    if (normLink.isNotEmpty()) normLink else it.originalTitle.ifEmpty { it.title }
                }.filter { it.timestamp > thirtyDaysAgo }"""

if old_cache in vm_code:
    vm_code = vm_code.replace(old_cache, new_cache)

with open(vm_path, "w", encoding="utf-8") as f:
    f.write(vm_code)

# 2. Точковий фікс у NewsWorker.kt
worker_path = "app/src/main/java/com/newsapp/service/NewsWorker.kt"
with open(worker_path, "r", encoding="utf-8") as f:
    worker_code = f.read()

worker_code = worker_code.replace('https://www.space.com/feeds/all"', 'https://www.space.com/feeds/all/"')
worker_code = worker_code.replace('https://www.esa.int/rssfeed/TopNews"', 'https://www.esa.int/rssfeed/Our_Activities/Space_Science"')
worker_code = worker_code.replace('https://api.allorigins.win/raw?url=https://www.nasa.gov/news-release/feed/"', 'https://www.nasa.gov/news-release/feed/"')

with open(worker_path, "w", encoding="utf-8") as f:
    f.write(worker_code)

print("Точкові виправлення застосовано!")
