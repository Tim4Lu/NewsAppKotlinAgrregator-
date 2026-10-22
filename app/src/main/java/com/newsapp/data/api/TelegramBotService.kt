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

    /**
     * NASA/ESA CDN підтримують ?w=&h=&fit= - ріже 5-20 MB до ~200 KB.
     * v465: прибирає timeout при getJpegBytesFromUrl для великих PNG.
     */
    /**
     * Отримує og:image з HTML статті. Fallback, якщо оригінал битий/великий.
     */
    private suspend fun fetchOgImage(articleUrl: String): String? {
        if (articleUrl.isBlank()) return null
        return try {
            val resp = client.get(articleUrl) {
                io.ktor.client.request.header("User-Agent", "Mozilla/5.0 NewsBot/1.0")
            }
            if (resp.status.value !in 200..299) return null
            val html = resp.bodyAsText()
            val re1 = Regex("""<meta[^>]+(?:property|name)=["'](?:og:image|twitter:image)["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            val re2 = Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["'](?:og:image|twitter:image)["']""", RegexOption.IGNORE_CASE)
            val m = re1.find(html) ?: re2.find(html) ?: return null
            var img = m.groupValues[1]
            if (!img.startsWith("http")) {
                val base = java.net.URL(articleUrl)
                img = base.protocol + "://" + base.host + (if (img.startsWith("/")) img else "/" + img)
            }
            img
        } catch (e: Throwable) {
            LogManager.log("TG_OG_ERR", "og:image fail: ${e.message}")
            null
        }
    }

    private fun resizeImageUrl(url: String): String {
        if (url.isBlank()) return url
        val hosts = listOf("nasa.gov", "esa.int", "spaceinimages")
        if (hosts.none { url.contains(it, ignoreCase = true) }) return url
        if (url.contains("?w=") || url.contains("&w=")) return url
        val sep = if (url.contains("?")) "&" else "?"
        return url + sep + "w=1200&h=630&fit=crop"
    }

    private fun sanitizeHtml(text: String): String {
        // 1. Ескейпимо все
        var s = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        // 2. Відновлюємо дозволені Telegram-теги
        val allowedTags = listOf("b", "i", "u", "s", "code", "pre")
        for (tag in allowedTags) {
            s = s.replace("&lt;$tag&gt;", "<$tag>")
            s = s.replace("&lt;/$tag&gt;", "</$tag>")
        }
        // 3. Відновлюємо <a href="...">
        s = s.replace(Regex("&lt;a href=\"(.*?)\"&gt;"), "<a href=\"$1\">")
        s = s.replace("&lt;/a&gt;", "</a>")
        // 4. Прибираємо подвійне екранування
        s = s.replace("&amp;amp;", "&amp;")
        return s
    }

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

    /**
     * v465: sendPhoto(photo=URL) - Telegram качає з CDN замість Ktor.
     * Прибирає 90% TG_IMG_ERR: timeout.
     */
    /**
     * v465: sendMediaGroup з URL (без attach://). Telegram качає з CDN.
     */
    private suspend fun trySendMediaGroupByUrls(urls: List<String>, caption: String, token: String): Boolean {
        if (urls.size < 2) return false
        return try {
            val mediaArray = JSONArray()
            urls.forEachIndexed { i, u ->
                val obj = JSONObject().apply {
                    put("type", "photo")
                    put("media", u)
                    if (i == 0 && caption.isNotEmpty()) {
                        put("caption", caption)
                        put("parse_mode", "HTML")
                    }
                }
                mediaArray.put(obj)
            }
            val resp = client.post("https://api.telegram.org/bot$token/sendMediaGroup") {
                contentType(ContentType.Application.Json)
                setBody(JSONObject().apply {
                    put("chat_id", channelId)
                    put("media", mediaArray)
                }.toString())
            }
            val body = resp.bodyAsText()
            val ok = JSONObject(body).optBoolean("ok", false)
            if (!ok) {
                val desc = JSONObject(body).optString("description", body.take(120))
                LogManager.log("TG_GROUP_WARN", "sendMediaGroup(url) fail: $desc")
            }
            ok
        } catch (e: Throwable) {
            LogManager.log("TG_GROUP_ERR", "sendMediaGroup(url) crash: ${e.message}")
            false
        }
    }

    private suspend fun trySendPhotoByUrl(photoUrl: String, caption: String, token: String): Boolean {
        return try {
            val resp = client.post("https://api.telegram.org/bot$token/sendPhoto") {
                contentType(ContentType.Application.Json)
                setBody(JSONObject().apply {
                    put("chat_id", channelId)
                    put("photo", photoUrl)
                    if (caption.isNotEmpty()) {
                        put("caption", caption)
                        put("parse_mode", "HTML")
                    }
                }.toString())
            }
            val body = resp.bodyAsText()
            val ok = JSONObject(body).optBoolean("ok", false)
            if (!ok) {
                val desc = JSONObject(body).optString("description", body.take(120))
                LogManager.log("TG_URL_WARN", "sendPhoto(url) fail: $desc")
            }
            ok
        } catch (e: Throwable) {
            LogManager.log("TG_URL_ERR", "sendPhoto(url) crash: ${e.message}")
            false
        }
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

    suspend fun sendToTelegram(caption: String, imageUrls: List<String> = emptyList(), videoUrl: String = ""): Boolean {
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

            // ВІДЕО: якщо є videoUrl - публікуємо його замість фото
            if (videoUrl.isNotEmpty()) {
                val isYouTube = videoUrl.contains("youtube.com") || videoUrl.contains("youtu.be")
                if (isYouTube) {
                    val ytMessage = "$safeCaption\n\n$videoUrl"
                    val ytResponse = client.post("https://api.telegram.org/bot$token/sendMessage") {
                        contentType(ContentType.Application.Json)
                        setBody(JSONObject().apply {
                            put("chat_id", channelId)
                            put("text", ytMessage)
                            put("parse_mode", "HTML")
                            put("disable_web_page_preview", false)
                        }.toString())
                    }
                    val ytOk = JSONObject(ytResponse.bodyAsText()).optBoolean("ok", false)
                    if (ytOk) {
                        LogManager.log("TG_OK", "YouTube посилання опубліковано!")
                        return true
                    } else {
                        LogManager.log("TG_ERR", "Помилка YouTube: ${ytResponse.bodyAsText()}")
                        return false
                    }
                } else {
                    try {
                        val videoBytes = client.get(videoUrl).readBytes()
                        if (videoBytes.size > 50 * 1024 * 1024) {
                            LogManager.log("TG_WARN", "Відео >50MB, fallback на посилання")
                            val fbResp = client.post("https://api.telegram.org/bot$token/sendMessage") {
                                contentType(ContentType.Application.Json)
                                setBody(JSONObject().apply {
                                    put("chat_id", channelId)
                                    put("text", "$safeCaption\n\n$videoUrl")
                                    put("parse_mode", "HTML")
                                    put("disable_web_page_preview", false)
                                }.toString())
                            }
                            return JSONObject(fbResp.bodyAsText()).optBoolean("ok", false)
                        }
                        val videoResp = client.post("https://api.telegram.org/bot$token/sendVideo") {
                            setBody(MultiPartFormDataContent(formData {
                                append("chat_id", channelId)
                                if (captionForMedia.isNotEmpty()) {
                                    append("caption", captionForMedia)
                                    append("parse_mode", "HTML")
                                }
                                append("video", videoBytes, Headers.build {
                                    append(HttpHeaders.ContentType, "video/mp4")
                                    append(HttpHeaders.ContentDisposition, "filename=\"video.mp4\"")
                                })
                            }))
                        }
                        val videoOk = JSONObject(videoResp.bodyAsText()).optBoolean("ok", false)
                        if (videoOk) {
                            LogManager.log("TG_OK", "Відео опубліковано з підписом!")
                            return true
                        } else {
                            LogManager.log("TG_ERR", "Помилка sendVideo: ${videoResp.bodyAsText()}")
                        }
                    } catch (e: Throwable) {
                        LogManager.log("TG_VIDEO_ERR", "Відео не завантажилось: ${e.message}, fallback")
                        val fbResp = client.post("https://api.telegram.org/bot$token/sendMessage") {
                            contentType(ContentType.Application.Json)
                            setBody(JSONObject().apply {
                                put("chat_id", channelId)
                                put("text", "$safeCaption\n\n$videoUrl")
                                put("parse_mode", "HTML")
                                put("disable_web_page_preview", false)
                            }.toString())
                        }
                        return JSONObject(fbResp.bodyAsText()).optBoolean("ok", false)
                    }
                }
            }

            if (validUrls.size > 1) {
                // v465: спочатку URL (ресайз -> Telegram качає з CDN), потім байти fallback
                val resizedUrls = validUrls.map { resizeImageUrl(it) }
                if (trySendMediaGroupByUrls(resizedUrls, captionForMedia, token)) {
                    mediaSentOk = true
                    LogManager.log("TG_OK", "Галерея URL x${resizedUrls.size} опублікована")
                } else {
                    LogManager.log("TG_WARN", "URL-галерея не пройшла, спроба og:image")
                    val ogCandidates = mutableListOf<String>()
                    for (u in validUrls) {
                        val og = fetchOgImage(u)
                        if (og != null) ogCandidates.add(og)
                    }
                    if (ogCandidates.size >= 2 && trySendMediaGroupByUrls(ogCandidates.map { resizeImageUrl(it) }, captionForMedia, token)) {
                        mediaSentOk = true
                        LogManager.log("TG_OK", "Галерея og:image x${ogCandidates.size} опублікована")
                    } else {
                        LogManager.log("TG_WARN", "og:image не вистачило, байти fallback")
                        val bytesList = validUrls.mapNotNull { getJpegBytesFromUrl(it) }
                        if (bytesList.size >= 2) {
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
                                                put("parse_mode", "HTML"); put("disable_web_page_preview", true)
                                            }
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
                            if (!mediaSentOk) LogManager.log("TG_ERR", "Байтова галерея fail: ${response.bodyAsText().take(150)}")
                        }
                    }
                }
            
            } else if (validUrls.size == 1) {
                // v465: URL -> og:image -> байти
                val origUrl = validUrls.first()
                val resized = resizeImageUrl(origUrl)
                var ok = trySendPhotoByUrl(resized, captionForMedia, token)
                if (ok) {
                    LogManager.log("TG_OK", "Фото URL опубліковано (resize)")
                } else {
                    val og = fetchOgImage(origUrl)
                    if (og != null) {
                        ok = trySendPhotoByUrl(resizeImageUrl(og), captionForMedia, token)
                        if (ok) LogManager.log("TG_OK", "Фото og:image опубліковано")
                    }
                    if (!ok) {
                        val jpegBytes = getJpegBytesFromUrl(origUrl)
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
                            ok = JSONObject(response.bodyAsText()).optBoolean("ok", false)
                            if (!ok) LogManager.log("TG_ERR", "Байтове фото fail: ${response.bodyAsText().take(150)}")
                        }
                    }
                    mediaSentOk = ok
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
