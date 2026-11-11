package com.w10m.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * Accessibility service used for one thing only: a long press on the Back key opens the system
 * task switcher (GLOBAL_ACTION_RECENTS), as on Windows 10 Mobile. It reads no window content.
 */
class KeysService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        @Volatile private var instance: KeysService? = null

        /** Opens the task switcher, or sends the user to enable the service the first time. */
        fun recents(c: Context) {
            val s = instance
            if (s != null) { s.performGlobalAction(GLOBAL_ACTION_RECENTS); return }
            Toast.makeText(c, R.string.keys_service_hint, Toast.LENGTH_LONG).show()
            try { c.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
    }
}
