package com.example.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.example.util.NotificationHelper

class BluetoothForegroundService : Service {

    constructor() : super()

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createNotificationChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val status = intent?.getStringExtra(EXTRA_STATUS) ?: "Connected to ESP32 Radio Bridge"
        val notification = NotificationHelper.getServiceNotification(this, status)
        startForeground(NotificationHelper.SERVICE_NOTIFICATION_ID, notification)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.example.service.START_BRIDGE"
        const val ACTION_STOP = "com.example.service.STOP_BRIDGE"
        const val EXTRA_STATUS = "extra_status"

        fun start(context: Context, status: String = "Connected to ESP32 Radio Bridge") {
            val intent = Intent(context, BluetoothForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_STATUS, status)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, BluetoothForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
