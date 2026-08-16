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

private fun String.cleanHtmlAndEntities(): String {
    return this.replace(Regex("(?s)(?i)<!\\[CDATA\\[(.*?)\\]\\]>"), "$1")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&nbsp;", " ")
        .replace(Regex("<[^>]*>"), "")
        .trim()
}

private fun parseRobust(xml: String, sourceName: String): List<NewsItem> {
    val items = mutableListOf<NewsItem>()
    try {
        val itemRegex = Regex("(?s)(?i)<(?:item|entry)[^>]*>(.*?)</(?:item|entry)>")
        val matches = itemRegex.findAll(xml).toList()

        if (matches.isEmpty()) {
            LogManager.log("PARSER_WARN", "Джерело $sourceName: 0 елементів у XML (довжина відповіді: ${xml.length})")
        }

        for (match in matches) {
            val block = match.groupValues[1]

            // Title
            val titleMatch = Regex("(?s)(?i)<title[^>]*>(.*?)</title>").find(block)
            val rawTitle = titleMatch?.groupValues?.getOrNull(1)?.cleanHtmlAndEntities() ?: ""

            // Link (Space.com підтримує guid та link)
            val linkHref = Regex("(?i)<link[^>]*href=[\"']([^\"']+)[\"']").find(block)?.groupValues?.getOrNull(1)
            val linkTag = Regex("(?s)(?i)<link[^>]*>(.*?)</link>").find(block)?.groupValues?.getOrNull(1)
            val guidTag = Regex("(?s)(?i)<guid[^>]*>(.*?)</guid>").find(block)?.groupValues?.getOrNull(1)
            var rawLink = (linkHref ?: linkTag ?: guidTag ?: "").cleanHtmlAndEntities()
            if (rawLink.contains(" ")) rawLink = rawLink.split(" ")[0]

            // Description
            val descMatch = Regex("(?s)(?i)<(?:description|summary|content|content:encoded)[^>]*>(.*?)</(?:description|summary|content|content:encoded)>").find(block)
            val rawDesc = descMatch?.groupValues?.getOrNull(1)?.cleanHtmlAndEntities() ?: ""

            // Image
            var img: String? = null
            val encMatch = Regex("(?i)<(?:enclosure|media:content|media:thumbnail)[^>]*url=[\"']([^\"']+)[\"']").find(block)
            if (encMatch != null) {
                img = encMatch.groupValues.getOrNull(1)
            } else if (rawDesc.isNotEmpty()) {
                val imgMatch = Regex("(?i)<img[^>]+src=[\"']([^\"']+)[\"']").find(block)
                if (imgMatch != null) img = imgMatch.groupValues.getOrNull(1)
            }

            // Date
            var timestamp = System.currentTimeMillis()
            val dateMatch = Regex("(?i)<(?:pubDate|published|dc:date)[^>]*>(.*?)</(?:pubDate|published|dc:date)>").find(block)
            if (dateMatch != null) {
                try {
                    val dateStr = dateMatch.groupValues[1].cleanHtmlAndEntities()
                    timestamp = java.util.Date(dateStr).time
                } catch (e: Exception) {}
            }

            if (rawTitle.isNotEmpty()) {
                var cleanDesc = rawDesc
                if (cleanDesc.length > 300) cleanDesc = cleanDesc.take(300) + "..."

                items.add(NewsItem(
                    title = rawTitle,
                    originalTitle = rawTitle,
                    link = rawLink,
                    description = cleanDesc,
                    source = sourceName,
                    image = img ?: "",
                    timestamp = timestamp
                ))
            }
        }
        if (items.isNotEmpty()) {
            LogManager.log("PARSER_OK", "Джерело $sourceName: розпарсено ${items.size} новин")
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
