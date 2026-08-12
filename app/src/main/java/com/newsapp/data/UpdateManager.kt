package com.newsapp.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class UpdateManager(private val context: Context) {
    private val client = HttpClient(CIO)
    private val repoUrl = "https://api.github.com/repos/Tim4Lu/NewsAppKotlinAgrregator-/releases/latest"

    data class UpdateInfo(val versionCode: Int, val downloadUrl: String, val tagName: String)

    suspend fun checkForUpdate(currentBuildNumber: Int = 1): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val response = client.get(repoUrl).bodyAsText()
            val json = JSONObject(response)
            val tagName = json.optString("tag_name", "") // наприклад "v1.0.68"
            
            val latestBuild = tagName.substringAfterLast(".").toIntOrNull() ?: 0

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

    suspend fun downloadAndInstallApk(downloadUrl: String, onProgress: (Int) -> Unit = {}) = withContext(Dispatchers.IO) {
        try {
            val apkFile = File(context.getExternalFilesDir(null), "update.apk")
            if (apkFile.exists()) apkFile.delete()

            LogManager.log("UPDATE", "Завантаження оновлення з $downloadUrl...")
            
            val response = client.get(downloadUrl)
            val bytes = response.bodyAsText().toByteArray() // завантаження файлу

            val input = response.engineResponse.let { client.get(downloadUrl) }
            
            // Запис у файл
            val url = java.net.URL(downloadUrl)
            val connection = url.openConnection()
            connection.connect()
            val fileLength = connection.contentLength

            val inputData = connection.getInputStream()
            val outputData = FileOutputStream(apkFile)

            val data = ByteArray(4096)
            var total: Long = 0
            var count: Int
            while (inputData.read(data).also { count = it } != -1) {
                total += count.toLong()
                if (fileLength > 0) {
                    onProgress((total * 100 / fileLength).toInt())
                }
                outputData.write(data, 0, count)
            }

            outputData.flush()
            outputData.close()
            inputData.close()

            LogManager.log("UPDATE_OK", "Завантажено. Запуск встановлення...")
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
