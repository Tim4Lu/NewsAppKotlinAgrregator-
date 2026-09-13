package com.newsapp.data.api

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.newsapp.BuildConfig
import com.newsapp.data.LogManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class TelegramBotService {
    private val client = HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 60000
            connectTimeoutMillis = 60000
            socketTimeoutMillis = 60000
        }
    }
    private val channelId = "@pronaukyonline"

    private fun sanitizeHtml(text: String): String = text.replace(Regex("&(?!(amp|lt|gt|quot|apos);)"), "&amp;")

    private suspend fun getJpegBytesFromUrl(url: String): ByteArray? {
        return try {
            val response = client.get(url)
            val imageBytes = response.readBytes()
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return null
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
            outputStream.toByteArray()
        } catch (e: Exception) { null }
    }

    suspend fun sendToTelegram(caption: String, imageUrls: List<String> = emptyList()): Boolean {
        return try {
            val token = BuildConfig.TELEGRAM_BOT_TOKEN
            val safeCaption = sanitizeHtml(caption)
            val validUrls = imageUrls.filter { it.startsWith("http") }.take(10)

            if (validUrls.size > 1) {
                LogManager.log("Telegram", "Відправка галереї з ${validUrls.size} фото...")
                val bytesList = validUrls.mapNotNull { getJpegBytesFromUrl(it) }
                if (bytesList.isEmpty()) return false

                val response = client.post("https://api.telegram.org/bot$token/sendMediaGroup") {
                    setBody(MultiPartFormDataContent(formData {
                        append("chat_id", channelId)
                        val mediaArray = JSONArray()
                        bytesList.forEachIndexed { index, _ ->
                            val mediaObj = JSONObject().apply {
                                put("type", "photo")
                                put("media", "attach://photo$index")
                                if (index == 0) { put("caption", safeCaption); put("parse_mode", "HTML") }
                            }
                            mediaArray.put(mediaObj)
                        }
                        append("media", mediaArray.toString())
                        bytesList.forEachIndexed { index, bytes ->
                            append("photo$index", bytes, Headers.build {
                                append(HttpHeaders.ContentType, "image/jpeg")
                                append(HttpHeaders.ContentDisposition, "filename=\"photo$index.jpg\"")
                            })
                        }
                    }))
                }
                JSONObject(response.bodyAsText()).optBoolean("ok", false)
            } else if (validUrls.size == 1) {
                LogManager.log("Telegram", "Відправка одного фото...")
                val jpegBytes = getJpegBytesFromUrl(validUrls.first()) ?: return false
                val response = client.post("https://api.telegram.org/bot$token/sendPhoto") {
                    setBody(MultiPartFormDataContent(formData {
                        append("chat_id", channelId)
                        append("caption", safeCaption)
                        append("parse_mode", "HTML")
                        append("photo", jpegBytes, Headers.build {
                            append(HttpHeaders.ContentType, "image/jpeg")
                            append(HttpHeaders.ContentDisposition, "filename=\"image.jpg\"")
                        })
                    }))
                }
                JSONObject(response.bodyAsText()).optBoolean("ok", false)
            } else {
                val response = client.post("https://api.telegram.org/bot$token/sendMessage") {
                    contentType(ContentType.Application.Json)
                    setBody(JSONObject().apply { put("chat_id", channelId); put("text", safeCaption); put("parse_mode", "HTML") }.toString())
                }
                JSONObject(response.bodyAsText()).optBoolean("ok", false)
            }
        } catch (e: Exception) {
            LogManager.log("Telegram_ERR", "Мережа: ${e.message}")
            false
        }
    }
}
