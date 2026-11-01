package com.metrowin.keyboard.integration

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.metrowin.keyboard.KeyboardApp

/**
 * Receives the look of MetroWin Launcher when it changes. It is exported so the launcher can reach it, so it only accepts values of
 * known shapes, and only changes colours and style, nothing else, and only if the user left "follow the launcher" on.
 */
class ThemeSyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LauncherLink.ACTION_APPLY_THEME) return
        val prefs = KeyboardApp.of(context).prefs
        if (!prefs.launcherSync) return
        LauncherLink.fromIntent(intent)?.let { LauncherLink.apply(prefs, it) }
    }
}
