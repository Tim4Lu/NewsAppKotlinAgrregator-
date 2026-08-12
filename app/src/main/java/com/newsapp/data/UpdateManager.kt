package com.newsapp.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class UpdateManager(private val context: Context) {
    private val client = HttpClient(CIO) {
        followRedirects = true
    }
    
    private val repoUrl = "https://api.github.com/repos/Tim4Lu/NewsAppKotlinAgrregator-/releases/latest"

    data class UpdateInfo(val versionCode: Int, val downloadUrl: String, val tagName: String)

    suspend fun checkForUpdate(currentBuildNumber: Int = 1): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val response = client.get(repoUrl) {
                header("User-Agent", "Mozilla/5.0 (Android; Mobile)")
            }.bodyAsText()
            
            val json = JSONObject(response)
            val tagName = json.optString("tag_name", "")
            
            val latestBuild = tagName.substringAfterLast(".").toIntOrNull() ?: 0
            LogManager.log("UPDATE_CHECK", "Тег GitHub: $tagName (build: $latestBuild), Поточна версія: $currentBuildNumber")

            if (latestBuild > currentBuildNumber) {
                val assets = json.optJSONArray("assets")
                if (assets != null && assets.length() > 0) {
                    val apkAsset = assets.getJSONObject(0)
                    val downloadUrl = apkAsset.getString("browser_download_url")
                    return@withContext UpdateInfo(latestBuild, downloadUrl, tagName)
                }
            } else {
                LogManager.log("UPDATE", "Встановлено актуальну версію ($currentBuildNumber). Оновлення не потрібні.")
            }
        } catch (e: Exception) {
            LogManager.log("UPDATE_ERR", "Помилка перевірки оновлення: ${e.message}")
        }
        return@withContext null
    }

    suspend fun downloadAndInstallApk(downloadUrl: String) = withContext(Dispatchers.IO) {
        try {
            val apkFile = File(context.getExternalFilesDir(null), "update.apk")
            if (apkFile.exists()) apkFile.delete()

            LogManager.log("UPDATE", "Завантаження оновлення з $downloadUrl...")

            var currentUrl = downloadUrl
            var connection: HttpURLConnection
            var redirect: Boolean

            do {
                val url = URL(currentUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                connection.connect()

                val status = connection.responseCode
                redirect = status == HttpURLConnection.HTTP_MOVED_TEMP || 
                           status == HttpURLConnection.HTTP_MOVED_PERM || 
                           status == HttpURLConnection.HTTP_SEE_OTHER

                if (redirect) {
                    currentUrl = connection.getHeaderField("Location")
                }
            } while (redirect)

            val fileLength = connection.contentLength
            val inputStream = connection.inputStream
            val outputStream = FileOutputStream(apkFile)
            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalBytesRead: Long = 0
            var lastLoggedProgress = -1

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                totalBytesRead += bytesRead
                outputStream.write(buffer, 0, bytesRead)

                if (fileLength > 0) {
                    val progress = ((totalBytesRead * 100) / fileLength).toInt()
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
        val intent = Intent(Intent.ACTION_VIEW)
        val apkUri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } else {
            Uri.fromFile(file)
        }

        intent.setDataAndType(apkUri, "application/vnd.android.package-archive")
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        context.startActivity(intent)
    }
}
