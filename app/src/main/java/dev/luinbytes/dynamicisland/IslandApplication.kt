package dev.luinbytes.dynamicisland

import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.Closeable
import java.util.UUID

class IslandApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        IslandRuntime.initialize(this)
    }
}

/** Process owner for app-owned sources and user-enabled presentation. */
internal object IslandRuntime {
    private const val PREFS = "island_settings_v1"
    private const val KEY_OVERLAY = "overlay_enabled"
    private const val KEY_MEDIA = "media_enabled"
    private const val KEY_MEDIA_PACKAGES = "media_packages"
    private const val TIMER_PUBLISHER = "dev.luinbytes.dynamicisland"

    private lateinit var appContext: Context
    private lateinit var timersRepository: AppTimerRepository
    private var timerSubscription: Closeable? = null
    private lateinit var deviceSignals: DeviceSignals
    private val timerRevisions = HashMap<String, Long>()
    private var previousTimerIds = emptySet<String>()
    private var previousTimerStates = emptyMap<String, TimerState>()
    private var initialized = false
    private var screenInteractive = true
    private var mediaAdapter: MediaSourceAdapter? = null
    private val mediaIds = HashMap<MediaSession.Token, String>()
    private val mediaRevisions = HashMap<String, Long>()
    private val mediaStarted = HashMap<String, Long>()
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenInteractive = intent.action != Intent.ACTION_SCREEN_OFF
            syncOverlay()
        }
    }

    var timerSnapshots by mutableStateOf<List<TimerSnapshot>>(emptyList())
        private set
    var alarmState by mutableStateOf<AlarmState?>(null)
        private set
    var signalSnapshot by mutableStateOf(DeviceSignalSnapshot())
        private set
    var overlayEnabled by mutableStateOf(false)
        private set
    var mediaEnabled by mutableStateOf(false)
        private set
    var listenerConnected by mutableStateOf(false)
        private set
    var mediaAccess by mutableStateOf(MediaAccessState.STOPPED)
        private set
    var mediaSessions by mutableStateOf<List<MediaSessionFacts>>(emptyList())
        private set
    var knownMediaPackages by mutableStateOf<Set<String>>(emptySet())
        private set
    var allowedMediaPackages by mutableStateOf<Set<String>>(emptySet())
        private set

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        overlayEnabled = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_OVERLAY, false)
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        mediaEnabled = prefs.getBoolean(KEY_MEDIA, false)
        allowedMediaPackages = prefs.getStringSet(KEY_MEDIA_PACKAGES, emptySet())?.toSet().orEmpty()
        screenInteractive = appContext.getSystemService(PowerManager::class.java).isInteractive
        appContext.registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        timersRepository = AppTimerRepository(appContext)
        timerSubscription = timersRepository.observe(::onTimersChanged)
        deviceSignals = DeviceSignals(appContext) { signalSnapshot = it }
        deviceSignals.start()
    }

    fun startTimer(durationMillis: Long, label: String = "Timer"): String =
        timersRepository.start(durationMillis, label)

    fun pauseTimer(id: String): Boolean = timersRepository.pause(id)

    fun resumeTimer(id: String): Boolean = timersRepository.resume(id)

    fun cancelTimer(id: String): Boolean = timersRepository.cancel(id)

    fun reconcileTimerAlarms() {
        alarmState = TimerAlarmScheduler.reconcile(appContext, timersRepository.snapshot())
    }

    fun onTimerAlarm(context: Context, timerId: String) {
        val snapshots = timersRepository.snapshot()
        alarmState = TimerAlarmScheduler.reconcile(context, snapshots)
        snapshots.firstOrNull { it.id == timerId && it.state == TimerState.FINISHED }
            ?.let { TimerAlarmScheduler.postCompletion(context, it) }
    }

    fun onTimerNotificationAction(
        context: Context,
        timerId: String,
        action: TimerNotificationAction,
        expectedState: TimerState,
    ): Boolean {
        val current = timersRepository.snapshot().firstOrNull { it.id == timerId }
        if (current?.state != expectedState) {
            alarmState = TimerAlarmScheduler.reconcile(context, timersRepository.snapshot())
            return false
        }
        return when (action) {
            TimerNotificationAction.PAUSE ->
                expectedState == TimerState.RUNNING && timersRepository.pause(timerId)
            TimerNotificationAction.RESUME ->
                expectedState == TimerState.PAUSED && timersRepository.resume(timerId)
            TimerNotificationAction.CLEAR ->
                expectedState in setOf(TimerState.PAUSED, TimerState.FINISHED) && timersRepository.cancel(timerId)
        }
    }

    fun changeOverlayEnabled(enabled: Boolean): String? {
        overlayEnabled = enabled
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_OVERLAY, enabled).apply()
        if (!enabled) {
            OverlayController.stop()
            return null
        }
        return syncOverlay()
    }

    fun refresh(): String? = syncOverlay()

    fun reconcileMedia() {
        if (mediaEnabled && listenerConnected) mediaAdapter?.reconcile()
    }

    fun changeMediaEnabled(enabled: Boolean) {
        mediaEnabled = enabled
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_MEDIA, enabled).apply()
        if (enabled && listenerConnected) ensureMediaAdapter().start()
        if (!enabled) {
            mediaAdapter?.stop()
            clearMediaSources(terminal = true)
            mediaSessions = emptyList()
            knownMediaPackages = emptySet()
            mediaAccess = MediaAccessState.STOPPED
        }
    }

    fun setMediaPackageAllowed(packageName: String, allowed: Boolean) {
        val next = allowedMediaPackages.toMutableSet()
        if (allowed) next += packageName else next -= packageName
        allowedMediaPackages = next.toSet()
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_MEDIA_PACKAGES, next).apply()
        if (mediaEnabled && listenerConnected) onMediaSnapshot(mediaSessions, mediaAccess)
    }

    fun sendMediaAction(token: MediaSession.Token, action: MediaTransportAction): Boolean {
        if (!mediaEnabled || !listenerConnected || mediaAccess != MediaAccessState.AVAILABLE) return false
        val current = mediaSessions.firstOrNull { it.token == token } ?: return false
        if (current.packageName !in allowedMediaPackages || action !in current.playbackActions) return false
        return mediaAdapter?.send(token, action) == true
    }

    fun onListenerConnection(connected: Boolean) {
        listenerConnected = connected
        if (connected && mediaEnabled) {
            ensureMediaAdapter().start()
        } else {
            mediaAdapter?.stop()
            if (!connected && mediaEnabled) {
                clearMediaSources(terminal = false)
                mediaSessions = emptyList()
                knownMediaPackages = emptySet()
                mediaAccess = MediaAccessState.UNAVAILABLE
            }
        }
    }

    private fun ensureMediaAdapter(): MediaSourceAdapter {
        val existing = mediaAdapter
        if (existing != null) return existing
        return MediaSourceAdapter(
            appContext,
            ComponentName(appContext, IslandNotificationListener::class.java),
            object : MediaSourceAdapter.Listener {
                override fun onSnapshot(sessions: List<MediaSessionFacts>, access: MediaAccessState) {
                    onMediaSnapshot(sessions, access)
                }
            },
        ).also { mediaAdapter = it }
    }

    private fun onMediaSnapshot(sessions: List<MediaSessionFacts>, access: MediaAccessState) {
        if (!mediaEnabled) return
        if (!listenerConnected) {
            mediaSessions = emptyList()
            knownMediaPackages = emptySet()
            mediaAccess = MediaAccessState.UNAVAILABLE
            clearMediaSources(terminal = false)
            return
        }
        mediaAccess = access
        mediaSessions = if (access == MediaAccessState.AVAILABLE) sessions else emptyList()
        knownMediaPackages = knownMediaPackages + sessions.map { it.packageName }
        if (access != MediaAccessState.AVAILABLE) {
            knownMediaPackages = emptySet()
            clearMediaSources(terminal = false)
            return
        }

        val now = SystemClock.elapsedRealtime()
        val eligible = sessions.filter { facts ->
            facts.packageName in allowedMediaPackages &&
                facts.playbackState != null &&
                facts.playbackState !in setOf(PlaybackState.STATE_NONE, PlaybackState.STATE_STOPPED, PlaybackState.STATE_ERROR)
        }
        val tokens = eligible.mapTo(HashSet()) { it.token }
        for (token in mediaIds.keys.toList()) {
            if (token !in tokens) removeMediaSource(token)
        }
        for (facts in eligible) {
            val id = mediaIds.getOrPut(facts.token) { "media:${UUID.randomUUID()}" }
            val revision = (mediaRevisions[id] ?: 0L) + 1L
            mediaRevisions[id] = revision
            val started = mediaStarted.getOrPut(id) { now }
            val title = facts.displayTitle ?: facts.title ?: facts.packageName
            val detail = facts.artist ?: when (facts.playbackState) {
                PlaybackState.STATE_PLAYING -> "Playing"
                PlaybackState.STATE_PAUSED -> "Paused"
                PlaybackState.STATE_BUFFERING -> "Buffering"
                else -> "Media session"
            }
            IslandStateEngine.upsert(
                IslandSource(
                    id = id,
                    kind = IslandSourceKind.MEDIA,
                    lifecycle = IslandLifecycle.ACTIVE,
                    revision = revision,
                    publisherId = facts.packageName,
                    startedAtMillis = started,
                    updatedAtMillis = now,
                    title = title,
                    detail = detail,
                ),
            )
        }
        syncOverlay()
    }

    private fun clearMediaSources(terminal: Boolean) {
        for (token in mediaIds.keys.toList()) {
            if (terminal) removeMediaSource(token)
            else {
                val id = mediaIds[token] ?: continue
                val revision = (mediaRevisions[id] ?: 0L) + 1L
                mediaRevisions[id] = revision
                IslandStateEngine.transition(id, revision, IslandLifecycle.UNAVAILABLE)
            }
        }
        syncOverlay()
    }

    private fun removeMediaSource(token: MediaSession.Token) {
        val id = mediaIds.remove(token) ?: return
        val revision = (mediaRevisions.remove(id) ?: 0L) + 1L
        mediaStarted.remove(id)
        IslandStateEngine.remove(id, revision)
    }

    private fun onTimersChanged(timers: List<TimerSnapshot>) {
        timerSnapshots = timers
        val currentStates = timers.associate { it.id to it.state }
        if (currentStates != previousTimerStates) {
            alarmState = TimerAlarmScheduler.reconcile(appContext, timers)
            timers.filter { it.state == TimerState.FINISHED }
                .forEach { TimerAlarmScheduler.postCompletion(appContext, it) }
            previousTimerStates = currentStates
        }
        val now = SystemClock.elapsedRealtime()
        val ids = timers.mapTo(HashSet()) { it.id }
        for (timer in timers) {
            val revision = (timerRevisions[timer.id] ?: 0L) + 1L
            timerRevisions[timer.id] = revision
            val remaining = formatDuration(timer.remainingMillis)
            val detail = when (timer.state) {
                TimerState.RUNNING -> "$remaining remaining"
                TimerState.PAUSED -> "$remaining paused"
                TimerState.FINISHED -> "Timer complete"
            }
            IslandStateEngine.upsert(
                IslandSource(
                    id = "timer:${timer.id}",
                    kind = IslandSourceKind.TIMER,
                    lifecycle = IslandLifecycle.ACTIVE,
                    revision = revision,
                    publisherId = TIMER_PUBLISHER,
                    startedAtMillis = timer.createdOrder,
                    updatedAtMillis = now,
                    title = if (timer.label == "Timer") "Timer ${timer.createdOrder}" else timer.label,
                    detail = detail,
                ),
            )
        }
        for (removed in previousTimerIds - ids) {
            val revision = (timerRevisions.remove(removed) ?: 0L) + 1L
            IslandStateEngine.remove("timer:$removed", revision)
        }
        previousTimerIds = ids
        syncOverlay()
    }

    private fun syncOverlay(): String? {
        val deviceLocked = appContext.getSystemService(KeyguardManager::class.java).isDeviceLocked
        if (!overlayEnabled || !screenInteractive || deviceLocked) {
            OverlayController.stop()
            return null
        }
        if (OverlayController.running) {
            OverlayController.refresh(appContext)
            return null
        }
        if (IslandStateEngine.snapshot.selectedId == null) return null
        return OverlayController.show(appContext)
    }

    private fun formatDuration(millis: Long): String {
        val seconds = (millis + 999L) / 1_000L
        val hours = seconds / 3_600L
        val minutes = (seconds % 3_600L) / 60L
        val rest = seconds % 60L
        return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, rest)
        else "%02d:%02d".format(minutes, rest)
    }
}
