package com.newsapp.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.core.isEmpty
import io.ktor.utils.io.core.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class UpdateManager(private val context: Context) {
    private val client = HttpClient(CIO) {
        followRedirects = true
    }
    
    private val repoUrl = "https://api.github.com/repos/Tim4Lu/NewsAppKotlinAgrregator-/releases/latest"

    data class UpdateInfo(val versionCode: Int, val downloadUrl: String, val tagName: String)

    suspend fun checkForUpdate(currentBuildNumber: Int = 1): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val response = client.get(repoUrl).bodyAsText()
            val json = JSONObject(response)
            val tagName = json.optString("tag_name", "")
            
            val latestBuild = tagName.substringAfterLast(".").toIntOrNull() ?: 0
            LogManager.log("UPDATE_CHECK", "Тег GitHub: $tagName (build: $latestBuild), Поточний: $currentBuildNumber")

            if (latestBuild > currentBuildNumber) {
                val assets = json.optJSONArray("assets")
                if (assets != null && assets.length() > 0) {
                    val apkAsset = assets.getJSONObject(0)
                    val downloadUrl = apkAsset.getString("browser_download_url")
                    return@withContext UpdateInfo(latestBuild, downloadUrl, tagName)
                }
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

            val response = client.get(downloadUrl)
            val channel: ByteReadChannel = response.bodyAsChannel()
            val outputStream = FileOutputStream(apkFile)

            while (!channel.isClosedForRead) {
                val packet = channel.readRemaining(8192)
                while (!packet.isEmpty) {
                    val bytes = packet.readBytes()
                    outputStream.write(bytes)
                }
            }

            outputStream.flush()
            outputStream.close()

            LogManager.log("UPDATE_OK", "Завантажено успішно. Запуск встановлення...")
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
