package dev.luinbytes.dynamicisland

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Routes a timer alarm through the application-owned runtime and its persisted repository state. */
class TimerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val timerId = intent?.data?.lastPathSegment?.takeIf(String::isNotBlank) ?: return
        IslandRuntime.onTimerAlarm(context.applicationContext, timerId)
    }
}
