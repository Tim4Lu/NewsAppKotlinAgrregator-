package com.newsapp.data

import com.newsapp.model.NewsItem
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

interface BaseRssParser {
    fun parse(xml: String): List<NewsItem>
}

class NasaParser : BaseRssParser {
    override fun parse(xml: String): List<NewsItem> = parseGeneric(xml, "NASA")
}

class SpaceComParser : BaseRssParser {
    override fun parse(xml: String): List<NewsItem> = parseGeneric(xml, "Space.com")
}

class SpaceDailyParser : BaseRssParser {
    override fun parse(xml: String): List<NewsItem> = parseGeneric(xml, "Space Daily")
}

class UniverseTodayParser : BaseRssParser {
    override fun parse(xml: String): List<NewsItem> = parseGeneric(xml, "Universe Today")
}

class PhysOrgParser : BaseRssParser {
    override fun parse(xml: String): List<NewsItem> = parseGeneric(xml, "Phys.org")
}

private fun parseGeneric(xml: String, sourceName: String): List<NewsItem> {
    val items = mutableListOf<NewsItem>()
    try {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        var eventType = parser.eventType
        var insideItem = false
        var currentTag = ""

        var title: String? = null
        var link: String? = null
        var desc: String? = null
        var img: String? = null

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name?.lowercase() ?: ""
                    if (currentTag == "item" || currentTag == "entry") {
                        insideItem = true
                        title = null
                        link = null
                        desc = null
                        img = null
                    } else if (insideItem) {
                        if (currentTag == "link") {
                            val href = parser.getAttributeValue(null, "href")
                            if (!href.isNullOrEmpty()) link = href
                        } else if (currentTag == "enclosure" || currentTag == "media:content") {
                            val mediaUrl = parser.getAttributeValue(null, "url")
                            if (!mediaUrl.isNullOrEmpty()) img = mediaUrl
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (insideItem && parser.text?.isNotBlank() == true) {
                        val text = parser.text.trim()
                        when (currentTag) {
                            "title" -> title = (title ?: "") + text
                            "link" -> if (link.isNullOrEmpty()) link = text
                            "description", "summary", "content", "content:encoded" -> desc = (desc ?: "") + text
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val name = parser.name?.lowercase() ?: ""
                    if (name == "item" || name == "entry") {
                        if (!title.isNullOrEmpty()) {
                            var cleanDesc = (desc ?: "").replace(Regex("<[^>]*>"), "").trim()
                            if (cleanDesc.length > 300) cleanDesc = cleanDesc.take(300) + "..."

                            if (img.isNullOrEmpty() && desc != null) {
                                val imgMatch = Regex("<img[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(desc!!)
                                if (imgMatch != null) img = imgMatch.groupValues[1]
                            }

                            items.add(
                                NewsItem(
                                    title = title!!.trim(),
                                    link = link?.trim() ?: "",
                                    description = cleanDesc,
                                    source = sourceName,
                                    image = img ?: ""
                                )
                            )
                        }
                        insideItem = false
                    }
                    currentTag = ""
                }
            }
            eventType = parser.next()
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
