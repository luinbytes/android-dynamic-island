package dev.luinbytes.dynamicisland

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.annotation.MainThread
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.util.Collections
import java.util.UUID

/** Lifecycle for one app-owned countdown. Finished timers remain until explicitly cancelled. */
enum class TimerState {
    RUNNING,
    PAUSED,
    FINISHED,
}

/** Immutable view of a timer. [createdOrder] is stable across process restarts and device reboots. */
data class TimerSnapshot(
    val id: String,
    val label: String,
    val durationMillis: Long,
    val remainingMillis: Long,
    val state: TimerState,
    val createdOrder: Long,
)

/**
 * Main-thread-confined storage and clock for timers owned by this app.
 *
 * Deadlines use [SystemClock.elapsedRealtime], so UI updates and process restarts on the same boot
 * reconcile against real elapsed time instead of counting Handler callbacks. One main Handler
 * runnable is scheduled while at least one timer is running. State is stored in app-private
 * SharedPreferences. User actions use a synchronous preference commit; timer checkpoints use
 * asynchronous writes. If the device reboots, an active timer resumes from its last persisted
 * remaining-time checkpoint; powered-off time is not inferred from the adjustable wall clock.
 *
 * This repository does not keep the process alive, schedule alarms, post notifications, or request
 * permissions. If Android stops the process, no completion event is delivered until the app opens
 * again. Reboot recovery is conservative and may extend a timer by up to the last checkpoint gap.
 */
class AppTimerRepository(context: Context) : Closeable {
    private data class MutableTimer(
        val id: String,
        val label: String,
        val durationMillis: Long,
        val createdOrder: Long,
        var state: TimerState,
        var remainingMillis: Long,
        var deadlineElapsedMillis: Long,
    )

    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val timers = LinkedHashMap<String, MutableTimer>()
    private val observers = LinkedHashSet<(List<TimerSnapshot>) -> Unit>()
    private var nextCreatedOrder = 1L
    private var tickerPosted = false
    private var closed = false

    private val ticker = object : Runnable {
        override fun run() {
            tickerPosted = false
            if (closed) return

            val now = SystemClock.elapsedRealtime()
            refreshExpiredTimers(now)
            persist(now, durable = false)
            publish(now)
            updateTickerState()
        }
    }

    init {
        checkMainThread()
        val restored = restore()
        val now = SystemClock.elapsedRealtime()
        val expired = refreshExpiredTimers(now)
        if (restored || expired) {
            check(persist(now, durable = true)) { "Could not durably reconcile app timers" }
        }
        updateTickerState()
    }

    /** Creates and starts a timer, returning its stable ID. */
    @MainThread
    fun start(
        durationMillis: Long,
        label: String = "Timer",
        id: String = UUID.randomUUID().toString(),
    ): String {
        checkUsable()
        require(durationMillis > 0L) { "durationMillis must be positive" }
        require(id.isNotBlank()) { "id must not be blank" }
        require(id !in timers) { "A timer with id '$id' already exists" }

        val now = SystemClock.elapsedRealtime()
        refreshExpiredTimers(now)
        check(durationMillis <= Long.MAX_VALUE - now) { "durationMillis exceeds the monotonic clock range" }
        check(nextCreatedOrder < Long.MAX_VALUE) { "Timer creation order is exhausted" }
        val normalizedLabel = label.trim().ifEmpty { "Timer" }
        val timer = MutableTimer(
            id = id,
            label = normalizedLabel,
            durationMillis = durationMillis,
            createdOrder = nextCreatedOrder++,
            state = TimerState.RUNNING,
            remainingMillis = durationMillis,
            deadlineElapsedMillis = now + durationMillis,
        )
        timers[id] = timer
        commitAndPublish(now)
        return id
    }

    /** Freezes the current remaining duration. Returns false if the timer is absent or not running. */
    @MainThread
    fun pause(id: String): Boolean {
        checkUsable()
        val now = SystemClock.elapsedRealtime()
        val expired = refreshExpiredTimers(now)
        val timer = timers[id]
        if (timer == null || timer.state != TimerState.RUNNING) {
            if (expired) commitAndPublish(now) else updateTickerState()
            return false
        }

        timer.remainingMillis = remainingAt(timer, now)
        timer.deadlineElapsedMillis = NO_DEADLINE
        timer.state = TimerState.PAUSED
        commitAndPublish(now)
        return true
    }

