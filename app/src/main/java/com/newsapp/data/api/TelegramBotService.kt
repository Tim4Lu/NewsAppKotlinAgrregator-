package com.newsapp.data.api

import com.newsapp.data.LogManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.json.JSONObject

class TelegramBotService {
    private val client = HttpClient(CIO) {
        expectSuccess = false
    }

    // Твої дані бота та каналу (перевір їх за потреби)
    private val botToken = "ТВОЇ_ТОКЕН_БОТА" 
    private val channelId = "@pronaukyonline" // Або числовий ID типу "-100XXXXXXXXXX", якщо юзернейм не спрацює

    suspend fun sendToTelegram(caption: String): Boolean {
        return try {
            val url = "https://api.telegram.org/bot$botToken/sendMessage"
            
            val jsonBody = JSONObject().apply {
                put("chat_id", channelId)
                put("text", caption)
                put("parse_mode", "HTML")
            }

            LogManager.log("Telegram", "Публікація посту в Telegram...")

            val response = client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(jsonBody.toString())
            }

            val responseText = response.bodyAsText()
            LogManager.log("TG_RAW", responseText)

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
            LogManager.log("Telegram_ERR", "Виняток при відправці: ${e.message}")
            false
        }
    }
}
