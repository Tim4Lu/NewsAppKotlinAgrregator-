package com.newsapp.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val tagName: String,
    val downloadUrl: String,
    val buildNumber: Int
)

class UpdateManager(private val context: Context) {

    private val githubRepo = "Tim4Lu/NewsAppKotlinAgrregator-"

    suspend fun checkForUpdate(currentBuildNumber: Int): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.github.com/repos/$githubRepo/releases/latest")
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0")
                connectTimeout = 10000
                readTimeout = 10000
            }

            if (connection.responseCode == 200) {
                val jsonStr = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(jsonStr)
                val tagName = json.getString("tag_name") // "v1.0.103"

                val remoteBuild = tagName.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0

                LogManager.log("UPDATE_CHECK", "Ter GitHub: $tagName (build: $remoteBuild), Поточна версія: $currentBuildNumber")

                if (remoteBuild > currentBuildNumber) {
                    val assets = json.getJSONArray("assets")
                    if (assets.length() > 0) {
                        val downloadUrl = assets.getJSONObject(0).getString("browser_download_url")
                        return@withContext UpdateInfo(tagName, downloadUrl, remoteBuild)
                    }
                }
            }
        } catch (e: Exception) {
            LogManager.log("UPDATE_ERR", "Помилка перевірки оновлень: ${e.message}")
        }
        return@withContext null
    }

    suspend fun downloadAndInstallApk(downloadUrl: String) = withContext(Dispatchers.IO) {
        try {
            LogManager.log("UPDATE", "Завантаження оновлення з $downloadUrl...")

            var currentUrl = downloadUrl
            var connection: HttpURLConnection
            var redirect: Boolean
            var redirectsCount = 0

            // Обробка HTTP 301/302/303 редиректів GitHub CDN
            do {
                val url = URL(currentUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.apply {
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                    connectTimeout = 15000
                    readTimeout = 15000
                }

                val status = connection.responseCode
                redirect = status == HttpURLConnection.HTTP_MOVED_TEMP ||
                        status == HttpURLConnection.HTTP_MOVED_PERM ||
                        status == HttpURLConnection.HTTP_SEE_OTHER

                if (redirect) {
                    currentUrl = connection.getHeaderField("Location")
                    redirectsCount++
                }
            } while (redirect && redirectsCount < 5)

            val totalSize = connection.contentLength
            val inputStream = connection.inputStream
            val apkFile = File(context.cacheDir, "update.apk")
            val outputStream = FileOutputStream(apkFile)

            val buffer = ByteArray(8192)
            var bytesRead: Int
            var downloaded = 0L
            var lastLoggedProgress = -1

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                downloaded += bytesRead

                if (totalSize > 0) {
                    val progress = ((downloaded * 100) / totalSize).toInt()
                    if (progress % 25 == 0 && progress != lastLoggedProgress) {
                        LogManager.log("UPDATE_PROGRESS", "Завантаження APK: $progress%")
                        lastLoggedProgress = progress
                    }
                }
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()

            LogManager.log("UPDATE_OK", "APK успішно завантажено. Запуск встановлення...")
            installApk(apkFile)

        } catch (e: Exception) {
            LogManager.log("UPDATE_ERR", "Помилка завантаження: ${e.message}")
        }
    }

    private fun installApk(file: File) {
        val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        } else {
            Uri.fromFile(file)
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}