    /** Resumes a paused timer from its remaining duration. Returns false if it cannot be resumed. */
    @MainThread
    fun resume(id: String): Boolean {
        checkUsable()
        val now = SystemClock.elapsedRealtime()
        val expired = refreshExpiredTimers(now)
        val timer = timers[id]
        if (timer == null || timer.state != TimerState.PAUSED) {
            if (expired) commitAndPublish(now) else updateTickerState()
            return false
        }

        if (timer.remainingMillis <= 0L) {
            timer.remainingMillis = 0L
            timer.state = TimerState.FINISHED
            timer.deadlineElapsedMillis = NO_DEADLINE
        } else {
            check(timer.remainingMillis <= Long.MAX_VALUE - now) {
                "remainingMillis exceeds the monotonic clock range"
            }
            timer.deadlineElapsedMillis = now + timer.remainingMillis
            timer.state = TimerState.RUNNING
        }
        commitAndPublish(now)
        return true
    }

    /** Removes a timer and its persisted state. Returns false if its ID is unknown. */
    @MainThread
    fun cancel(id: String): Boolean {
        checkUsable()
        val now = SystemClock.elapsedRealtime()
        val expired = refreshExpiredTimers(now)
        val removed = timers.remove(id) != null
        if (removed || expired) commitAndPublish(now) else updateTickerState()
        return removed
    }

    /** Returns the current immutable snapshot, reconciling deadlines before returning it. */
    @MainThread
    fun snapshot(): List<TimerSnapshot> {
        checkMainThread()
        val now = SystemClock.elapsedRealtime()
        if (refreshExpiredTimers(now)) commitAndPublish(now)
        return createSnapshot(now)
    }

    /** Registers an observer and emits the current state immediately. Close the result to detach. */
    @MainThread
    fun observe(observer: (List<TimerSnapshot>) -> Unit): Closeable {
        checkUsable()
        observers.add(observer)
        val now = SystemClock.elapsedRealtime()
        if (refreshExpiredTimers(now)) {
            commitAndPublish(now)
        } else {
            try {
                observer(createSnapshot(now))
            } catch (error: RuntimeException) {
                observers.remove(observer)
                throw error
            }
        }
        return Closeable {
            checkMainThread()
            observers.remove(observer)
        }
    }

    /** Stops ticking and persists the current state. Active deadlines remain recoverable. */
    @MainThread
    override fun close() {
        checkMainThread()
        if (closed) return
        val now = SystemClock.elapsedRealtime()
        refreshExpiredTimers(now)
        check(persist(now, durable = true)) { "Could not durably persist app timers" }
        closed = true
        handler.removeCallbacks(ticker)
        tickerPosted = false
        observers.clear()
    }

