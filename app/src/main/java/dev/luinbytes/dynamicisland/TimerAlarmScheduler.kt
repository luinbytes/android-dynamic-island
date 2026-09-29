package dev.luinbytes.dynamicisland

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/** Current scheduling and notification capability reported by [TimerAlarmScheduler.reconcile]. */
data class AlarmState(
    val runningTimerCount: Int,
    val scheduledAlarmCount: Int,
    val exactAlarmAccess: Boolean,
    val inexactFallbackUsed: Boolean,
    val notificationsAvailable: Boolean,
    val message: String,
)

/** Notification controls whose state validation is performed by [IslandRuntime]. */
internal enum class TimerNotificationAction {
    PAUSE,
    RESUME,
    CLEAR,
}

/**
 * Schedules app-owned timer completion broadcasts and maintains one notification per timer.
 *
 * API 31+ uses exact alarms only while [AlarmManager.canScheduleExactAlarms] is true. Otherwise
 * this uses [AlarmManager.setAndAllowWhileIdle], which is inexact and may be delayed by doze or
 * system alarm limits. Notifications use a low-importance channel and never change an existing
 * channel's settings. This class only reads permission state; it never requests permissions.
 */
object TimerAlarmScheduler {
    private data class SchedulerRecord(
        val knownOrders: MutableMap<String, Long> = linkedMapOf(),
        val completionAttempts: MutableSet<Long> = linkedSetOf(),
    )

    private data class NotificationAccess(
        val manager: NotificationManager?,
        val available: Boolean,
        val reason: String?,
    )

    private val lock = Any()

    /** Reconciles all pending alarms and timer notifications against a repository snapshot. */
    fun reconcile(context: Context, snapshots: List<TimerSnapshot>): AlarmState = synchronized(lock) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(AlarmManager::class.java)
        val notificationAccess = notificationAccess(appContext)
        val record = readRecord(appContext)
        val byId = snapshots.associateBy(TimerSnapshot::id)
        val currentOrders = byId.mapValues { (_, snapshot) -> snapshot.createdOrder }
        val removedIds = record.knownOrders.keys - currentOrders.keys
        for (id in removedIds) {
            cancelAlarm(appContext, alarmManager, id)
            record.knownOrders[id]?.let { cancelTimerNotification(notificationAccess.manager, it) }
            record.knownOrders.remove(id)
        }

        val exactAccess = alarmManager != null && canUseExactAlarms(alarmManager)
        var exactAllowedForCalls = exactAccess
        var fallbackUsed = false
        var scheduled = 0
        var notificationsFailed = false
        val running = snapshots.count { it.state == TimerState.RUNNING }

        for (timer in snapshots) {
            when (timer.state) {
                TimerState.RUNNING -> {
                    if (timer.remainingMillis <= 0L) {
                        cancelAlarm(appContext, alarmManager, timer.id)
                        cancelTimerNotification(notificationAccess.manager, timer.createdOrder)
                        continue
                    }
                    if (alarmManager != null) {
                        try {
                            when (scheduleAlarm(appContext, alarmManager, timer, exactAllowedForCalls)) {
                                AlarmDelivery.EXACT -> scheduled++
                                AlarmDelivery.INEXACT -> {
                                    scheduled++
                                    fallbackUsed = true
                                    exactAllowedForCalls = false
                                }
                            }
                        } catch (_: RuntimeException) {
                            exactAllowedForCalls = false
                        }
                    } else {
                        exactAllowedForCalls = false
                    }
                    if (notificationAccess.available && notificationAccess.manager != null) {
                        if (!postRunningNotification(appContext, notificationAccess.manager, timer)) {
                            notificationsFailed = true
                        }
                    }
                }

                TimerState.PAUSED -> {
                    cancelAlarm(appContext, alarmManager, timer.id)
                    if (notificationAccess.available && notificationAccess.manager != null) {
                        if (!postPausedNotification(appContext, notificationAccess.manager, timer)) {
                            notificationsFailed = true
                        }
                    }
                }

                TimerState.FINISHED -> {
                    cancelAlarm(appContext, alarmManager, timer.id)
                    if (timer.createdOrder !in record.completionAttempts) {
                        cancelTimerNotification(notificationAccess.manager, timer.createdOrder)
                    } else {
                        val manager = notificationAccess.manager
                        if (
                            notificationAccess.available && manager != null &&
                            hasActiveTimerNotification(manager, timer.createdOrder)
                        ) {
                            // Replace any pre-generation Clear action on an existing completion
                            // notification. Only update notifications Android still considers active:
                            // a dismissed completion must not be resurrected by reconciliation.
                            if (!postCompletionNotification(appContext, manager, timer)) {
                                notificationsFailed = true
                            }
                        }
                    }
                }
            }
        }

