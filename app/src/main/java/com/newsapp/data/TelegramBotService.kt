package com.newsapp.data

import com.newsapp.model.NewsItem
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.json.JSONObject

class TelegramBotService {
    private val client = HttpClient(CIO)

    // Вкажи свій токен та ID каналу, якщо потрібно
    private val BOT_TOKEN = "ТВІЙ_TELEGRAM_BOT_TOKEN"
    private val CHANNEL_ID = "@ТВІЙ_КАНАЛ"

    suspend fun sendNewsToChannel(newsItem: NewsItem): Boolean {
        return try {
            LogManager.log("Telegram", "Надсилання новини в канал: ${newsItem.title.take(20)}...")
            
            val captionText = newsItem.telegramCaption.ifEmpty {
                "🚀 <b>${newsItem.title}</b>\n\n${newsItem.description}\n\n• <b>Джерело:</b> ${newsItem.source}"
            }

            val url: String
            val jsonBody = JSONObject().apply {
                put("chat_id", CHANNEL_ID)
                put("parse_mode", "HTML")
            }

            if (!newsItem.image.isNullOrEmpty() && newsItem.image.startsWith("http")) {
                url = "https://api.telegram.org/bot$BOT_TOKEN/sendPhoto"
                jsonBody.put("photo", newsItem.image)
                jsonBody.put("caption", captionText)
            } else {
                url = "https://api.telegram.org/bot$BOT_TOKEN/sendMessage"
                jsonBody.put("text", captionText)
            }

            val response = client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(jsonBody.toString())
            }

            val responseBody = response.bodyAsText()
            val isSuccess = response.status.value in 200..299 && JSONObject(responseBody).optBoolean("ok", false)

            if (isSuccess) {
                LogManager.log("Telegram_OK", "Успішно опубліковано в Telegram!")
            } else {
                LogManager.log("Telegram_ERR", "Помилка публікації: $responseBody")
            }

            isSuccess
        } catch (e: Exception) {
            LogManager.log("Telegram_ERR", "Збій відправки в Telegram: ${e.message}")
            false
        }
    }
}
