package com.w10m.launcher

import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Counts active notifications per package to drive W10M-style tile badges. */
class BadgeService : NotificationListenerService() {

    companion object {
        @Volatile var counts: Map<String, Int> = emptyMap()
            private set
        var listener: (() -> Unit)? = null
        private val main = Handler(Looper.getMainLooper())
    }

    override fun onListenerConnected() = refresh()
    override fun onNotificationPosted(sbn: StatusBarNotification?) = refresh()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()
    override fun onListenerDisconnected() { counts = emptyMap(); main.post { listener?.invoke() } }

    private fun refresh() {
        val list = try { activeNotifications } catch (_: Exception) { null } ?: return
        val m = HashMap<String, Int>()
        for (n in list) {
            if (n.isOngoing || n.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) continue
            val num = n.notification.number
            m[n.packageName] = (m[n.packageName] ?: 0) + if (num > 0) num else 1
        }
        counts = m
        main.post { listener?.invoke() }
    }
}
