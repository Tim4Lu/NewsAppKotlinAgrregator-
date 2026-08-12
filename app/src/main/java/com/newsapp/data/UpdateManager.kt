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

class UpdateManager(private val context: Context) {

    suspend fun checkForUpdate(currentBuildNumber: Int): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.github.com/repos/Tim4Lu/NewsAppKotlinAgrregator-/releases/latest")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.connectTimeout = 5000
            
            if (conn.responseCode == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val tagName = json.getString("tag_name") // "v109"
                val remoteBuild = tagName.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
                
                if (remoteBuild > currentBuildNumber) {
                    val assets = json.getJSONArray("assets")
                    return@withContext assets.getJSONObject(0).getString("browser_download_url")
                }
            }
        } catch (e: Exception) {
            LogManager.log("UPDATE_ERR", "Помилка перевірки: ${e.message}")
        }
        return@withContext null
    }

    suspend fun downloadAndInstallApk(downloadUrl: String) = withContext(Dispatchers.IO) {
        try {
            LogManager.log("UPDATE", "Завантаження...")
            
            // Чистий URLConnection, який сам обробляє редиректи
            val url = URL(downloadUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            
            val inputStream = conn.inputStream
            val apkFile = File(context.cacheDir, "update.apk")
            val outputStream = FileOutputStream(apkFile)
            
            val buffer = ByteArray(8192)
            var bytes: Int
            while (inputStream.read(buffer).also { bytes = it } != -1) {
                outputStream.write(buffer, 0, bytes)
            }
            outputStream.close()
            inputStream.close()
            
            installApk(apkFile)
        } catch (e: Exception) {
            LogManager.log("UPDATE_ERR", "Помилка: ${e.message}")
        }
    }

    private fun installApk(file: File) {
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        } else {
            Uri.fromFile(file)
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}
