package com.newsapp.data.api

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

    private val PART1 = "8738429281:AAHRxy5sPK4Q"
    private val PART2 = "MRwF3QKu8kDWPnTR0HFukHw"
    private val channelId = "@pronaukyonline"

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

    // Завантаження картинки і конвертація в JPEG (100% якість)
    private suspend fun getJpegBytesFromUrl(url: String): ByteArray? {
        return try {
            LogManager.log("Telegram", "Завантаження картинки для конвертації...")
            val response = client.get(url)
            val imageBytes = response.readBytes()

            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                ?: return null

            val outputStream = ByteArrayOutputStream()
            // Використовуємо якість 100, щоб зберегти максимальну відповідність оригіналу
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
            val jpegBytes = outputStream.toByteArray()
            
            LogManager.log("Telegram", "Картинку успішно конвертовано в JPEG (${jpegBytes.size / 1024} KB)")
            jpegBytes
        } catch (e: Exception) {
            LogManager.log("Telegram_ERR", "Помилка завантаження/конвертації картинки: ${e.message}")
            null
        }
    }

    suspend fun sendToTelegram(caption: String, imageUrl: String? = null): Boolean {
        return try {
            val token = PART1 + PART2
            val safeCaption = sanitizeHtml(caption)
            val hasImage = !imageUrl.isNullOrEmpty() && imageUrl.startsWith("http")

            if (hasImage) {
                // 1. Пробуємо завантажити та конвертувати
                val jpegBytes = getJpegBytesFromUrl(imageUrl!!)
                
                // 2. Якщо проблема з фото - ПЕРЕРИВАЄМО ВІДПРАВКУ (не відправляємо без картинки)
                if (jpegBytes == null) {
                    LogManager.log("Telegram_ERR", "Відправку скасовано: не вдалося обробити картинку.")
                    return false 
                }

                val url = "https://api.telegram.org/bot$token/sendPhoto"
                LogManager.log("Telegram", "Відправка файлу (multipart/form-data)...")

                val response = client.post(url) {
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                append("chat_id", channelId)
                                append("caption", safeCaption)
                                append("parse_mode", "HTML")
                                append("photo", jpegBytes, Headers.build {
                                    append(HttpHeaders.ContentType, "image/jpeg")
                                    append(HttpHeaders.ContentDisposition, "filename=\"image.jpg\"")
                                })
                            }
                        )
                    )
                }

                val responseText = response.bodyAsText()
                val jsonResponse = JSONObject(responseText)

                if (jsonResponse.optBoolean("ok", false)) {
                    LogManager.log("Telegram_OK", "Успішно опубліковано конвертоване фото!")
                    true
                } else {
                    LogManager.log("Telegram_ERR", "Помилка Telegram: ${jsonResponse.optString("description")}")
                    false
                }
            } else {
                // Якщо картинки не було з самого початку (текстова новина)
                val url = "https://api.telegram.org/bot$token/sendMessage"
                LogManager.log("Telegram", "Публікація лише тексту...")
                
                val jsonBody = JSONObject().apply {
                    put("chat_id", channelId)
                    put("text", safeCaption)
                    put("parse_mode", "HTML")
                }

                val response = client.post(url) {
                    contentType(ContentType.Application.Json)
                    setBody(jsonBody.toString())
                }

                val responseText = response.bodyAsText()
                val jsonResponse = JSONObject(responseText)
                
                if (jsonResponse.optBoolean("ok", false)) {
                    LogManager.log("Telegram_OK", "Успішно опубліковано текст!")
                    true
                } else {
                    LogManager.log("Telegram_ERR", "Помилка Telegram: ${jsonResponse.optString("description")}")
                    false
                }
            }
        } catch (e: Exception) {
            LogManager.log("Telegram_ERR", "Мережа: ${e.message ?: e.toString()}")
            false
        }
    }
}
