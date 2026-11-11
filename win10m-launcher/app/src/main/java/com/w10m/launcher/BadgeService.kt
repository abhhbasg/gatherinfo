package com.w10m.launcher

import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Latest notification of an app, shown on its live tile. */
class Note(val title: String, val text: String, val time: Long)

/** Feeds tile badges (count per package) and live-tile content (latest notification per package). */
class BadgeService : NotificationListenerService() {

    companion object {
        @Volatile var counts: Map<String, Int> = emptyMap()
            private set
        @Volatile var notes: Map<String, Note> = emptyMap()
            private set
        var listener: (() -> Unit)? = null
        private val main = Handler(Looper.getMainLooper())
    }

    override fun onListenerConnected() = refresh()
    override fun onNotificationPosted(sbn: StatusBarNotification?) = refresh()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()
    override fun onListenerDisconnected() { counts = emptyMap(); notes = emptyMap(); main.post { listener?.invoke() } }

    private fun refresh() {
        val list = try { activeNotifications } catch (_: Exception) { null } ?: return
        val m = HashMap<String, Int>()
        val n = HashMap<String, Note>()
        for (sbn in list) {
            val nf = sbn.notification
            if (sbn.isOngoing || nf.flags and Notification.FLAG_GROUP_SUMMARY != 0) continue
            m[sbn.packageName] = (m[sbn.packageName] ?: 0) + if (nf.number > 0) nf.number else 1
            val title = nf.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = nf.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            if (title.isEmpty() && text.isEmpty()) continue
            val old = n[sbn.packageName]
            if (old == null || sbn.postTime > old.time) n[sbn.packageName] = Note(title, text, sbn.postTime)
        }
        counts = m; notes = n
        main.post { listener?.invoke() }
    }
}
