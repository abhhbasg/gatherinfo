package com.w10m.launcher

import android.app.Notification
import android.app.PendingIntent
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** One active notification: live-tile content and an Action Center entry. */
class Note(val title: String, val text: String, val time: Long, val key: String = "", val pkg: String = "",
           val intent: PendingIntent? = null, val clearable: Boolean = true)

/** Feeds tile badges, live-tile content and the Action Center from the system's active notifications. */
class BadgeService : NotificationListenerService() {

    companion object {
        @Volatile var counts: Map<String, Int> = emptyMap()
            private set
        /** Latest notification per package. */
        @Volatile var notes: Map<String, Note> = emptyMap()
            private set
        /** All notifications, newest first. */
        @Volatile var all: List<Note> = emptyList()
            internal set
        @Volatile var connected = false
            internal set
        private val listeners = LinkedHashSet<() -> Unit>()
        private val main = Handler(Looper.getMainLooper())
        @Volatile private var instance: BadgeService? = null

        fun addListener(l: () -> Unit) { listeners += l }
        fun removeListener(l: () -> Unit) { listeners -= l }
        private fun notifyAllListeners() = main.post { listeners.toList().forEach { it() } }

        fun dismiss(key: String) { try { instance?.cancelNotification(key) } catch (_: Exception) {} }
        fun dismissAll() { try { instance?.cancelAllNotifications() } catch (_: Exception) {} }
    }

    override fun onListenerConnected() { instance = this; connected = true; refresh() }
    override fun onNotificationPosted(sbn: StatusBarNotification?) = refresh()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()
    override fun onListenerDisconnected() {
        instance = null; connected = false
        counts = emptyMap(); notes = emptyMap(); all = emptyList(); notifyAllListeners()
    }

    private fun refresh() {
        val list = try { activeNotifications } catch (_: Exception) { null } ?: return
        val m = HashMap<String, Int>()
        val n = HashMap<String, Note>()
        val a = ArrayList<Note>()
        for (sbn in list) {
            val nf = sbn.notification
            if (nf.flags and Notification.FLAG_GROUP_SUMMARY != 0) continue
            val title = nf.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = nf.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            if (title.isEmpty() && text.isEmpty()) continue
            val note = Note(title, text, sbn.postTime, sbn.key, sbn.packageName, nf.contentIntent, sbn.isClearable)
            a += note
            if (sbn.isOngoing) continue // ongoing ones show in the Action Center but don't badge tiles
            m[sbn.packageName] = (m[sbn.packageName] ?: 0) + if (nf.number > 0) nf.number else 1
            val old = n[sbn.packageName]
            if (old == null || sbn.postTime > old.time) n[sbn.packageName] = note
        }
        counts = m; notes = n; all = a.sortedByDescending { it.time }
        notifyAllListeners()
    }
}
