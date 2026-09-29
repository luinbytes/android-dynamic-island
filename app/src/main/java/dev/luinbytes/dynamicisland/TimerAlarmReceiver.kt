package dev.luinbytes.dynamicisland

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Routes a timer alarm through the application-owned runtime and its persisted repository state. */
class TimerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val receivedIntent = intent ?: return
        val appContext = context.applicationContext
        val segments = receivedIntent.data?.pathSegments.orEmpty()
        when (receivedIntent.action) {
            TimerAlarmScheduler.ACTION_TIMER_ALARM -> {
                val timerId = segments.firstOrNull()?.takeIf(String::isNotBlank) ?: return
                IslandRuntime.onTimerAlarm(appContext, timerId)
            }

            TimerAlarmScheduler.ACTION_TIMER_NOTIFICATION -> {
                if (segments.size != 4) return
                val timerId = segments[0].takeIf(String::isNotBlank) ?: return
                val action = runCatching { TimerNotificationAction.valueOf(segments[1]) }.getOrNull() ?: return
                val expectedState = runCatching { TimerState.valueOf(segments[2]) }.getOrNull() ?: return
                val expectedGeneration = segments[3].toLongOrNull()?.takeIf { it >= 0L } ?: return
                if (!actionMatchesState(action, expectedState)) return
                IslandRuntime.onTimerNotificationAction(
                    appContext,
                    timerId,
                    action,
                    expectedState,
                    expectedGeneration,
                )
            }
        }
    }

    private fun actionMatchesState(action: TimerNotificationAction, expectedState: TimerState): Boolean =
        when (action) {
            TimerNotificationAction.PAUSE -> expectedState == TimerState.RUNNING
            TimerNotificationAction.RESUME -> expectedState == TimerState.PAUSED
            TimerNotificationAction.CLEAR -> expectedState == TimerState.PAUSED || expectedState == TimerState.FINISHED
        }
}
