package com.newsapp.data

import com.newsapp.model.NewsItem

interface BaseRssParser {
    fun parse(xml: String): List<NewsItem>
}

class NasaParser : BaseRssParser { override fun parse(xml: String) = parseRobust(xml, "NASA") }
class SpaceComParser : BaseRssParser { override fun parse(xml: String) = parseRobust(xml, "Space.com") }
class SpaceDailyParser : BaseRssParser { override fun parse(xml: String) = parseRobust(xml, "Space Daily") }
class UniverseTodayParser : BaseRssParser { override fun parse(xml: String) = parseRobust(xml, "Universe Today") }
class PhysOrgParser : BaseRssParser { override fun parse(xml: String) = parseRobust(xml, "Phys.org") }

private fun parseRobust(xml: String, sourceName: String): List<NewsItem> {
    val items = mutableListOf<NewsItem>()
    try {
        val itemRegex = Regex("<(?:item|entry)[^>]*>(.*?)</(?:item|entry)>", RegexOption.DOTALL or RegexOption.IGNORE_CASE)
        val matches = itemRegex.findAll(xml)

        for (match in matches) {
            val block = match.groupValues[1]

            val titleMatch = Regex("<title[^>]*>(.*?)</title>", RegexOption.DOTALL or RegexOption.IGNORE_CASE).find(block)
            var title = titleMatch?.groupValues?.get(1)?.replace(Regex("<!\\[CDATA\\[(.*?)\\]\\]>", RegexOption.DOTALL), "$1")?.trim()

            val linkMatch = Regex("<link[^>]*href=[\"']([^\"']+)[\"'][^>]*>|<link[^>]*>(.*?)</link>", RegexOption.DOTALL or RegexOption.IGNORE_CASE).find(block)
            var link = linkMatch?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }?.trim()

            val descMatch = Regex("<(?:description|summary|content|content:encoded)[^>]*>(.*?)</(?:description|summary|content|content:encoded)>", RegexOption.DOTALL or RegexOption.IGNORE_CASE).find(block)
            var desc = descMatch?.groupValues?.get(1)?.replace(Regex("<!\\[CDATA\\[(.*?)\\]\\]>", RegexOption.DOTALL), "$1")?.trim()

            var img: String? = null
            val encMatch = Regex("<(?:enclosure|media:content)[^>]*url=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(block)
            if (encMatch != null) {
                img = encMatch.groupValues[1]
            } else if (desc != null) {
                val imgMatch = Regex("<img[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(desc)
                if (imgMatch != null) img = imgMatch.groupValues[1]
            }

            var timestamp = System.currentTimeMillis()
            val dateMatch = Regex("<(?:pubDate|published|dc:date)[^>]*>(.*?)</(?:pubDate|published|dc:date)>", RegexOption.IGNORE_CASE).find(block)
            if (dateMatch != null) {
                try {
                    val dateStr = dateMatch.groupValues[1].trim()
                    timestamp = java.util.Date(dateStr).time
                } catch (e: Exception) {}
            }

            if (!title.isNullOrEmpty()) {
                var cleanDesc = (desc ?: "").replace(Regex("<[^>]*>"), "").trim()
                if (cleanDesc.length > 300) cleanDesc = cleanDesc.take(300) + "..."
                
                // Очищення заголовка від зайвих тегів
                title = title.replace(Regex("<[^>]*>"), "").trim()

                items.add(NewsItem(
                    title = title,
                    link = link ?: "",
                    description = cleanDesc,
                    source = sourceName,
                    image = img ?: "",
                    timestamp = timestamp
                ))
            }
        }
    } catch (e: Exception) {
        LogManager.log("PARSER_ERR", "Помилка Regex-парсингу $sourceName: ${e.message}")
    }
    return items
}

object NewsParserFactory {
    fun getParser(url: String): BaseRssParser {
        return when {
            url.contains("nasa.gov") -> NasaParser()
            url.contains("space.com") -> SpaceComParser()
            url.contains("spacedaily.com") -> SpaceDailyParser()
            url.contains("universetoday.com") -> UniverseTodayParser()
            url.contains("phys.org") -> PhysOrgParser()
            else -> NasaParser()
        }
    }
}
