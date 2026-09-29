package dev.luinbytes.dynamicisland

import android.app.NotificationManager
import android.content.ComponentName
import android.os.SystemClock
import android.service.notification.NotificationListenerService

/** System-bound listener used only for explicitly enabled source adapters. */
class IslandNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        IslandRuntime.onListenerConnection(true)
    }

    override fun onListenerDisconnected() {
        IslandRuntime.onListenerConnection(false)
        super.onListenerDisconnected()

        // A disconnect can be temporary. Retry only while media is still opted in and the
        // current component still has listener access; keep no ComponentName across lifecycles.
        requestRebindIfStillEnabled()
    }

    override fun onDestroy() {
        IslandRuntime.onListenerConnection(false)
        super.onDestroy()
    }

    private fun requestRebindIfStillEnabled() {
        if (!IslandRuntime.mediaEnabled) return

        val component = ComponentName(this, IslandNotificationListener::class.java)
        val notificationManager = getSystemService(NotificationManager::class.java) ?: return
        if (!notificationManager.isNotificationListenerAccessGranted(component)) return

        val now = SystemClock.elapsedRealtime()
        val previousRequest = lastRebindRequestElapsedRealtime
        if (previousRequest != null && now - previousRequest < REBIND_RETRY_COOLDOWN_MS) return

        try {
            NotificationListenerService.requestRebind(component)
            lastRebindRequestElapsedRealtime = now
        } catch (_: SecurityException) {
            // Access may be revoked between the grant check and the rebind request.
        }
    }

    private companion object {
        const val REBIND_RETRY_COOLDOWN_MS = 30_000L

        // The listener service can be recreated after a disconnect, so the throttle is
        // process-wide so repeated connect/disconnect callbacks cannot spin on rebind.
        var lastRebindRequestElapsedRealtime: Long? = null
    }
}
