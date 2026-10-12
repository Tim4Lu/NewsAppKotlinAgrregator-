package com.newsapp.data

import com.newsapp.model.NewsItem
import java.text.SimpleDateFormat
import java.util.Locale

interface BaseRssParser {
    fun parse(xml: String): List<NewsItem>
}

class EsaParser : BaseRssParser { override fun parse(xml: String) = parseRobust(xml, "ESA") }
class NasaParser : BaseRssParser { override fun parse(xml: String) = parseRobust(xml, "NASA") }
class SpaceNewsParser : BaseRssParser { override fun parse(xml: String) = parseRobust(xml, "SpaceNews") }
class SpaceComParser : BaseRssParser { override fun parse(xml: String) = parseRobust(xml, "Space.com") }
class SpaceDailyParser : BaseRssParser { override fun parse(xml: String) = parseRobust(xml, "Space Daily") }
class UniverseTodayParser : BaseRssParser { override fun parse(xml: String) = parseRobust(xml, "Universe Today") }
class PhysOrgParser : BaseRssParser { override fun parse(xml: String) = parseRobust(xml, "Phys.org") }
class NatureParser : BaseRssParser { override fun parse(xml: String) = parseRobust(xml, "Nature") }

private fun String.cleanHtmlAndEntities(): String {
    val text = this.replace(Regex("(?s)(?i)<!\\[CDATA\\[(.*?)\\]\\]>"), "$1")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace(Regex("<[^>]*>"), "\n")

    val artifacts = setOf(
        "apod", "today's apod", "archive", "submissions", 
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
        LogManager.log("PARSER", "Парсинг $sourceName. Отримано текст: ${xml.length} символів")
        
        val itemRegex = Regex("(?s)(?i)<(?:item|entry)[^>]*>(.*?)</(?:item|entry)>")
        val matches = itemRegex.findAll(xml)
        
        LogManager.log("PARSER", "[$sourceName] Початок обробки блоків <item>")

        for (match in matches) {
            val block = match.groupValues[1]

            val titleMatch = Regex("(?s)(?i)<(?:title|dc:title)[^>]*>(.*?)</(?:title|dc:title)>").find(block)
            var rawTitle = titleMatch?.groupValues?.getOrNull(1)?.cleanHtmlAndEntities() ?: ""
            rawTitle = rawTitle.replace("(?i)APOD:\\s*(-\\s*)?".toRegex(), "").trim()

            val linkHref = Regex("(?i)<link[^>]*href=[\"']([^\"']+)[\"']").find(block)?.groupValues?.getOrNull(1)
            val linkTag = Regex("(?s)(?i)<link[^>]*>(.*?)</link>").find(block)?.groupValues?.getOrNull(1)
            val guidTag = Regex("(?s)(?i)<guid[^>]*>(.*?)</guid>").find(block)?.groupValues?.getOrNull(1)
            
            var rawLink = (linkHref ?: linkTag ?: guidTag ?: "")
                .replace("(?s)(?i)<!\\[CDATA\\[(.*?)\\]\\]>".toRegex(), "$1")
                .replace("&amp;", "&")
                .replace(Regex("<[^>]*>"), "")
                .trim()
            if (rawLink.contains(" ")) rawLink = rawLink.split(" ")[0]

            var rawDesc = Regex("(?s)(?i)<(?:content:encoded|content)[^>]*>(.*?)</(?:content:encoded|content)>").find(block)?.groupValues?.getOrNull(1)?.cleanHtmlAndEntities() ?: ""
            if (rawDesc.isEmpty()) {
                rawDesc = Regex("(?s)(?i)<(?:description|summary|dc:description)[^>]*>(.*?)</(?:description|summary|dc:description)>").find(block)?.groupValues?.getOrNull(1)?.cleanHtmlAndEntities() ?: ""
            }

            var img: String? = null
            val encMatch = Regex("(?i)<(?:enclosure|media:content|media:thumbnail)[^>]*url=[\"']([^\"']+)[\"']").find(block)
            if (encMatch != null) {
                img = encMatch.groupValues.getOrNull(1)
            } else if (rawDesc.isNotEmpty()) {
                val imgMatch = Regex("(?i)<img[^>]+src=[\"']([^\"']+)[\"']").find(block)
                if (imgMatch != null) img = imgMatch.groupValues.getOrNull(1)
            }
            
            if (img != null) {
                img = img.replace("/tmb/", "/").replace(Regex("-\\d{2,4}x\\d{2,4}(?=\\.[a-zA-Z]+)"), "")
            }

            var timestamp = System.currentTimeMillis()
            val dateMatch = Regex("(?i)<(?:pubDate|published|dc:date)[^>]*>(.*?)</(?:pubDate|published|dc:date)>").find(block)
            if (dateMatch != null) {
                val dateStr = dateMatch.groupValues[1].replace(Regex("<[^>]*>"), "").trim()
                val formats = listOf("EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, dd MMM yyyy", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd HH:mm:ss")
                for (format in formats) {
                    try {
                        val sdf = SimpleDateFormat(format, Locale.ENGLISH)
                        val parsed = sdf.parse(dateStr)
                        if (parsed != null) { timestamp = parsed.time; break }
                    } catch (e: Exception) {}
                }
            }

            if (rawTitle.isNotEmpty() && rawLink.isNotEmpty()) {
                var cleanDesc = rawDesc
                if (cleanDesc.length > 300) cleanDesc = cleanDesc.take(300) + "..."
                items.add(NewsItem(title = rawTitle, originalTitle = rawTitle, link = rawLink, description = cleanDesc, source = sourceName, image = img ?: "", timestamp = timestamp))
            }
        }
    } catch (e: Throwable) { LogManager.log("PARSER_ERR", "Крах парсера $sourceName: ${e.message}") }
    return items
}


import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import org.json.JSONObject
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Locale

object SnapiParser {
    private val client = HttpClient(CIO) { expectSuccess = false }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ENGLISH).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }

    suspend fun fetch(newsSite: String, limit: Int = 15): List<NewsItem> {
        return try {
            val url = "https://api.spaceflightnewsapi.net/v4/articles/?news_site=${java.net.URLEncoder.encode(newsSite, "UTF-8")}&limit=$limit"
            LogManager.log("SNAPI", "Запит: $newsSite")
            val response = client.get(url)
            if (response.status.value !in 200..299) {
                LogManager.log("SNAPI_ERR", "HTTP ${response.status.value} для $newsSite")
                return emptyList()
            }
            val json = JSONObject(response.bodyAsText())
            val results = json.optJSONArray("results") ?: return emptyList()
            val items = mutableListOf<NewsItem>()
            for (i in 0 until results.length()) {
                val obj = results.getJSONObject(i)
                val title = obj.optString("title", "")
                val link = obj.optString("url", "")
                val summary = obj.optString("summary", "").replace(Regex("<[^>]*>"), "").trim()
                val image = obj.optString("image_url", "")
                val publishedAt = obj.optString("published_at", "")
                var timestamp = System.currentTimeMillis()
                try { dateFormat.parse(publishedAt)?.let { timestamp = it.time } } catch (_: Exception) {}
                if (title.isNotEmpty() && link.isNotEmpty()) {
                    items.add(NewsItem(
                        title = title,
                        originalTitle = title,
                        link = link,
                        description = summary,
                        source = newsSite,
                        image = image,
                        timestamp = timestamp
                    ))
                }
            }
            LogManager.log("SNAPI_OK", "$newsSite: ${items.size} новин")
            items
        } catch (e: Exception) {
            LogManager.log("SNAPI_ERR", "$newsSite: ${e.message}")
            emptyList()
        }
    }
}

object NewsParserFactory {
    fun getParser(url: String): BaseRssParser {
        return when {
            url.contains("nasa.gov") -> NasaParser()
            url.contains("esa.int") -> EsaParser()
            url.contains("spacenews") -> SpaceNewsParser()
            url.contains("space.com") -> SpaceComParser()
            url.contains("spacedaily") -> SpaceDailyParser()
            url.contains("universetoday") -> UniverseTodayParser()
            url.contains("phys.org") -> PhysOrgParser()
            url.contains("nature.com") -> NatureParser()
            else -> NasaParser()
        }
    }
}
