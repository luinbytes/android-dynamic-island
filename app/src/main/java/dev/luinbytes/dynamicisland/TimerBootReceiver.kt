package dev.luinbytes.dynamicisland

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Rebuilds app-owned timer alarms after Android clears them during a reboot. */
class TimerBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        IslandRuntime.initialize(context.applicationContext)
        IslandRuntime.reconcileTimerAlarms()
    }
}
