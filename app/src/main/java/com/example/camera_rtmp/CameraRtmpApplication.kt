package com.example.camera_rtmp

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class CameraRtmpApplication : Application() {
    
    companion object {
        const val NOTIFICATION_CHANNEL_ID = "stream_service_channel"
        const val NOTIFICATION_CHANNEL_NAME = "推流服务"
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "用于显示推流服务状态"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
}