    private fun restore(): Boolean {
        val encoded = preferences.getString(KEY_STATE, null) ?: return false
        val nowElapsed = SystemClock.elapsedRealtime()
        val currentBootCount = readBootCount()
        try {
            val root = JSONObject(encoded)
            if (root.optInt(JSON_VERSION, 0) != FORMAT_VERSION) return false
            nextCreatedOrder = maxOf(1L, root.optLong(JSON_NEXT_ORDER, 1L))
            val storedTimers = root.optJSONArray(JSON_TIMERS) ?: JSONArray()
            for (index in 0 until storedTimers.length()) {
                val item = storedTimers.optJSONObject(index) ?: continue
                val id = item.optString(JSON_ID).takeIf(String::isNotBlank) ?: continue
                if (id in timers) continue

                val duration = item.optLong(JSON_DURATION, 0L)
                if (duration <= 0L) continue
                val state = runCatching {
                    TimerState.valueOf(item.optString(JSON_STATE))
                }.getOrNull() ?: continue
                val createdOrder = item.optLong(JSON_CREATED_ORDER, nextCreatedOrder)
                if (createdOrder <= 0L) continue
                val afterCreatedOrder = if (createdOrder == Long.MAX_VALUE) {
                    Long.MAX_VALUE
                } else {
                    createdOrder + 1L
                }
                nextCreatedOrder = maxOf(nextCreatedOrder, afterCreatedOrder)
                val remaining = item.optLong(JSON_REMAINING, duration).coerceIn(0L, duration)
                val deadline = item.optLong(JSON_DEADLINE, NO_DEADLINE)
                val checkpointElapsed = item.optLong(JSON_CHECKPOINT_ELAPSED, 0L)
                val savedBootCount = item.optInt(JSON_BOOT_COUNT, UNKNOWN_BOOT_COUNT)

                val timer = MutableTimer(
                    id = id,
                    label = item.optString(JSON_LABEL).trim().ifEmpty { "Timer" },
                    durationMillis = duration,
                    createdOrder = createdOrder,
                    state = state,
                    remainingMillis = remaining,
                    deadlineElapsedMillis = NO_DEADLINE,
                )
                if (state == TimerState.RUNNING) {
                    if (isSameBoot(savedBootCount, currentBootCount, checkpointElapsed, nowElapsed) && deadline >= 0L) {
                        timer.deadlineElapsedMillis = deadline
                        timer.remainingMillis = remainingAt(timer, nowElapsed)
                    } else if (isSameBoot(savedBootCount, currentBootCount, checkpointElapsed, nowElapsed)) {
                        // An invalid/missing monotonic deadline is not proof that this timer ended.
                        timer.state = TimerState.PAUSED
                    } else if (remaining > 0L && remaining <= Long.MAX_VALUE - nowElapsed) {
                        // Do not compare wall time across boots: a manual clock change could finish
                        // a timer that had not actually elapsed.
                        timer.deadlineElapsedMillis = nowElapsed + remaining
                    } else {
                        // The saved checkpoint reached zero during a boot transition. Keep that
                        // uncertain state paused so a reset cannot manufacture a completion.
                        timer.state = TimerState.PAUSED
                        timer.remainingMillis = 0L
                    }
                }
                timers[id] = timer
            }
            return true
        } catch (_: Exception) {
            timers.clear()
            nextCreatedOrder = 1L
            preferences.edit().remove(KEY_STATE).apply()
            return false
        }
    }

    private fun isSameBoot(
        savedBootCount: Int,
        currentBootCount: Int,
        checkpointElapsed: Long,
        nowElapsed: Long,
    ): Boolean = when {
        savedBootCount != UNKNOWN_BOOT_COUNT && currentBootCount != UNKNOWN_BOOT_COUNT ->
            savedBootCount == currentBootCount
        else -> nowElapsed >= checkpointElapsed
    }

    private fun refreshExpiredTimers(nowElapsed: Long): Boolean {
        var changed = false
        for (timer in timers.values) {
            if (timer.state == TimerState.RUNNING && timer.deadlineElapsedMillis <= nowElapsed) {
                timer.remainingMillis = 0L
                timer.deadlineElapsedMillis = NO_DEADLINE
                timer.state = TimerState.FINISHED
                changed = true
            }
        }
        return changed
    }

    private fun commitAndPublish(nowElapsed: Long) {
        if (!persist(nowElapsed, durable = true)) {
            // Do not let a later ticker write make a failed user action appear successful.
            timers.clear()
            nextCreatedOrder = 1L
            restore()
            refreshExpiredTimers(nowElapsed)
            updateTickerState()
            throw IllegalStateException("Could not durably persist app timers")
        }
        publish(nowElapsed)
        updateTickerState()
    }

