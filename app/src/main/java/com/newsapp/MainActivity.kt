package com.newsapp

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.newsapp.data.LogManager
import com.newsapp.data.UpdateManager
import com.newsapp.ui.NewsScreen
import com.newsapp.ui.viewmodel.NewsViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "[LOG] onCreate запущено")

        val viewModel = ViewModelProvider(this)[NewsViewModel::class.java]

        // Запускаємо перевірку оновлень при старті
        checkForUpdates()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // ТЕПЕР ВИКЛИКАЄМО НАШ ПРАВИЛЬНИЙ ЕКРАН
                    NewsScreen(viewModel = viewModel)
                }
            }
        }
    }

    private fun checkForUpdates() {
        lifecycleScope.launch {
            try {
                val updateManager = UpdateManager(this@MainActivity)
                
                // Динамічно отримуємо поточний versionCode (currentBuildNumber)
                val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(packageName, 0)
                }

                val currentBuildNumber = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode.toInt()
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.versionCode
                }

                LogManager.log("APP_START", "Поточна версія збірки: $currentBuildNumber")

                // Перевіряємо GitHub
                val updateInfo = updateManager.checkForUpdate(currentBuildNumber)
                
                // Якщо є новіша версія — завантажуємо і встановлюємо
                if (updateInfo != null) {
                    LogManager.log("UPDATE", "Знайдено оновлення! Версія: ${updateInfo.buildNumber}")
                    updateManager.downloadAndInstallApk(updateInfo.downloadUrl)
                }
            } catch (e: Exception) {
                LogManager.log("UPDATE_ERR", "Не вдалося перевірити оновлення: ${e.message}")
            }
        }
    }
}
