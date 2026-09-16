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
            val validUrls = imageUrls.filter { it.startsWith("http") }.take(10)

            if (validUrls.size > 1) {
                LogManager.log("TG", "Стискаємо ${validUrls.size} фотографій...")
                val bytesList = validUrls.mapNotNull { getJpegBytesFromUrl(it) }
                if (bytesList.isEmpty()) {
                    LogManager.log("TG_ERR", "Жодне фото не вдалося стиснути")
                    return false
                }

                LogManager.log("TG", "Відправка галереї в Telegram...")
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
                val isOk = JSONObject(response.bodyAsText()).optBoolean("ok", false)
                if (isOk) LogManager.log("TG_OK", "Галерею опубліковано!") else LogManager.log("TG_ERR", "Помилка галереї: ${response.bodyAsText()}")
                return isOk
            } else if (validUrls.size == 1) {
                LogManager.log("TG", "Обробка 1 фотографії...")
                val jpegBytes = getJpegBytesFromUrl(validUrls.first())
                if (jpegBytes == null) {
                    LogManager.log("TG_ERR", "Не вдалося стиснути фото")
                    return false
                }
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
                val isOk = JSONObject(response.bodyAsText()).optBoolean("ok", false)
                if (isOk) LogManager.log("TG_OK", "Фото опубліковано!") else LogManager.log("TG_ERR", "Помилка фото: ${response.bodyAsText()}")
                return isOk
            } else {
                val response = client.post("https://api.telegram.org/bot$token/sendMessage") {
                    contentType(ContentType.Application.Json)
                    setBody(JSONObject().apply { put("chat_id", channelId); put("text", safeCaption); put("parse_mode", "HTML") }.toString())
                }
                val isOk = JSONObject(response.bodyAsText()).optBoolean("ok", false)
                if (isOk) LogManager.log("TG_OK", "Текст опубліковано!") else LogManager.log("TG_ERR", "Помилка тексту: ${response.bodyAsText()}")
                return isOk
            }
        } catch (e: Throwable) {
            LogManager.log("TG_CRASH", "Критичний збій (Пам'ять/Мережа): ${e.message}")
            false
        }
    }
}