        record.knownOrders.clear()
        record.knownOrders.putAll(currentOrders)
        val retainedOrders = currentOrders.values.toSet()
        record.completionAttempts.retainAll(retainedOrders)
        val recordStored = writeRecord(appContext, record)

        val messages = buildList {
            if (running == 0) add("No running timers")
            else if (scheduled < running) add("Some timer alarms could not be scheduled")
            if (fallbackUsed) add("Exact alarms unavailable; inexact delivery may be delayed")
            if (!notificationAccess.available) add(notificationAccess.reason ?: "Timer notifications unavailable")
            if (notificationsFailed) add("A timer notification could not be posted")
            if (!recordStored) add("Timer alarm identities could not be persisted")
            if (isEmpty()) add("Exact timer alarms scheduled")
        }
        AlarmState(
            runningTimerCount = running,
            scheduledAlarmCount = scheduled,
            exactAlarmAccess = exactAccess,
            inexactFallbackUsed = fallbackUsed,
            notificationsAvailable = notificationAccess.available && !notificationsFailed,
            message = messages.joinToString(" · "),
        )
    }

    /**
     * Posts a truthful finished notification once per persisted timer identity. Safe for both the
     * receiver and the in-process timer-state transition to call; duplicate callers are deduped.
     */
    internal fun postCompletion(context: Context, timer: TimerSnapshot): Boolean = synchronized(lock) {
        if (timer.state != TimerState.FINISHED || timer.createdOrder <= 0L) return@synchronized false
        val appContext = context.applicationContext
        val record = readRecord(appContext)
        if (timer.createdOrder in record.completionAttempts) return@synchronized false

        val access = notificationAccess(appContext)
        record.completionAttempts.add(timer.createdOrder)
        record.knownOrders[timer.id] = timer.createdOrder
        val posted = if (access.available && access.manager != null) {
            postCompletionNotification(appContext, access.manager, timer)
        } else {
            false
        }
        writeRecord(appContext, record)
        posted
    }

    internal fun cancelAlarm(context: Context, timerId: String) {
        cancelAlarm(
            context.applicationContext,
            context.applicationContext.getSystemService(AlarmManager::class.java),
            timerId,
        )
    }

    private enum class AlarmDelivery { EXACT, INEXACT }

    private fun scheduleAlarm(
        context: Context,
        manager: AlarmManager,
        timer: TimerSnapshot,
        exactAllowed: Boolean,
    ): AlarmDelivery {
        val pendingIntent = checkNotNull(timerPendingIntent(context, timer.id, create = true))
        val nowElapsed = SystemClock.elapsedRealtime()
        val remaining = timer.remainingMillis.coerceAtLeast(1L)
        val trigger = if (remaining > Long.MAX_VALUE - nowElapsed) Long.MAX_VALUE else nowElapsed + remaining
        if (exactAllowed) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pendingIntent)
                return AlarmDelivery.EXACT
            } catch (_: RuntimeException) {
                // Exact-alarm access can change between the capability check and scheduling.
            }
        }
        manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pendingIntent)
        return AlarmDelivery.INEXACT
    }

    private fun canUseExactAlarms(manager: AlarmManager): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            true
        } else {
            try {
                manager.canScheduleExactAlarms()
            } catch (_: RuntimeException) {
                false
            }
        }

    private fun cancelAlarm(context: Context, manager: AlarmManager?, timerId: String) {
        val pendingIntent = timerPendingIntent(context, timerId, create = false) ?: return
        manager?.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun timerPendingIntent(context: Context, timerId: String, create: Boolean): PendingIntent? {
        val data = Uri.Builder()
            .scheme("dynamicisland")
            .authority("timer")
            .appendPath(timerId)
            .build()
        val intent = Intent(context, TimerAlarmReceiver::class.java)
            .setAction(ACTION_TIMER_ALARM)
            .setData(data)
        val flags = PendingIntent.FLAG_IMMUTABLE or if (create) {
            PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_NO_CREATE
        }
        return PendingIntent.getBroadcast(context, timerId.hashCode(), intent, flags)
    }

    private fun notificationAccess(context: Context): NotificationAccess {
        val manager = context.getSystemService(NotificationManager::class.java)
            ?: return NotificationAccess(null, false, "Notification service unavailable")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return NotificationAccess(manager, false, "Notification permission is not granted")
        }
        if (!manager.areNotificationsEnabled()) {
            return NotificationAccess(manager, false, "App notifications are disabled")
        }
        var channel = manager.getNotificationChannel(CHANNEL_ID)
        if (channel == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW),
            )
            channel = manager.getNotificationChannel(CHANNEL_ID)
        }
        if (channel == null || channel.importance == NotificationManager.IMPORTANCE_NONE) {
            return NotificationAccess(manager, false, "Timer notification channel is disabled")
        }
        return NotificationAccess(manager, true, null)
    }

    private fun postRunningNotification(
        context: Context,
        manager: NotificationManager,
        timer: TimerSnapshot,
    ): Boolean {
        val deadlineWall = safeAdd(System.currentTimeMillis(), timer.remainingMillis.coerceAtLeast(0L))
        val notification = baseBuilder(context, timer)
            .setContentTitle(timer.label)
            .setContentText("Timer running")
            .setWhen(deadlineWall)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(notificationAction(context, timer, "Pause", TimerNotificationAction.PAUSE, TimerState.RUNNING))
            .build()
        return notify(manager, timer.createdOrder, notification)
    }

    private fun postPausedNotification(
        context: Context,
        manager: NotificationManager,
        timer: TimerSnapshot,
    ): Boolean {
        val notification = baseBuilder(context, timer)
            .setContentTitle("${timer.label} paused")
            .setContentText("${formatDuration(timer.remainingMillis)} remaining")
            .setShowWhen(false)
            .setUsesChronometer(false)
            .setOngoing(false)
            .setAutoCancel(true)
            .addAction(notificationAction(context, timer, "Resume", TimerNotificationAction.RESUME, TimerState.PAUSED))
            .addAction(notificationAction(context, timer, "Clear", TimerNotificationAction.CLEAR, TimerState.PAUSED))
            .build()
        return notify(manager, timer.createdOrder, notification)
    }

    private fun postCompletionNotification(
        context: Context,
        manager: NotificationManager,
        timer: TimerSnapshot,
    ): Boolean {
        val notification = baseBuilder(context, timer)
            .setContentTitle("Timer finished")
            .setContentText(timer.label)
            .setShowWhen(false)
            .setUsesChronometer(false)
            .setOngoing(false)
            .setAutoCancel(true)
            .addAction(notificationAction(context, timer, "Clear", TimerNotificationAction.CLEAR, TimerState.FINISHED))
            .build()
        return notify(manager, timer.createdOrder, notification)
    }

    private fun baseBuilder(context: Context, timer: TimerSnapshot): Notification.Builder =
        Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_island)
            .setContentIntent(contentIntent(context, timer))
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)

    private fun contentIntent(context: Context, timer: TimerSnapshot): PendingIntent {
        val data = Uri.Builder()
            .scheme("dynamicisland")
            .authority("timer-details")
            .appendPath(timer.id)
            .build()
        val intent = Intent(context, MainActivity::class.java).setData(data)
        return PendingIntent.getActivity(
            context,
            notificationId(timer.createdOrder),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun notificationAction(
        context: Context,
        timer: TimerSnapshot,
        title: String,
        action: TimerNotificationAction,
        expectedState: TimerState,
    ): Notification.Action = Notification.Action.Builder(
        Icon.createWithResource(context, R.drawable.ic_island),
        title,
        notificationActionIntent(context, timer, action, expectedState),
    ).build()

    private fun notificationActionIntent(
        context: Context,
        timer: TimerSnapshot,
        action: TimerNotificationAction,
        expectedState: TimerState,
    ): PendingIntent {
        val data = Uri.Builder()
            .scheme("dynamicisland")
            .authority("timer-action")
            .appendPath(timer.id)
            .appendPath(action.name)
            .appendPath(expectedState.name)
            .appendPath(timer.actionGeneration.toString())
            .build()
        val intent = Intent(context, TimerAlarmReceiver::class.java)
            .setAction(ACTION_TIMER_NOTIFICATION)
            .setData(data)
        val requestCode = (((timer.id.hashCode() * 31 + action.ordinal) * 31 + expectedState.ordinal) * 31) +
            timer.actionGeneration.hashCode()
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun notify(manager: NotificationManager, createdOrder: Long, notification: Notification): Boolean =
        try {
            manager.notify(notificationId(createdOrder), notification)
            true
        } catch (_: RuntimeException) {
            false
        }

    private fun cancelTimerNotification(manager: NotificationManager?, createdOrder: Long) {
        if (manager == null || createdOrder <= 0L) return
        try {
            manager.cancel(notificationId(createdOrder))
        } catch (_: RuntimeException) {
            // The notification may already have been removed by the user or system.
        }
    }

    private fun hasActiveTimerNotification(manager: NotificationManager, createdOrder: Long): Boolean =
        try {
            manager.activeNotifications.any { it.id == notificationId(createdOrder) }
        } catch (_: RuntimeException) {
            false
        }

    private fun notificationId(createdOrder: Long): Int {
        val slot = (createdOrder - 1L) % NOTIFICATION_ID_RANGE
        return NOTIFICATION_ID_BASE + (slot * 2L).toInt()
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

    private fun formatDuration(millis: Long): String {
        val seconds = millis.coerceAtLeast(0L) / 1_000L +
            if (millis > 0L && millis % 1_000L != 0L) 1L else 0L
        val hours = seconds / 3_600L
        val minutes = (seconds % 3_600L) / 60L
        val rest = seconds % 60L
        return if (hours > 0L) "$hours:${minutes.toString().padStart(2, '0')}:${rest.toString().padStart(2, '0')}"
        else "${minutes.toString().padStart(2, '0')}:${rest.toString().padStart(2, '0')}"
    }

    private fun readRecord(context: Context): SchedulerRecord {
        val encoded = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(KEY_RECORD, null) ?: return SchedulerRecord()
        return try {
            val root = JSONObject(encoded)
            val known = root.optJSONObject(JSON_KNOWN_ORDERS) ?: JSONObject()
            val orders = linkedMapOf<String, Long>()
            val keys = known.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val order = known.optLong(id, 0L)
                if (id.isNotBlank() && order > 0L) orders[id] = order
            }
            val completed = root.optJSONArray(JSON_COMPLETION_ATTEMPTS) ?: JSONArray()
            val attempts = linkedSetOf<Long>()
            for (index in 0 until completed.length()) {
                val order = completed.optLong(index, 0L)
                if (order > 0L) attempts += order
            }
            SchedulerRecord(orders, attempts)
        } catch (_: Exception) {
            SchedulerRecord()
        }
    }

    private fun writeRecord(context: Context, record: SchedulerRecord): Boolean {
        val known = JSONObject()
        for ((id, order) in record.knownOrders) known.put(id, order)
        val attempts = JSONArray()
        record.completionAttempts.sorted().forEach(attempts::put)
        val encoded = JSONObject()
            .put(JSON_KNOWN_ORDERS, known)
            .put(JSON_COMPLETION_ATTEMPTS, attempts)
            .toString()
        return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECORD, encoded)
            .commit()
    }

    internal const val ACTION_TIMER_ALARM = "dev.luinbytes.dynamicisland.action.TIMER_ALARM"
    internal const val ACTION_TIMER_NOTIFICATION = "dev.luinbytes.dynamicisland.action.TIMER_NOTIFICATION"
    private const val PREFERENCES_NAME = "timer_alarm_scheduler_v1"
    private const val KEY_RECORD = "record"
    private const val CHANNEL_ID = "app_owned_timers"
    private const val CHANNEL_NAME = "Timers"
    private const val NOTIFICATION_ID_BASE = 500_000_000
    private const val NOTIFICATION_ID_RANGE = (Int.MAX_VALUE.toLong() - NOTIFICATION_ID_BASE) / 2L

    private const val JSON_KNOWN_ORDERS = "knownOrders"
    private const val JSON_COMPLETION_ATTEMPTS = "completionAttempts"
}
