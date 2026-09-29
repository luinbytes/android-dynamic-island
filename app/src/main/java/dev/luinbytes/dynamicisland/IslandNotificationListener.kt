package dev.luinbytes.dynamicisland

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
    }

    override fun onDestroy() {
        IslandRuntime.onListenerConnection(false)
        super.onDestroy()
    }
}
