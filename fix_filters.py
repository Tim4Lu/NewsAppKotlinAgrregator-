import re

def fix_filter(path):
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()
    
    # Шукаємо блок від початку фільтра до фігурної дужки, незалежно від пробілів
    pattern = r"val freshNews = uniqueRawNews\.filter \{ item ->[\s\S]*?!isTitleDuplicate && !isLinkDuplicate\s*\}"
    
    replacement = """val maxAgeMillis = System.currentTimeMillis() - (3L * 24 * 60 * 60 * 1000)
        val freshNews = uniqueRawNews.filter { item ->
            val normTitle = item.title.trim().lowercase()
            val origTitle = item.originalTitle.trim().lowercase()
            val normLink = item.link.normalizeUrl()

            val isTitleDuplicate = existingTitles.contains(normTitle) || (origTitle.isNotEmpty() && existingTitles.contains(origTitle))
            val isLinkDuplicate = normLink.isNotEmpty() && existingLinks.contains(normLink)
            val isRecent = item.timestamp > maxAgeMillis

            !isTitleDuplicate && !isLinkDuplicate && isRecent
        }"""
    
    new_content = re.sub(pattern, replacement, content)
    with open(path, "w", encoding="utf-8") as f:
        f.write(new_content)

fix_filter("app/src/main/java/com/newsapp/ui/viewmodel/NewsViewModel.kt")
fix_filter("app/src/main/java/com/newsapp/service/NewsWorker.kt")
print("Фільтри новин успішно оновлено!")
