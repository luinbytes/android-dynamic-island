package dev.luinbytes.dynamicisland

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.app.NotificationManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var overlayGranted by mutableStateOf(false)
    private var listenerGranted by mutableStateOf(false)
    private var message by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF11181B),
                    surface = Color(0xFF1D292D),
                    primary = Color(0xFF66DACA),
                ),
            ) {
                Surface(Modifier.fillMaxSize()) {
                    PrototypeScreen(
                        overlayGranted = overlayGranted,
                        overlayRunning = OverlayController.running,
                        overlayEnabled = IslandRuntime.overlayEnabled,
                        geometry = OverlayController.geometry,
                        timers = IslandRuntime.timerSnapshots,
                        signals = IslandRuntime.signalSnapshot,
                        island = IslandStateEngine.snapshot,
                        mediaEnabled = IslandRuntime.mediaEnabled,
                        listenerGranted = listenerGranted,
                        listenerConnected = IslandRuntime.listenerConnected,
                        mediaAccess = IslandRuntime.mediaAccess,
                        mediaSessions = IslandRuntime.mediaSessions,
                        mediaPackages = IslandRuntime.knownMediaPackages,
                        allowedMediaPackages = IslandRuntime.allowedMediaPackages,
                        message = message,
                        onOpenOverlaySettings = ::openOverlaySettings,
                        onStart = ::startPreview,
                        onStop = {
                            IslandRuntime.changeOverlayEnabled(false)
                            message = null
                        },
                        onStartTimer = { IslandRuntime.startTimer(it) },
                        onPauseTimer = { IslandRuntime.pauseTimer(it) },
                        onResumeTimer = { IslandRuntime.resumeTimer(it) },
                        onCancelTimer = { IslandRuntime.cancelTimer(it) },
                        onSelectSource = {
                            IslandStateEngine.select(it)
                            IslandRuntime.refresh()
                        },
                        onEnableMedia = ::enableMedia,
                        onDisableMedia = { IslandRuntime.changeMediaEnabled(false) },
                        onOpenListenerSettings = ::openListenerSettings,
                        onAllowMediaPackage = IslandRuntime::setMediaPackageAllowed,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        overlayGranted = Settings.canDrawOverlays(this)
        listenerGranted = getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(this, IslandNotificationListener::class.java))
        message = IslandRuntime.refresh()
    }

    private fun openOverlaySettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        } catch (_: ActivityNotFoundException) {
            message = "This device has no overlay Settings screen."
        }
    }

    private fun startPreview() {
        message = IslandRuntime.changeOverlayEnabled(true)
    }

    private fun enableMedia() {
        IslandRuntime.changeMediaEnabled(true)
        if (!listenerGranted) openListenerSettings()
    }

    private fun openListenerSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            message = "This device has no notification access Settings screen."
        }
    }
}

