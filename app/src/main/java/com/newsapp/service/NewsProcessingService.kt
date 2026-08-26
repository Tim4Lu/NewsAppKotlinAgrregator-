package com.newsapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class NewsProcessingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_STICKY
        }

        try {
            createNotificationChannel()
            val notification = createNotification("Обробка та відправка новин у фоновому режимі...")
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            com.newsapp.data.LogManager.log("SERVICE_ERR", "Помилка служби: ${e.message}")
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        com.newsapp.data.LogManager.log("TRACE", "Викликано функцію: createNotificationChannel")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Фонова обробка AI",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        com.newsapp.data.LogManager.log("TRACE", "Викликано функцію: createNotification")
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NewsApp працює у фоні")
            .setContentText(contentText)
            .setSmallIcon(applicationInfo.icon)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "news_processing_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"

        fun start(context: Context) {
        com.newsapp.data.LogManager.log("TRACE", "Викликано функцію: start")
            try {
                val intent = Intent(context, NewsProcessingService::class.java).apply {
                    action = ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun stop(context: Context) {
        com.newsapp.data.LogManager.log("TRACE", "Викликано функцію: stop")
            try {
                val intent = Intent(context, NewsProcessingService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
