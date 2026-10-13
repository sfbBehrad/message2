package com.example.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.protocol.PacketParser

object NotificationHandler {
    const val CHANNEL_SILENT_ID = "radio_bridge_silent"
    const val CHANNEL_NORMAL_ID = "radio_bridge_normal"
    const val CHANNEL_ALARM_ID = "radio_bridge_emergency_alarm"
    const val CHANNEL_SERVICE_ID = "radio_bridge_service"

    const val SERVICE_NOTIFICATION_ID = 1001

    private var alarmMediaPlayer: MediaPlayer? = null
    var isAlarmActive: Boolean = false
        private set

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // 1. Silent channel (Header 7): Importance Low, no sound, no vibration
            val silentChannel = NotificationChannel(
                CHANNEL_SILENT_ID,
                "Silent Radio Transmissions",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Silent messages without sound or vibration"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(true)
            }

            // 2. Normal channel (Header 1/0): Importance Default
            val normalChannel = NotificationChannel(
                CHANNEL_NORMAL_ID,
                "Standard Radio Messages",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Standard radio transmissions with sound"
                enableVibration(true)
            }

            // 3. Critical Alarm channel (Header 8): Importance High, bypasses DND
            val alarmChannel = NotificationChannel(
                CHANNEL_ALARM_ID,
                "Emergency Critical Alarms",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical emergency alarms that sound continuously until acknowledged"
                enableVibration(true)
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            // 4. Background Service channel
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE_ID,
                "Radio Bridge Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Maintains Bluetooth radio bridge in background"
                setShowBadge(false)
            }

            manager.createNotificationChannel(silentChannel)
            manager.createNotificationChannel(normalChannel)
            manager.createNotificationChannel(alarmChannel)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    /**
     * Handles incoming notification based on header type (Normal vs Silent vs Alarm).
     */
    fun handleIncomingNotification(
        context: Context,
        sender: String,
        content: String,
        headerType: Int
    ) {
        createChannels(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        when (headerType) {
            // Header 7: Silent (no sound, no vibration)
            PacketParser.TYPE_SILENT -> {
                val notification = NotificationCompat.Builder(context, CHANNEL_SILENT_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setContentTitle("Message (Silent) from $sender")
                    .setContentText(content)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setSilent(true)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .build()

                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(201, notification)
            }

            // Header 8: Critical Loud Alarm
            PacketParser.TYPE_CRITICAL_ALARM -> {
                playLoudEmergencyAlarm(context)

                val notification = NotificationCompat.Builder(context, CHANNEL_ALARM_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentTitle("EMERGENCY CRITICAL ALARM from $sender")
                    .setContentText(content)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)
                    .build()

                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(202, notification)
            }

            // Header 0 / 1: Standard Normal Notification
            else -> {
                val notification = NotificationCompat.Builder(context, CHANNEL_NORMAL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setContentTitle("New Message from $sender")
                    .setContentText(content)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .build()

                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(200, notification)
            }
        }
    }

    /**
     * Plays a continuous looping loud alarm using MediaPlayer on USAGE_ALARM stream.
     */
    @Synchronized
    fun playLoudEmergencyAlarm(context: Context) {
        stopAlarm()
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            alarmMediaPlayer = MediaPlayer().apply {
                setDataSource(context, alarmUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
            isAlarmActive = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Stops the continuous emergency alarm when the user opens the chat screen or app.
     */
    @Synchronized
    fun stopAlarm() {
        try {
            if (alarmMediaPlayer?.isPlaying == true) {
                alarmMediaPlayer?.stop()
            }
            alarmMediaPlayer?.release()
        } catch (_: Exception) {}
        alarmMediaPlayer = null
        isAlarmActive = false
    }

    fun getServiceNotification(context: Context, statusText: String): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_SERVICE_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("NRF24 Radio Bridge Active")
            .setContentText(statusText)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }
}