@Composable
private fun PrototypeScreen(
    overlayGranted: Boolean,
    overlayRunning: Boolean,
    overlayEnabled: Boolean,
    geometry: String,
    timers: List<TimerSnapshot>,
    signals: DeviceSignalSnapshot,
    island: IslandSnapshot,
    mediaEnabled: Boolean,
    listenerGranted: Boolean,
    listenerConnected: Boolean,
    mediaAccess: MediaAccessState,
    mediaSessions: List<MediaSessionFacts>,
    mediaPackages: Set<String>,
    allowedMediaPackages: Set<String>,
    message: String?,
    onOpenOverlaySettings: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onStartTimer: (Long) -> Unit,
    onPauseTimer: (String) -> Unit,
    onResumeTimer: (String) -> Unit,
    onCancelTimer: (String) -> Unit,
    onSelectSource: (String) -> Unit,
    onEnableMedia: () -> Unit,
    onDisableMedia: () -> Unit,
    onOpenListenerSettings: () -> Unit,
    onAllowMediaPackage: (String, Boolean) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Spacer(Modifier.height(10.dp))
        Text("ISLAND", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text("Live activity workspace", style = MaterialTheme.typography.headlineMedium)
        Text(
            "An Android app overlay for confirmed activities. Pixel system integration and iPhone visual matching remain separate device gates.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Access", style = MaterialTheme.typography.titleMedium)
                Text(if (overlayGranted) "Draw over apps: granted" else "Draw over apps: not granted")
                Text("Notification access: ${if (listenerGranted) "granted" else "not granted"}")
                if (!overlayGranted) {
                    Text("Open Android Settings and enable display over other apps for this app. Return here to recheck it.")
                    OutlinedButton(onClick = onOpenOverlaySettings) { Text("Open overlay settings") }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Media sessions", style = MaterialTheme.typography.titleMedium)
                Text("Read published playback sessions only when enabled. Choose which apps may appear in the Island; native playback controls stay available.")
                Text("Listener: ${if (listenerConnected) "connected" else "disconnected"} · media feed: ${mediaAccess.name.lowercase()}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!mediaEnabled) Button(onClick = onEnableMedia) { Text("Enable media") }
                    else OutlinedButton(onClick = onDisableMedia) { Text("Disable media") }
                    if (mediaEnabled && !listenerGranted) {
                        OutlinedButton(onClick = onOpenListenerSettings) { Text("Open access settings") }
                    }
                }
                if (mediaEnabled && !listenerGranted) {
                    Text("Grant notification access in Android Settings, then return. This is separate from overlay access.")
                }
                if (mediaEnabled && listenerConnected && mediaSessions.isEmpty()) Text("No published media sessions observed")
                for (packageName in mediaPackages.sorted()) {
                    val allowed = packageName in allowedMediaPackages
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(packageName, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { onAllowMediaPackage(packageName, !allowed) }) {
                            Text(if (allowed) "Hide" else "Allow")
                        }
                    }
                }
                for (session in mediaSessions) {
                    Text("${session.displayTitle ?: session.title ?: "Untitled"} · ${session.packageName}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Island window", style = MaterialTheme.typography.titleMedium)
                Text(when {
                    overlayRunning -> "Showing ${island.visibleIds.size} of ${island.sourcesById.values.count { it.lifecycle == IslandLifecycle.ACTIVE || it.lifecycle == IslandLifecycle.STALE }} active sources"
                    overlayEnabled -> "Ready when a source is active and overlay access is granted"
                    else -> "Stopped"
                })
                Text(geometry, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onStart, enabled = overlayGranted && !overlayEnabled) {
                        Text("Enable Island")
                    }
                    OutlinedButton(onClick = onStop, enabled = overlayEnabled || overlayRunning) {
                        Text("Stop")
                    }
                }
                Text("Tap the bounded pill to expand or collapse; long-press it to stop the window. Android owns the status bar, shade and lock screen.")
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Timers", style = MaterialTheme.typography.titleMedium)
                Text("Start independent app-owned countdowns. They keep their deadline across process restarts; an exact background completion alert is not configured.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onStartTimer(60_000L) }) { Text("1 min") }
                    OutlinedButton(onClick = { onStartTimer(300_000L) }) { Text("5 min") }
                    OutlinedButton(onClick = { onStartTimer(600_000L) }) { Text("10 min") }
                }
                if (timers.isEmpty()) Text("No timers")
                for (timer in timers) {
                    Text("${if (timer.label == "Timer") "Timer ${timer.createdOrder}" else timer.label} · ${formatTimer(timer.remainingMillis)} · ${timer.state.name.lowercase()}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (timer.state == TimerState.RUNNING) {
                            OutlinedButton(onClick = { onPauseTimer(timer.id) }) { Text("Pause") }
                        } else if (timer.state == TimerState.PAUSED) {
                            OutlinedButton(onClick = { onResumeTimer(timer.id) }) { Text("Resume") }
                        }
                        OutlinedButton(onClick = { onCancelTimer(timer.id) }) { Text("Clear") }
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sources", style = MaterialTheme.typography.titleMedium)
                Text("Policy: ${island.policyVersion} · ${island.policyLabel}", style = MaterialTheme.typography.bodySmall)
                val activeSources = island.sourcesById.values.filter {
                    it.lifecycle == IslandLifecycle.ACTIVE || it.lifecycle == IslandLifecycle.STALE
                }
                if (activeSources.isEmpty()) Text("No active sources")
                for (source in activeSources) {
                    val id = source.id
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val slot = if (id in island.visibleIds) "visible" else "queued"
                        Text("${source.title} · ${source.detail.orEmpty()} · $slot", modifier = Modifier.weight(1f))
                        if (id != island.selectedId) {
                            OutlinedButton(onClick = { onSelectSource(id) }) { Text("Show") }
                        }
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Device signals", style = MaterialTheme.typography.titleMedium)
                Text("Battery: ${signals.batteryPercent?.let { "$it%" } ?: "unavailable"}${if (signals.charging == true) " · charging" else ""}")
                Text("Ringer: ${when (signals.ringerMode) { 0 -> "silent"; 1 -> "vibrate"; 2 -> "normal"; else -> "unavailable" }}")
                Text("Torch: ${when (signals.torchEnabled) { true -> "on"; false -> "off"; null -> if (signals.torchAvailable == false) "unavailable" else "unknown" }}")
                Text("These facts do not automatically become activities.", style = MaterialTheme.typography.bodySmall)
            }
        }

        if (message != null) {
            Text(message, color = MaterialTheme.colorScheme.error)
        }

        Text("APK preview · native Android surfaces remain authoritative", style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatTimer(millis: Long): String {
    val seconds = (millis + 999L) / 1_000L
    return String.format(Locale.US, "%02d:%02d", seconds / 60L, seconds % 60L)
}