    private fun persist(nowElapsed: Long, durable: Boolean): Boolean {
        val root = JSONObject()
            .put(JSON_VERSION, FORMAT_VERSION)
            .put(JSON_NEXT_ORDER, nextCreatedOrder)
        val items = JSONArray()
        val bootCount = readBootCount()
        for (timer in timers.values) {
            val remaining = when (timer.state) {
                TimerState.RUNNING -> remainingAt(timer, nowElapsed)
                TimerState.PAUSED -> timer.remainingMillis
                TimerState.FINISHED -> 0L
            }
            timer.remainingMillis = remaining
            items.put(
                JSONObject()
                    .put(JSON_ID, timer.id)
                    .put(JSON_LABEL, timer.label)
                    .put(JSON_DURATION, timer.durationMillis)
                    .put(JSON_CREATED_ORDER, timer.createdOrder)
                    .put(JSON_STATE, timer.state.name)
                    .put(JSON_REMAINING, remaining)
                    .put(JSON_DEADLINE, timer.deadlineElapsedMillis)
                    .put(JSON_CHECKPOINT_ELAPSED, nowElapsed)
                    .put(JSON_BOOT_COUNT, bootCount),
            )
        }
        root.put(JSON_TIMERS, items)
        val editor = preferences.edit().putString(KEY_STATE, root.toString())
        return if (durable) editor.commit() else {
            editor.apply()
            true
        }
    }

    private fun publish(nowElapsed: Long) {
        if (observers.isEmpty()) return
        val current = createSnapshot(nowElapsed)
        for (observer in observers.toList()) {
            try {
                observer(current)
            } catch (_: RuntimeException) {
                // One faulty consumer must not prevent other consumers or the ticker from running.
            }
        }
    }

    private fun createSnapshot(nowElapsed: Long): List<TimerSnapshot> {
        val values = timers.values
            .sortedBy(MutableTimer::createdOrder)
            .map { timer ->
                TimerSnapshot(
                    id = timer.id,
                    label = timer.label,
                    durationMillis = timer.durationMillis,
                    remainingMillis = when (timer.state) {
                        TimerState.RUNNING -> remainingAt(timer, nowElapsed)
                        TimerState.PAUSED -> timer.remainingMillis
                        TimerState.FINISHED -> 0L
                    },
                    state = timer.state,
                    createdOrder = timer.createdOrder,
                )
            }
        return Collections.unmodifiableList(values)
    }

    private fun remainingAt(timer: MutableTimer, nowElapsed: Long): Long =
        (timer.deadlineElapsedMillis - nowElapsed).coerceAtLeast(0L)

    private fun updateTickerState() {
        if (closed) return
        val hasRunningTimer = timers.values.any { it.state == TimerState.RUNNING }
        if (hasRunningTimer && !tickerPosted) {
            tickerPosted = true
            handler.postDelayed(ticker, TICK_INTERVAL_MILLIS)
        } else if (!hasRunningTimer && tickerPosted) {
            handler.removeCallbacks(ticker)
            tickerPosted = false
        }
    }

    private fun readBootCount(): Int = try {
        Settings.Global.getInt(
            appContext.contentResolver,
            Settings.Global.BOOT_COUNT,
            UNKNOWN_BOOT_COUNT,
        )
    } catch (_: RuntimeException) {
        UNKNOWN_BOOT_COUNT
    }

    private fun checkUsable() {
        checkMainThread()
        check(!closed) { "AppTimerRepository is closed" }
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "AppTimerRepository must be used from the main thread"
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "app_owned_timers_v1"
        const val KEY_STATE = "state"
        const val FORMAT_VERSION = 1
        const val TICK_INTERVAL_MILLIS = 1_000L
        const val UNKNOWN_BOOT_COUNT = -1
        const val NO_DEADLINE = -1L

        const val JSON_VERSION = "version"
        const val JSON_NEXT_ORDER = "nextCreatedOrder"
        const val JSON_TIMERS = "timers"
        const val JSON_ID = "id"
        const val JSON_LABEL = "label"
        const val JSON_DURATION = "durationMillis"
        const val JSON_CREATED_ORDER = "createdOrder"
        const val JSON_STATE = "state"
        const val JSON_REMAINING = "remainingMillis"
        const val JSON_DEADLINE = "deadlineElapsedMillis"
        const val JSON_CHECKPOINT_ELAPSED = "checkpointElapsedMillis"
        const val JSON_BOOT_COUNT = "bootCount"
    }
}
