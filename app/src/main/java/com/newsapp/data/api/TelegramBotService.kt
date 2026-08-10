package com.newsapp.data.api

import android.util.Log
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*

object TelegramBotService {
    private const val TAG = "TelegramBotService"
    private val client = HttpClient(CIO)

    suspend fun sendMessage(botToken: String, chatId: String, messageText: String): Boolean {
        if (botToken.isNullOrEmpty() || chatId.isNullOrEmpty()) {
            Log.e(TAG, "[LOG] Помилка: Bot Token або Chat ID порожні")
            return false
        }

        return try {
            Log.d(TAG, "[LOG] Відправка повідомлення в Telegram...")
            val url = "https://api.telegram.org/bot$botToken/sendMessage"

            val jsonBody = """
                {
                    "chat_id": "$chatId",
                    "text": ${escapeJson(messageText)},
                    "parse_mode": "Markdown"
                }
            """.trimIndent()

            val response: HttpResponse = client.post(url) {
                header(HttpHeaders.ContentType, "application/json")
                setBody(jsonBody)
            }

            if (response.status.value == 200) {
                Log.d(TAG, "[LOG] Повідомлення успішно відправлено в Telegram!")
                true
            } else {
                Log.e(TAG, "[LOG] Помилка Telegram API: ${response.status.value}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "[LOG] Виняток при відправці в Telegram: ${e.message}")
            false
        }
    }

    private fun escapeJson(text: String): String {
        return "\"" + text.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "") + "\""
    }
}
