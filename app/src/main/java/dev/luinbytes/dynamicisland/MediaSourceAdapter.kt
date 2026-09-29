package dev.luinbytes.dynamicisland

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import java.util.Collections

/**
 * Observes the media sessions visible to the app's enabled notification listener.
 *
 * This adapter reports the session token and facts actually published by each session. It does
 * not infer a task identity from a package or title or persist publisher content. Calls to [send]
 * recheck the exact active token and advertised action before dispatch. Call [start], [stop], and
 * [reconcile] from any thread; listener callbacks are delivered on the main thread.
 */
internal class MediaSourceAdapter(
    context: Context,
    private val notificationListener: ComponentName,
    private val listener: Listener,
) {
    internal interface Listener {
        /** A complete, priority-ordered snapshot of currently observed session tokens. */
        fun onSnapshot(sessions: List<MediaSessionFacts>, access: MediaAccessState)
    }

    private val manager = context.applicationContext
        .getSystemService(MediaSessionManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessions = LinkedHashMap<MediaSession.Token, SessionRecord>()
    private var activeOrder: List<MediaSession.Token> = emptyList()
    private var started = false
    private var activeListenerRegistered = false
    private var accessState = MediaAccessState.STOPPED

    private val activeSessionsListener =
        object : MediaSessionManager.OnActiveSessionsChangedListener {
            override fun onActiveSessionsChanged(controllers: MutableList<MediaController>?) {
                if (!started) return
                if (controllers == null) {
                    reconcileOnMain()
                    return
                }
                try {
                    updateControllers(controllers)
                    accessState = MediaAccessState.AVAILABLE
                    publishSnapshot()
                } catch (_: SecurityException) {
                    loseAccess()
                }
            }
        }

    /** Starts listening and immediately reconciles against the current active-session list. */
    fun start() = onMain {
        if (started) {
            reconcileOnMain()
            return@onMain
        }
        started = true
        reconcileOnMain()
    }

    /** Stops listening, unregisters every controller callback, and publishes an empty snapshot. */
    fun stop() = onMain {
        started = false
        unregisterActiveListener()
        clearSessions()
        accessState = MediaAccessState.STOPPED
        publishSnapshot()
    }

    /** Re-reads listener access and the active-session list, for example after returning to UI. */
    fun reconcile() = onMain {
        if (started) reconcileOnMain()
    }

    /**
     * Requests one currently advertised standard transport action for the exact active token.
     *
     * This synchronous method must be called on the main thread. It re-queries active sessions,
     * then reads the matching controller's current PlaybackState immediately before dispatch. A
     * `true` result means the command was dispatched to Android/session transport; it does not
     * confirm that the publisher acted on it or that playback changed.
     */
    fun send(token: MediaSession.Token, action: MediaTransportAction): Boolean {
        check(Looper.myLooper() == mainHandler.looper) {
            "Media transport actions must be dispatched on the main thread"
        }
        val record = sessions[token] ?: return false
        if (!started || accessState != MediaAccessState.AVAILABLE) return false
        val actionBit = action.requiredPlaybackActionBit() ?: return false

        return try {
            val activeControllers = manager.getActiveSessions(notificationListener)
            val controller = activeControllers.firstOrNull { it.sessionToken == token }
            if (controller == null) {
                updateControllers(activeControllers)
                accessState = MediaAccessState.AVAILABLE
                publishSnapshot()
                return false
            }
            if (sessions[token] !== record) return false

            val controls = controller.transportControls
            val currentState = controller.playbackState
            if (currentState == null || currentState.actions and actionBit == 0L) {
                refresh(record, controller)
                publishSnapshot()
                return false
            }

            when (action) {
                MediaTransportAction.PLAY -> {
                    controls.play()
                    true
                }
                MediaTransportAction.PAUSE -> {
                    controls.pause()
                    true
                }
                MediaTransportAction.PLAY_PAUSE -> {
                    val downTime = SystemClock.uptimeMillis()
                    val pressed = controller.dispatchMediaButtonEvent(
                        KeyEvent(
                            downTime,
                            downTime,
                            KeyEvent.ACTION_DOWN,
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                            0,
                        ),
                    )
                    val released = controller.dispatchMediaButtonEvent(
                        KeyEvent(
                            downTime,
                            SystemClock.uptimeMillis(),
                            KeyEvent.ACTION_UP,
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                            0,
                        ),
                    )
                    pressed && released
                }
                MediaTransportAction.NEXT -> {
                    controls.skipToNext()
                    true
                }
                MediaTransportAction.PREVIOUS -> {
                    controls.skipToPrevious()
                    true
                }
                else -> false
            }
        } catch (_: SecurityException) {
            loseAccess()
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun reconcileOnMain() {
        if (!started) return
        try {
            registerActiveListenerIfNeeded()
            val controllers = manager.getActiveSessions(notificationListener)
            updateControllers(controllers)
            accessState = MediaAccessState.AVAILABLE
            publishSnapshot()
        } catch (_: SecurityException) {
            loseAccess()
        }
    }

    private fun registerActiveListenerIfNeeded() {
        if (activeListenerRegistered) return
        manager.addOnActiveSessionsChangedListener(
            activeSessionsListener,
            notificationListener,
            mainHandler,
        )
        activeListenerRegistered = true
    }

    private fun unregisterActiveListener() {
        if (!activeListenerRegistered) return
        try {
            manager.removeOnActiveSessionsChangedListener(activeSessionsListener)
        } catch (_: SecurityException) {
            // Access may have been revoked between the last reconcile and cleanup.
        } finally {
            activeListenerRegistered = false
        }
    }

    private fun updateControllers(controllers: List<MediaController>) {
        val uniqueControllers = LinkedHashMap<MediaSession.Token, MediaController>()
        controllers.forEach { controller ->
            uniqueControllers.putIfAbsent(controller.sessionToken, controller)
        }

        val tokens = uniqueControllers.keys.toList()
        val removedTokens = sessions.keys.filterNot(tokens::contains)
        removedTokens.forEach(::removeSession)

        uniqueControllers.forEach { (token, controller) ->
            val existing = sessions[token]
            if (existing == null) {
                addSession(token, controller)
            } else {
                refresh(existing)
            }
        }
        activeOrder = tokens
    }

    private fun addSession(token: MediaSession.Token, controller: MediaController) {
        val record = SessionRecord(token, controller)
        sessions[token] = record
        try {
            controller.registerCallback(record.callback, mainHandler)
            record.callbackRegistered = true
            refresh(record)
        } catch (_: SecurityException) {
            removeSession(token)
            throw SecurityException("Media-session access was revoked")
        } catch (_: RuntimeException) {
            // A session can disappear between enumeration and callback registration.
            removeSession(token)
        }
    }

    private fun refresh(record: SessionRecord, source: MediaController = record.controller) {
        if (sessions[record.token] !== record) return
        val candidate = readFacts(source, record.token)
        val previous = record.facts
        if (previous?.copy(revision = 0L) == candidate) return
        record.facts = candidate.copy(revision = (previous?.revision ?: 0L) + 1L)
    }

    private fun readFacts(
        controller: MediaController,
        token: MediaSession.Token,
    ): MediaSessionFacts {
        val metadata = controller.metadata
        val playbackState = controller.playbackState
        val playbackInfo = controller.playbackInfo
        val actions = playbackState?.actions ?: 0L
        val customActions = playbackState?.customActions.orEmpty().map { action ->
            MediaCustomAction(
                action = action.action,
                name = action.name?.toString(),
                iconResource = action.icon,
            )
        }

        return MediaSessionFacts(
            token = token,
            packageName = controller.packageName,
            title = metadata.text(MediaMetadata.METADATA_KEY_TITLE),
            displayTitle = metadata.text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            artist = metadata.text(MediaMetadata.METADATA_KEY_ARTIST),
            album = metadata.text(MediaMetadata.METADATA_KEY_ALBUM),
            displaySubtitle = metadata.text(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
            displayDescription = metadata.text(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION),
            durationMs = metadata.long(MediaMetadata.METADATA_KEY_DURATION),
            artworkUri = metadata.text(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
                ?: metadata.text(MediaMetadata.METADATA_KEY_ART_URI)
                ?: metadata.text(MediaMetadata.METADATA_KEY_ALBUM_ART_URI),
            playbackState = playbackState?.state,
            playbackPositionMs = playbackState?.position,
            playbackSpeed = playbackState?.playbackSpeed,
            playbackPositionUpdatedAtRealtimeMs = playbackState?.lastPositionUpdateTime,
            playbackType = playbackInfo?.playbackType,
            playbackActions = immutableActions(actions),
            customActions = Collections.unmodifiableList(customActions),
            revision = 0L,
        )
    }

    private fun removeSession(token: MediaSession.Token) {
        val record = sessions.remove(token) ?: return
        if (record.callbackRegistered) {
            try {
                record.controller.unregisterCallback(record.callback)
            } catch (_: RuntimeException) {
                // The session may have ended while its callback was being detached.
            }
            record.callbackRegistered = false
        }
    }

    private fun clearSessions() {
        sessions.keys.toList().forEach(::removeSession)
        activeOrder = emptyList()
    }

    private fun loseAccess() {
        unregisterActiveListener()
        clearSessions()
        accessState = MediaAccessState.UNAVAILABLE
        publishSnapshot()
    }

    private fun MediaTransportAction.requiredPlaybackActionBit(): Long? = when (this) {
        MediaTransportAction.PLAY -> PlaybackState.ACTION_PLAY
        MediaTransportAction.PAUSE -> PlaybackState.ACTION_PAUSE
        MediaTransportAction.PLAY_PAUSE -> PlaybackState.ACTION_PLAY_PAUSE
        MediaTransportAction.NEXT -> PlaybackState.ACTION_SKIP_TO_NEXT
        MediaTransportAction.PREVIOUS -> PlaybackState.ACTION_SKIP_TO_PREVIOUS
        else -> null
    }

    private fun publishSnapshot() {
        val snapshot = activeOrder.mapNotNull { sessions[it]?.facts }
        listener.onSnapshot(
            Collections.unmodifiableList(snapshot),
            accessState,
        )
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == mainHandler.looper) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private inner class SessionRecord(
        val token: MediaSession.Token,
        val controller: MediaController,
    ) {
        var facts: MediaSessionFacts? = null
        var callbackRegistered = false

        val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) = refreshIfCurrent()

            override fun onPlaybackStateChanged(state: PlaybackState?) = refreshIfCurrent()

            override fun onAudioInfoChanged(playbackInfo: MediaController.PlaybackInfo) =
                refreshIfCurrent()

            override fun onSessionDestroyed() {
                if (sessions[token] !== this@SessionRecord) return
                removeSession(token)
                activeOrder = activeOrder.filterNot { it == token }
                publishSnapshot()
                reconcileOnMain()
            }

            private fun refreshIfCurrent() {
                if (sessions[token] !== this@SessionRecord) return
                try {
                    refresh(this@SessionRecord)
                    publishSnapshot()
                } catch (_: SecurityException) {
                    loseAccess()
                }
            }
        }
    }

    private companion object {
        fun MediaMetadata?.text(key: String): String? = try {
            this?.getText(key)?.toString()
        } catch (_: RuntimeException) {
            null
        }

        fun MediaMetadata?.long(key: String): Long? = try {
            if (this?.containsKey(key) == true) getLong(key) else null
        } catch (_: RuntimeException) {
            null
        }

        fun immutableActions(bits: Long): Set<MediaTransportAction> {
            val actions = buildSet {
                if (bits has PlaybackState.ACTION_PLAY) add(MediaTransportAction.PLAY)
                if (bits has PlaybackState.ACTION_PAUSE) add(MediaTransportAction.PAUSE)
                if (bits has PlaybackState.ACTION_PLAY_PAUSE) add(MediaTransportAction.PLAY_PAUSE)
                if (bits has PlaybackState.ACTION_STOP) add(MediaTransportAction.STOP)
                if (bits has PlaybackState.ACTION_SKIP_TO_NEXT) add(MediaTransportAction.NEXT)
                if (bits has PlaybackState.ACTION_SKIP_TO_PREVIOUS) add(MediaTransportAction.PREVIOUS)
                if (bits has PlaybackState.ACTION_FAST_FORWARD) add(MediaTransportAction.FAST_FORWARD)
                if (bits has PlaybackState.ACTION_REWIND) add(MediaTransportAction.REWIND)
                if (bits has PlaybackState.ACTION_SEEK_TO) add(MediaTransportAction.SEEK_TO)
                if (bits has PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM) add(MediaTransportAction.SKIP_TO_QUEUE_ITEM)
                if (bits has PlaybackState.ACTION_PLAY_FROM_MEDIA_ID) add(MediaTransportAction.PLAY_FROM_MEDIA_ID)
                if (bits has PlaybackState.ACTION_PLAY_FROM_SEARCH) add(MediaTransportAction.PLAY_FROM_SEARCH)
                if (bits has PlaybackState.ACTION_PLAY_FROM_URI) add(MediaTransportAction.PLAY_FROM_URI)
                if (bits has PlaybackState.ACTION_PREPARE) add(MediaTransportAction.PREPARE)
                if (bits has PlaybackState.ACTION_PREPARE_FROM_MEDIA_ID) add(MediaTransportAction.PREPARE_FROM_MEDIA_ID)
                if (bits has PlaybackState.ACTION_PREPARE_FROM_SEARCH) add(MediaTransportAction.PREPARE_FROM_SEARCH)
                if (bits has PlaybackState.ACTION_PREPARE_FROM_URI) add(MediaTransportAction.PREPARE_FROM_URI)
                if (bits has PlaybackState.ACTION_SET_RATING) add(MediaTransportAction.SET_RATING)
                if (bits has PlaybackState.ACTION_SET_PLAYBACK_SPEED) add(MediaTransportAction.SET_PLAYBACK_SPEED)
            }
            return Collections.unmodifiableSet(actions)
        }

        private infix fun Long.has(action: Long): Boolean = this and action != 0L
    }
}

/** An immutable snapshot of fields explicitly published through one media session. */
internal data class MediaSessionFacts(
    /** Exact source identity; do not replace it with a package/title-derived key. */
    val token: MediaSession.Token,
    val packageName: String,
    val title: String?,
    val displayTitle: String?,
    val artist: String?,
    val album: String?,
    val displaySubtitle: String?,
    val displayDescription: String?,
    val durationMs: Long?,
    /** URI published by the session; this adapter does not dereference it. */
    val artworkUri: String?,
    val playbackState: Int?,
    val playbackPositionMs: Long?,
    val playbackSpeed: Float?,
    val playbackPositionUpdatedAtRealtimeMs: Long?,
    val playbackType: Int?,
    /** Actions currently advertised in PlaybackState; [MediaSourceAdapter.send] rechecks them. */
    val playbackActions: Set<MediaTransportAction>,
    val customActions: List<MediaCustomAction>,
    /** Monotonic revision for changed facts during this active token's lifetime. */
    val revision: Long,
)

internal data class MediaCustomAction(
    val action: String,
    val name: String?,
    val iconResource: Int,
)

internal enum class MediaAccessState {
    STOPPED,
    AVAILABLE,
    UNAVAILABLE,
}

internal enum class MediaTransportAction {
    PLAY,
    PAUSE,
    PLAY_PAUSE,
    STOP,
    NEXT,
    PREVIOUS,
    FAST_FORWARD,
    REWIND,
    SEEK_TO,
    SKIP_TO_QUEUE_ITEM,
    PLAY_FROM_MEDIA_ID,
    PLAY_FROM_SEARCH,
    PLAY_FROM_URI,
    PREPARE,
    PREPARE_FROM_MEDIA_ID,
    PREPARE_FROM_SEARCH,
    PREPARE_FROM_URI,
    SET_RATING,
    SET_PLAYBACK_SPEED,
}
