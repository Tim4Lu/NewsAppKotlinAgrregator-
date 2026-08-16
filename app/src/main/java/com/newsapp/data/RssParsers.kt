package com.newsapp.data

import com.newsapp.model.NewsItem
import kotlin.text.RegexOption

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
        val itemRegex = Regex("<(?:item|entry)[^>]*>(.*?)</(?:item|entry)>", setOf(RegexOption.DOTALL, RegexOption.IGNORE_CASE))
        val matches = itemRegex.findAll(xml)

        for (match in matches) {
            val block = match.groupValues[1]

            // Title
            val titleMatch = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.DOTALL, RegexOption.IGNORE_CASE)).find(block)
            var rawTitle = titleMatch?.groupValues?.getOrNull(1) ?: ""
            rawTitle = rawTitle.replace(Regex("<!\\[CDATA\\[(.*?)\\]\\]>", setOf(RegexOption.DOTALL, RegexOption.IGNORE_CASE)), "$1").trim()

            // Link
            val linkHref = Regex("<link[^>]*href=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(block)?.groupValues?.getOrNull(1)
            val linkTag = Regex("<link[^>]*>(.*?)</link>", setOf(RegexOption.DOTALL, RegexOption.IGNORE_CASE)).find(block)?.groupValues?.getOrNull(1)
            val rawLink = (linkHref ?: linkTag ?: "").replace(Regex("<!\\[CDATA\\[(.*?)\\]\\]>", setOf(RegexOption.DOTALL, RegexOption.IGNORE_CASE)), "$1").trim()

            // Description
            val descMatch = Regex("<(?:description|summary|content|content:encoded)[^>]*>(.*?)</(?:description|summary|content|content:encoded)>", setOf(RegexOption.DOTALL, RegexOption.IGNORE_CASE)).find(block)
            var rawDesc = descMatch?.groupValues?.getOrNull(1) ?: ""
            rawDesc = rawDesc.replace(Regex("<!\\[CDATA\\[(.*?)\\]\\]>", setOf(RegexOption.DOTALL, RegexOption.IGNORE_CASE)), "$1").trim()

            // Image
            var img: String? = null
            val encMatch = Regex("<(?:enclosure|media:content)[^>]*url=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(block)
            if (encMatch != null) {
                img = encMatch.groupValues.getOrNull(1)
            } else if (rawDesc.isNotEmpty()) {
                val imgMatch = Regex("<img[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(rawDesc)
                if (imgMatch != null) img = imgMatch.groupValues.getOrNull(1)
            }

            // Date
            var timestamp = System.currentTimeMillis()
            val dateMatch = Regex("<(?:pubDate|published|dc:date)[^>]*>(.*?)</(?:pubDate|published|dc:date)>", RegexOption.IGNORE_CASE).find(block)
            if (dateMatch != null) {
                try {
                    val dateStr = dateMatch.groupValues[1].trim()
                    timestamp = java.util.Date(dateStr).time
                } catch (e: Exception) {}
            }

            if (rawTitle.isNotEmpty()) {
                var cleanDesc = rawDesc.replace(Regex("<[^>]*>"), "").trim()
                if (cleanDesc.length > 300) cleanDesc = cleanDesc.take(300) + "..."
                
                val cleanTitle = rawTitle.replace(Regex("<[^>]*>"), "").trim()

                items.add(NewsItem(
                    title = cleanTitle,
                    link = rawLink,
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
