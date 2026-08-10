package com.newsapp.data.api

import android.util.Log
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import java.net.URLEncoder

object TelegramBotService {
    private const val TAG = "TelegramBotService"
    
    // Ключі збережено з оригінального NewsAgent.js
    private const val BOT_TOKEN = "8738429281:AAGDcDMGTd8aQ9o4GRzVrQzYNls5O0XnoZ4"
    private const val CHAT_ID = "@pronaukyonline"

    private val client = HttpClient(CIO)

    suspend fun sendNews(text: String, imageUrl: String? = null): Boolean {
        Log.d(TAG, "[LOG] Початок відправки новини в Telegram канал $CHAT_ID...")
        
        return try {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            
            val url = if (!imageUrl.isNull_orEmpty()) {
                val encodedImage = URLEncoder.encode(imageUrl, "UTF-8")
                "https://api.telegram.org/bot$BOT_TOKEN/sendPhoto?chat_id=$CHAT_ID&photo=$encodedImage&caption=$encodedText&parse_mode=HTML"
            } else {
                "https://api.telegram.org/bot$BOT_TOKEN/sendMessage?chat_id=$CHAT_ID&text=$encodedText&parse_mode=HTML"
            }

            Log.d(TAG, "[LOG] Виконання HTTP GET запиту до Telegram API...")
            val response: HttpResponse = client.get(url)
            
            Log.d(TAG, "[LOG] Статус відповіді Telegram API: ${response.status.value}")
            
            if (response.status.value == 200) {
                Log.d(TAG, "[LOG] Новина успішно опублікована в Telegram!")
                true
            } else {
                Log.e(TAG, "[LOG] Помилка публікації в Telegram. Текст відповіді: ${response.bodyAsText()}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "[LOG] Виняткова ситуація при відправці в Telegram: ${e.message}", e)
            false
        }
    }
}
