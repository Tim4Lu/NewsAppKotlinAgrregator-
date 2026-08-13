package com.newsapp.data.api

import com.newsapp.data.LogManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.json.JSONObject

class TelegramBotService {
    private val client = HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 60000
            connectTimeoutMillis = 60000
            socketTimeoutMillis = 60000
        }
    }

    private val PART1 = "8738429281:AAHRxy5sPK4Q"
    private val PART2 = "MRwF3QKu8kDWPnTR0HFukHw"
    private val channelId = "@science_everyday"

    private fun sanitizeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("&lt;b&gt;", "<b>")
            .replace("&lt;/b&gt;", "</b>")
            .replace("&lt;i&gt;", "<i>")
            .replace("&lt;/i&gt;", "</i>")
            .replace("&lt;a href=", "<a href=")
            .replace("&lt;/a&gt;", "</a>")
    }

    suspend fun sendToTelegram(caption: String, imageUrl: String? = null): Boolean {
        return try {
            val token = PART1 + PART2
            val safeCaption = sanitizeHtml(caption)
            
            val hasImage = !imageUrl.isNullOrEmpty() && imageUrl.startsWith("http")
            val endpoint = if (hasImage) "sendPhoto" else "sendMessage"
            val url = "https://api.telegram.org/bot$token/$endpoint"
            
            val jsonBody = JSONObject().apply {
                put("chat_id", channelId)
                if (hasImage) {
                    put("photo", imageUrl)
                    put("caption", safeCaption)
                    put("parse_mode", "HTML")
                } else {
                    put("text", safeCaption)
                    put("parse_mode", "HTML")
                }
            }

            LogManager.log("Telegram", "Публікація в канал через $endpoint...")

            val response = client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(jsonBody.toString())
            }

            val responseText = response.bodyAsText()
            val jsonResponse = JSONObject(responseText)
            
            if (jsonResponse.optBoolean("ok", false)) {
                LogManager.log("Telegram_OK", "Успішно опубліковано в Telegram!")
                true
            } else {
                val description = jsonResponse.optString("description", "Unknown error")
                LogManager.log("Telegram_ERR", "Помилка Telegram: $description")
                false
            }
        } catch (e: Exception) {
            LogManager.log("Telegram_ERR", "Мережа: ${e.message ?: e.toString()}")
            false
        }
    }
}
