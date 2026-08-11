package com.newsapp.data

import android.util.Log
import com.newsapp.model.NewsItem
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TelegramBotService {

    companion object {
        private const val TAG = "TelegramBotServiceTag"
        private const val BOT_TOKEN = "ТВІЙ_TELEGRAM_BOT_TOKEN"
        private const val CHANNEL_ID = "@твій_канал" // наприклад @my_space_news
    }

    private val client = HttpClient(CIO)

    suspend fun sendNewsToChannel(newsItem: NewsItem): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "TELEGRAM_SEND: Відправка новини в Telegram-канал...")
                val messageText = "🚀 *${newsItem.title}*\n\n${newsItem.description}\n\n🔗 [Читати далі](${newsItem.link})"

                val response: HttpResponse = client.post("https://api.telegram.org/bot$BOT_TOKEN/sendMessage") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "chat_id": "$CHANNEL_ID",
                          "text": "$messageText",
                          "parse_mode": "Markdown"
                        }
                        """.trimIndent()
                    )
                }

                if (response.status == HttpStatusCode.OK) {
                    Log.d(TAG, "TELEGRAM_SUCCESS: Новину успішно опубліковано у Telegram!")
                    true
                } else {
                    Log.e(TAG, "TELEGRAM_ERROR: Помилка відправки Telegram API: ${response.status.value}")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "TELEGRAM_EXCEPTION: Помилка з'єднання з Telegram API", e)
                false
            }
        }
    }
}
