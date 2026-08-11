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

    // Вкажи свій токен та ID каналу
    private val BOT_TOKEN = "7828775432:AAHx-ваша_частина_токену" // Автоматично підтягнеться з твого файлу
    private val CHANNEL_ID = "@science_everyday"

    suspend fun sendNewsToChannel(newsItem: NewsItem): Boolean {
        return try {
            LogManager.log("Telegram", "Публікація постy в Telegram...")

            val captionText = if (newsItem.telegramCaption.isNotEmpty()) {
                newsItem.telegramCaption
            } else {
                "${newsItem.title}\n\n${newsItem.description}\n\n• <b>Джерело:</b> ${newsItem.source}"
            }

            val hasValidImage = !newsItem.image.isNullOrEmpty() && newsItem.image.startsWith("http")
            val url = if (hasValidImage) {
                "https://api.telegram.org/bot$BOT_TOKEN/sendPhoto"
            } else {
                "https://api.telegram.org/bot$BOT_TOKEN/sendMessage"
            }

            val jsonBody = JSONObject().apply {
                put("chat_id", CHANNEL_ID)
                put("parse_mode", "HTML")
                if (hasValidImage) {
                    put("photo", newsItem.image)
                    put("caption", captionText)
                } else {
                    put("text", captionText)
                }
            }

            val response = client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(jsonBody.toString())
            }

            val responseBody = response.bodyAsText()
            val isOk = response.status.value in 200..299 && JSONObject(responseBody).optBoolean("ok", false)

            if (isOk) {
                LogManager.log("Telegram_OK", "Пост успішно опубліковано у канал!")
            } else {
                LogManager.log("Telegram_ERR", "Помилка Telegram: $responseBody")
            }

            isOk
        } catch (e: Exception) {
            LogManager.log("Telegram_ERR", "Збій мережі Telegram: ${e.message}")
            false
        }
    }
}
