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
        // Збільшуємо тайм-аути, щоб відправка фото не падала по таймауту
        install(HttpTimeout) {
            requestTimeoutMillis = 30000
            connectTimeoutMillis = 30000
            socketTimeoutMillis = 30000
        }
    }

    private val botToken = "8738429281:AAGdCDMGTd8aQ9o4GRzVrQzYnls500XnoZ4" 
    private val channelId = "@pronaukyonline" // За потреби можна змінити на числовий ID типу "-100..."

    suspend fun sendToTelegram(caption: String, imageUrl: String? = null): Boolean {
        return try {
            // Якщо є картинка, надсилаємо через sendPhoto, інакше звичайний sendMessage
            val endpoint = if (!imageUrl.isNullOrEmpty()) "sendPhoto" else "sendMessage"
            val url = "https://api.telegram.org/bot$botToken/$endpoint"
            
            val jsonBody = JSONObject().apply {
                put("chat_id", channelId)
                if (!imageUrl.isNullOrEmpty()) {
                    put("photo", imageUrl)
                    put("caption", caption)
                    put("parse_mode", "HTML")
                } else {
                    put("text", caption)
                    put("parse_mode", "HTML")
                }
            }

            LogManager.log("Telegram", "Публікація посту в Telegram ($endpoint)...")

            val response = client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(jsonBody.toString())
            }

            val responseText = response.bodyAsText()
            LogManager.log("TG_RAW", responseText.replace("\n", "").take(250))

            val jsonResponse = JSONObject(responseText)
            if (jsonResponse.optBoolean("ok", false)) {
                LogManager.log("Telegram_OK", "Пост успішно опубліковано в канал!")
                true
            } else {
                val description = jsonResponse.optString("description", "Unknown error")
                LogManager.log("Telegram_ERR", "Помилка Telegram: $description")
                false
            }
        } catch (e: Exception) {
            LogManager.log("Telegram_ERR", "Збій мережі Telegram: ${e.message ?: e.toString()}")
            false
        }
    }
}
