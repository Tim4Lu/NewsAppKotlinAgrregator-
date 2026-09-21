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
            connectTimeoutMillis = 30000
            socketTimeoutMillis = 60000
        }
    }
    private val channelId = "@pronaukyonline"

    private fun sanitizeHtml(text: String): String = text.replace(Regex("&(?!(amp|lt|gt|quot|apos);)"), "&amp;")

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private suspend fun getJpegBytesFromUrl(url: String): ByteArray? {
        return try {
            val response = client.get(url)
            val imageBytes = response.readBytes()
            
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
            options.inSampleSize = calculateInSampleSize(options, 1280, 1280)
            options.inJustDecodeBounds = false
            
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options) ?: return null

            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
            val finalBytes = outputStream.toByteArray()
            bitmap.recycle()
            finalBytes
        } catch (e: Throwable) { 
            LogManager.log("TG_IMG_ERR", "Не вдалося обробити фото: ${e.message}")
            null 
        }
    }

    suspend fun sendToTelegram(caption: String, imageUrls: List<String> = emptyList()): Boolean {
        return try {
            val token = BuildConfig.TELEGRAM_BOT_TOKEN
            if (token.isEmpty() || token.contains("null")) {
                LogManager.log("TG_ERR", "ТОКЕН ВІДСУТНІЙ! Перевір local.properties")
                return false
            }
            
            val safeCaption = sanitizeHtml(caption)
            // Telegram ліміт на підпис до медіа - 1024 символи. Беремо з запасом 1000.
            val isCaptionTooLong = safeCaption.length > 1000
            val captionForMedia = if (isCaptionTooLong) "" else safeCaption

            val validUrls = imageUrls.filter { it.startsWith("http") }.take(10)
            var mediaSentOk = false

            if (validUrls.size > 1) {
                val bytesList = validUrls.mapNotNull { getJpegBytesFromUrl(it) }
                if (bytesList.isNotEmpty()) {
                    val response = client.post("https://api.telegram.org/bot$token/sendMediaGroup") {
                        setBody(MultiPartFormDataContent(formData {
                            append("chat_id", channelId)
                            val mediaArray = JSONArray()
                            bytesList.forEachIndexed { index, _ ->
                                val mediaObj = JSONObject().apply {
                                    put("type", "photo")
                                    put("media", "attach://photo$index")
                                    if (index == 0 && captionForMedia.isNotEmpty()) { 
                                        put("caption", captionForMedia)
                                        put("parse_mode", "HTML"); put("disable_web_page_preview", true) }
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
                    mediaSentOk = JSONObject(response.bodyAsText()).optBoolean("ok", false)
                    if (!mediaSentOk) LogManager.log("TG_ERR", "Помилка галереї: ${response.bodyAsText()}")
                }
            } else if (validUrls.size == 1) {
                val jpegBytes = getJpegBytesFromUrl(validUrls.first())
                if (jpegBytes != null) {
                    val response = client.post("https://api.telegram.org/bot$token/sendPhoto") {
                        setBody(MultiPartFormDataContent(formData {
                            append("chat_id", channelId)
                            if (captionForMedia.isNotEmpty()) {
                                append("caption", captionForMedia)
                                append("parse_mode", "HTML")
                            }
                            append("photo", jpegBytes, Headers.build {
                                append(HttpHeaders.ContentType, "image/jpeg")
                                append(HttpHeaders.ContentDisposition, "filename=\"image.jpg\"")
                            })
                        }))
                    }
                    mediaSentOk = JSONObject(response.bodyAsText()).optBoolean("ok", false)
                    if (!mediaSentOk) LogManager.log("TG_ERR", "Помилка фото: ${response.bodyAsText()}")
                }
            }

            // Якщо фото відправлено, але текст був занадто довгий, або якщо фото взагалі не було
            if ((mediaSentOk && isCaptionTooLong) || validUrls.isEmpty()) {
                val response = client.post("https://api.telegram.org/bot$token/sendMessage") {
                    contentType(ContentType.Application.Json)
                    setBody(JSONObject().apply { put("chat_id", channelId); put("text", safeCaption); put("parse_mode", "HTML"); put("disable_web_page_preview", true) }.toString())
                }
                val textSentOk = JSONObject(response.bodyAsText()).optBoolean("ok", false)
                if (textSentOk) LogManager.log("TG_OK", "Текст опубліковано!") else LogManager.log("TG_ERR", "Помилка тексту: ${response.bodyAsText()}")
                return textSentOk
            }

            if (mediaSentOk && !isCaptionTooLong) LogManager.log("TG_OK", "Медіа з підписом опубліковано!")
            return mediaSentOk

        } catch (e: Throwable) {
            LogManager.log("TG_CRASH", "Збій мережі TG: ${e.message}")
            false
        }
    }
}
