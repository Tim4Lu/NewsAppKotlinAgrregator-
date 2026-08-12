package com.newsapp.data.api

import android.util.Base64
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

    // Зашифрований Telegram Token
    private val T1 = "ODczODQyOTI4MTpBQUVsM3Zs"
    private val T2 = "cm5NR3FuNkY5YjNvclNnR3d1Ry12Ymhka2ZYNA=="
    private val channelId = "@pronaukyonline"

    private fun getBotToken(): String {
        return try {
            String(Base64.decode(T1 + T2, Base64.DEFAULT)).trim()
        } catch (e: Exception) {
            ""
        }
    }

    suspend fun sendToTelegram(caption: String, imageUrl: String? = null): Boolean {
        return try {
            val token = getBotToken()
            if (token.isEmpty()) {
                LogManager.log("Telegram_ERR", "Помилка дешифрування токена")
                return false
            }

            val hasImage = !imageUrl.isNullOrEmpty() && imageUrl.startsWith("http")
            val endpoint = if (hasImage) "sendPhoto" else "sendMessage"
            val url = "https://api.telegram.org/bot$token/$endpoint"
            
            val jsonBody = JSONObject().apply {
                put("chat_id", channelId)
                if (hasImage) {
                    put("photo", imageUrl)
                    put("caption", caption)
                    put("parse_mode", "HTML")
                } else {
                    put("text", caption)
                    put("parse_mode", "HTML")
                }
            }

            LogManager.log("Telegram", "Публікація в канал через $endpoint...")

            val response = client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(jsonBody.toString())
            }

            val responseText = response.bodyAsText()
            LogManager.log("TG_RAW", responseText.replace("\n", "").take(250))

            val jsonResponse = JSONObject(responseText)
            if (jsonResponse.optBoolean("ok", false)) {
                LogManager.log("Telegram_OK", "Успішно опубліковано в Telegram!")
                true
            } else {
                val description = jsonResponse.optString("description", "Unknown error")
                LogManager.log("Telegram_ERR", "Telegram відхилив запит: $description")
                false
            }
        } catch (e: Exception) {
            LogManager.log("Telegram_ERR", "Помилка мережі Telegram: ${e.message ?: e.toString()}")
            false
        }
    }
}
