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

    suspend fun checkForUpdate(currentBuildNumber: Int): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.github.com/repos/Tim4Lu/NewsAppKotlinAgrregator-/releases/latest")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.connectTimeout = 5000

            if (conn.responseCode == 200) {
                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(jsonStr)
                val tagName = json.getString("tag_name")
                
                // Виправляємо парсинг: коректно зчитуємо номер збірки після 'v'
                val remoteBuild = tagName.removePrefix("v").removePrefix("V").trim().toIntOrNull() ?: 0

                LogManager.log("UPDATE_CHECK", "GitHub: $tagName (build: $remoteBuild), Поточна: $currentBuildNumber")

                if (remoteBuild > currentBuildNumber) {
                    val assets = json.getJSONArray("assets")
                    if (assets.length() > 0) {
                        val downloadUrl = assets.getJSONObject(0).getString("browser_download_url")
                        return@withContext UpdateInfo(tagName, downloadUrl, remoteBuild)
                    }
                }
            }
        } catch (e: Exception) {
            LogManager.log("UPDATE_ERR", "Помилка перевірки: ${e.message}")
        }
        return@withContext null
    }

    suspend fun downloadAndInstallApk(downloadUrl: String) = withContext(Dispatchers.IO) {
        try {
            LogManager.log("UPDATE", "Завантаження $downloadUrl...")

            var currentUrl = downloadUrl
            var conn: HttpURLConnection
            var redirect: Boolean
            var redirectsCount = 0

            do {
                val url = URL(currentUrl)
                conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn.connectTimeout = 15000
                conn.readTimeout = 15000

                val status = conn.responseCode
                redirect = status == HttpURLConnection.HTTP_MOVED_TEMP ||
                        status == HttpURLConnection.HTTP_MOVED_PERM ||
                        status == HttpURLConnection.HTTP_SEE_OTHER

                if (redirect) {
                    currentUrl = conn.getHeaderField("Location")
                    redirectsCount++
                }
            } while (redirect && redirectsCount < 5)

            val inputStream = conn.inputStream
            val apkFile = File(context.cacheDir, "update.apk")
            val outputStream = FileOutputStream(apkFile)

            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()

            LogManager.log("UPDATE_OK", "APK завантажено. Запуск встановлення...")
            installApk(apkFile)

        } catch (e: Exception) {
            LogManager.log("UPDATE_ERR", "Помилка завантаження: ${e.message}")
        }
    }

    private fun installApk(file: File) {
        try {
            com.newsapp.data.LogManager.log("TRACE", "Викликано функцію: installApk (безпечна)")
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            } else {
                Uri.fromFile(file)
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                }
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            com.newsapp.data.LogManager.log("UPDATE_ERR", "Помилка інсталятора: ${e.message}")
        }
    }
}

