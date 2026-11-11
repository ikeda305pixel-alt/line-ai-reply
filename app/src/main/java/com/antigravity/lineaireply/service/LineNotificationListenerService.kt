package com.antigravity.lineaireply.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * High-performance background Notification Listener.
 * Catches incoming messages from LINE and Bumble, maintaining a local chronological
 * cache of conversation history per chat room/sender.
 *
 * This completely replaces both AccessibilityService and MediaProjection:
 * - 0% risk of PayPay or banking app blocks (not an accessibility service).
 * - 0% risk of Android 15 private display / screen share blackouts.
 * - Zero screen recording icon in status bar.
 */
class LineNotificationListenerService : NotificationListenerService() {

    companion object {
        const val TAG = "LineNotifListener"
        const val PACKAGE_LINE = "jp.naver.line.android"
        const val PACKAGE_BUMBLE = "com.bumble.app"

        /**
         * Checks if the user has enabled Notification Access for this app.
         */
        fun isNotificationAccessGranted(context: Context): Boolean {
            val enabledListeners = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val myComponent = ComponentName(context, LineNotificationListenerService::class.java).flattenToString()
            return enabledListeners.contains(myComponent, ignoreCase = true)
        }

        /**
         * Returns an intent to take the user directly to the Notification Access settings screen.
         */
        fun getSettingsIntent(): Intent {
            return Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val pkg = sbn.packageName ?: return
        if (pkg != PACKAGE_LINE && pkg != PACKAGE_BUMBLE) return

        val notif = sbn.notification ?: return
        val extras = notif.extras ?: return

        // 1. Check for Android MessagingStyle messages (rich conversation format)
        val messagingStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notif)
        if (messagingStyle != null && messagingStyle.messages.isNotEmpty()) {
            val conversationTitle = messagingStyle.conversationTitle?.toString()
                ?: extras.getString(Notification.EXTRA_CONVERSATION_TITLE)
                ?: extras.getString(Notification.EXTRA_TITLE)
                ?: "LINEトーク"

            for (m in messagingStyle.messages) {
                val senderName = m.person?.name?.toString()
                    ?: extras.getString(Notification.EXTRA_TITLE)
                    ?: conversationTitle
                val text = m.text?.toString() ?: ""
                val time = if (m.timestamp > 0) m.timestamp else sbn.postTime
                if (text.isNotBlank()) {
                    NotificationChatCacheManager.addMessage(
                        context = applicationContext,
                        roomName = conversationTitle,
                        sender = senderName,
                        text = text,
                        timestamp = time,
                        isPartner = true
                    )
                }
            }
            return
        }

        // 2. Standard notification extraction
        val title = extras.getString(Notification.EXTRA_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: if (pkg == PACKAGE_BUMBLE) "Bumble" else "LINE"

        // Prefer BIG_TEXT if available, otherwise regular TEXT
        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: ""

        if (text.isNotBlank()) {
            NotificationChatCacheManager.addMessage(
                context = applicationContext,
                roomName = title,
                sender = title,
                text = text,
                timestamp = sbn.postTime,
                isPartner = true
            )
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "LineNotificationListenerService connected and monitoring LINE/Bumble notifications")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "LineNotificationListenerService disconnected")
    }
}
