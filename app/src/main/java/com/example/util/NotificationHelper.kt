package com.example.util

import android.app.Notification
import android.content.Context

object NotificationHelper {
    const val CHANNEL_MESSAGES_ID = NotificationHandler.CHANNEL_NORMAL_ID
    const val CHANNEL_SERVICE_ID = NotificationHandler.CHANNEL_SERVICE_ID
    const val SERVICE_NOTIFICATION_ID = NotificationHandler.SERVICE_NOTIFICATION_ID

    fun createNotificationChannels(context: Context) {
        NotificationHandler.createChannels(context)
    }

    fun getServiceNotification(context: Context, statusText: String): Notification {
        return NotificationHandler.getServiceNotification(context, statusText)
    }

    fun showNewMessageNotification(context: Context, sender: String, content: String, headerType: Int = 1) {
        NotificationHandler.handleIncomingNotification(context, sender, content, headerType)
    }

    fun showMessageDeliveredNotification(context: Context, preview: String) {
        // Simple delivered receipt notification
    }

    fun showMessageFailedNotification(context: Context, preview: String) {
        // Failed send alert
    }
}
