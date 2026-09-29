package dev.luinbytes.dynamicisland

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.app.NotificationManager
import android.Manifest
import android.content.pm.PackageManager
import android.media.session.MediaSession
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var overlayGranted by mutableStateOf(false)
    private var listenerGranted by mutableStateOf(false)
    private var message by mutableStateOf<String?>(null)
    private var openedTimerId by mutableStateOf<String?>(null)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { IslandRuntime.reconcileTimerAlarms() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (IslandRuntime.mediaEnabled) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        selectTimerFromIntent(intent)
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
                        openedTimer = IslandRuntime.timerSnapshots.firstOrNull { it.id == openedTimerId },
                        alarmState = IslandRuntime.alarmState,
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
                        onStartTimer = { duration, label -> IslandRuntime.startTimer(duration, label) },
                        onPauseTimer = { IslandRuntime.pauseTimer(it) },
                        onResumeTimer = { IslandRuntime.resumeTimer(it) },
                        onCancelTimer = {
                            IslandRuntime.cancelTimer(it)
                            if (openedTimerId == it) openedTimerId = null
                        },
                        onEnableTimerNotifications = ::enableTimerNotifications,
                        onOpenExactAlarmSettings = ::openExactAlarmSettings,
                        onSelectSource = {
                            IslandStateEngine.select(it)
                            IslandRuntime.refresh()
                        },
                        onEnableMedia = ::enableMedia,
                        onDisableMedia = {
                            IslandRuntime.changeMediaEnabled(false)
                            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        },
                        onOpenListenerSettings = ::openListenerSettings,
                        onAllowMediaPackage = IslandRuntime::setMediaPackageAllowed,
                        onMediaAction = { token, action ->
                            if (!IslandRuntime.sendMediaAction(token, action)) {
                                message = "Playback command is no longer available."
                            }
                        },
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
        IslandRuntime.reconcileTimerAlarms()
        IslandRuntime.reconcileMedia()
        message = IslandRuntime.refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        selectTimerFromIntent(intent)
    }

    private fun selectTimerFromIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "dynamicisland" || data.authority != "timer-details") return
        val timerId = data.lastPathSegment ?: return
        if (IslandRuntime.timerSnapshots.none { it.id == timerId }) return
        openedTimerId = timerId
        IslandStateEngine.select("timer:$timerId")
        IslandRuntime.refresh()
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
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
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

    private fun enableTimerNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            })
        } catch (_: ActivityNotFoundException) {
            message = "This device has no app notification Settings screen."
        }
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:$packageName")
            })
        } catch (_: ActivityNotFoundException) {
            message = "This device has no exact alarm Settings screen."
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
    openedTimer: TimerSnapshot?,
    alarmState: AlarmState?,
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
    onStartTimer: (Long, String) -> Unit,
    onPauseTimer: (String) -> Unit,
    onResumeTimer: (String) -> Unit,
    onCancelTimer: (String) -> Unit,
    onEnableTimerNotifications: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    onSelectSource: (String) -> Unit,
    onEnableMedia: () -> Unit,
    onDisableMedia: () -> Unit,
    onOpenListenerSettings: () -> Unit,
    onAllowMediaPackage: (String, Boolean) -> Unit,
    onMediaAction: (MediaSession.Token, MediaTransportAction) -> Unit,
) {
    var customMinutes by rememberSaveable { mutableStateOf("") }
    var customLabel by rememberSaveable { mutableStateOf("") }
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

        if (openedTimer != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Opened timer ${openedTimer.createdOrder}", style = MaterialTheme.typography.titleMedium)
                    Text("${formatTimer(openedTimer.remainingMillis)} · ${openedTimer.state.name.lowercase()}")
                    OutlinedButton(onClick = { onCancelTimer(openedTimer.id) }) { Text("Clear timer") }
                }
            }
        }

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
                    if (session.packageName in allowedMediaPackages) {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            for ((action, label) in listOf(
                                MediaTransportAction.PREVIOUS to "Previous",
                                MediaTransportAction.PLAY to "Play",
                                MediaTransportAction.PAUSE to "Pause",
                                MediaTransportAction.PLAY_PAUSE to "Toggle",
                                MediaTransportAction.NEXT to "Next",
                            )) {
                                if (action in session.playbackActions) {
                                    OutlinedButton(onClick = { onMediaAction(session.token, action) }) {
                                        Text(label)
                                    }
                                }
                            }
                        }
                    }
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
                Text("Start independent app-owned countdowns. Android schedules their completion while the app is closed; exact timing and alerts depend on access below.")
                if (alarmState != null) {
                    Text("Background: ${alarmState.scheduledAlarmCount} scheduled · ${when {
                        alarmState.exactAlarmAccess -> "exact timing available"
                        alarmState.inexactFallbackUsed -> "inexact timing in use"
                        else -> "exact timing not allowed"
                    }}")
                    Text(if (alarmState.notificationsAvailable) "Completion alerts: available" else "Completion alerts: unavailable")
                    if (!alarmState.notificationsAvailable) {
                        OutlinedButton(onClick = onEnableTimerNotifications) { Text("Enable timer alerts") }
                    }
                    if (!alarmState.exactAlarmAccess) {
                        OutlinedButton(onClick = onOpenExactAlarmSettings) { Text("Allow exact timing") }
                    }
                    if (alarmState.message.isNotBlank()) {
                        Text(alarmState.message, style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedTextField(
                    value = customLabel,
                    onValueChange = { customLabel = it.take(48) },
                    label = { Text("Timer name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onStartTimer(60_000L, customLabel) }) { Text("1 min") }
                    OutlinedButton(onClick = { onStartTimer(300_000L, customLabel) }) { Text("5 min") }
                    OutlinedButton(onClick = { onStartTimer(600_000L, customLabel) }) { Text("10 min") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = customMinutes,
                        onValueChange = { customMinutes = it.filter(Char::isDigit).take(4) },
                        label = { Text("Minutes (1–1440)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    val minutes = customMinutes.toLongOrNull()
                    Button(
                        onClick = {
                            if (minutes != null) {
                                onStartTimer(minutes * 60_000L, customLabel)
                                customMinutes = ""
                            }
                        },
                        enabled = minutes != null && minutes in 1L..1440L,
                    ) { Text("Start") }
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
    return if (seconds >= 3_600L) {
        String.format(Locale.US, "%d:%02d:%02d", seconds / 3_600L, (seconds % 3_600L) / 60L, seconds % 60L)
    } else {
        String.format(Locale.US, "%02d:%02d", seconds / 60L, seconds % 60L)
    }
}
