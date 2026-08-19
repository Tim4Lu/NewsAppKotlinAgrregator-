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
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.newsapp.data.LogManager
import com.newsapp.data.UpdateManager
import com.newsapp.service.NewsWorker
import com.newsapp.ui.NewsScreen
import com.newsapp.ui.viewmodel.NewsViewModel
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        com.newsapp.data.LogManager.log("TRACE", "Викликано функцію: onCreate")
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "[LOG] onCreate запущено")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        val viewModel = ViewModelProvider(this)[NewsViewModel::class.java]

        checkForUpdates()
        setupBackgroundWork()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NewsScreen(viewModel = viewModel)
                }
            }
        }
    }

    private fun setupBackgroundWork() {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<NewsWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "NewsBackgroundWorker",
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
            LogManager.log("WORKER", "Фоновий воркер успішно заплановано (кожні 15 хв)")
        } catch (e: Exception) {
            LogManager.log("WORKER_ERR", "Не вдалося запустити воркер: ${e.message}")
        }
    }

    private fun checkForUpdates() {
        com.newsapp.data.LogManager.log("TRACE", "Викликано функцію: checkForUpdates")
        lifecycleScope.launch {
            try {
                val updateManager = UpdateManager(this@MainActivity)
                
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

                val updateInfo = updateManager.checkForUpdate(currentBuildNumber)
                
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
