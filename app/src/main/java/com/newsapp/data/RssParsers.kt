package com.newsapp.data

import com.newsapp.model.NewsItem
import java.text.SimpleDateFormat
import java.util.Locale

interface BaseRssParser {
    fun parse(xml: String): List<NewsItem>
}

class NasaParser : BaseRssParser { override fun parse(xml: String) = parseRobust(xml, "NASA") }
class SpaceComParser : BaseRssParser { override fun parse(xml: String) = parseRobust(xml, "Space.com") }
class SpaceDailyParser : BaseRssParser { override fun parse(xml: String) = parseRobust(xml, "Space Daily") }
class UniverseTodayParser : BaseRssParser { override fun parse(xml: String) = parseRobust(xml, "Universe Today") }
class PhysOrgParser : BaseRssParser { override fun parse(xml: String) = parseRobust(xml, "Phys.org") }

private fun String.cleanHtmlAndEntities(): String {
    val text = this.replace(Regex("(?s)(?i)<!\\[CDATA\\[(.*?)\\]\\]>"), "$1")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&nbsp;", " ")
        .replace(Regex("<[^>]*>"), "\n")

    val artifacts = setOf(
        "science", "apod", "today's apod", "archive", "submissions", 
        "index", "search", "calendar", "rss", "education", "about", "discuss"
    )

    val cleanLines = text.split("\n")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .filter { line -> 
            val lowerLine = line.lowercase()
            !artifacts.contains(lowerLine) && !lowerLine.startsWith("apod: 20")
        }

    return cleanLines.joinToString(" ")
        .replace(Regex("\\s{2,}"), " ")
        .trim()
}

private fun parseRobust(xml: String, sourceName: String): List<NewsItem> {
    val items = mutableListOf<NewsItem>()
    try {
        val itemRegex = Regex("(?s)(?i)<(?:item|entry)[^>]*>(.*?)</(?:item|entry)>")
        val matches = itemRegex.findAll(xml).toList()

        for (match in matches) {
            val block = match.groupValues[1]

            val titleMatch = Regex("(?s)(?i)<title[^>]*>(.*?)</title>").find(block)
            var rawTitle = titleMatch?.groupValues?.getOrNull(1)?.cleanHtmlAndEntities() ?: ""
            rawTitle = rawTitle.replace("(?i)APOD:\\s*(-\\s*)?".toRegex(), "").trim()

            val linkHref = Regex("(?i)<link[^>]*href=[\"']([^\"']+)[\"']").find(block)?.groupValues?.getOrNull(1)
            val linkTag = Regex("(?s)(?i)<link[^>]*>(.*?)</link>").find(block)?.groupValues?.getOrNull(1)
            val guidTag = Regex("(?s)(?i)<guid[^>]*>(.*?)</guid>").find(block)?.groupValues?.getOrNull(1)
            var rawLink = (linkHref ?: linkTag ?: guidTag ?: "").cleanHtmlAndEntities()
            if (rawLink.contains(" ")) rawLink = rawLink.split(" ")[0]

            var rawDesc = Regex("(?s)(?i)<content:encoded[^>]*>(.*?)</content:encoded>").find(block)?.groupValues?.getOrNull(1)?.cleanHtmlAndEntities() ?: ""
            if (rawDesc.isEmpty()) {
                rawDesc = Regex("(?s)(?i)<(?:description|summary|content)[^>]*>(.*?)</(?:description|summary|content)>").find(block)?.groupValues?.getOrNull(1)?.cleanHtmlAndEntities() ?: ""
            }

            var img: String? = null
            val encMatch = Regex("(?i)<(?:enclosure|media:content|media:thumbnail)[^>]*url=[\"']([^\"']+)[\"']").find(block)
            if (encMatch != null) {
                img = encMatch.groupValues.getOrNull(1)
            } else if (rawDesc.isNotEmpty()) {
                val imgMatch = Regex("(?i)<img[^>]+src=[\"']([^\"']+)[\"']").find(block)
                if (imgMatch != null) img = imgMatch.groupValues.getOrNull(1)
            }

            var timestamp = System.currentTimeMillis()
            val dateMatch = Regex("(?i)<(?:pubDate|published|dc:date)[^>]*>(.*?)</(?:pubDate|published|dc:date)>").find(block)
            if (dateMatch != null) {
                val dateStr = dateMatch.groupValues[1].cleanHtmlAndEntities()
                val formats = listOf(
                    "EEE, dd MMM yyyy HH:mm:ss Z",
                    "EEE, dd MMM yyyy HH:mm:ss zzz",
                    "yyyy-MM-dd'T'HH:mm:ss'Z'",
                    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                    "yyyy-MM-dd HH:mm:ss"
                )
                for (format in formats) {
                    try {
                        val sdf = SimpleDateFormat(format, Locale.ENGLISH)
                        val parsed = sdf.parse(dateStr)
                        if (parsed != null) {
                            timestamp = parsed.time
                            break
                        }
                    } catch (e: Exception) {}
                }
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
    } catch (e: Exception) {
        LogManager.log("PARSER_ERR", "Помилка парсингу $sourceName: ${e.message}")
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
