package dev.luinbytes.dynamicisland

import android.content.ActivityNotFoundException
import android.content.Intent
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

class MainActivity : ComponentActivity() {
    private var overlayGranted by mutableStateOf(false)
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
                        geometry = OverlayController.geometry,
                        message = message,
                        onOpenOverlaySettings = ::openOverlaySettings,
                        onStart = ::startPreview,
                        onStop = {
                            OverlayController.stop()
                            message = null
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        overlayGranted = Settings.canDrawOverlays(this)
        OverlayController.refresh(this)
    }

    private fun openOverlaySettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        } catch (_: ActivityNotFoundException) {
            message = "This device has no overlay Settings screen."
        }
    }

    private fun startPreview() {
        message = OverlayController.show(this)
    }
}

@Composable
private fun PrototypeScreen(
    overlayGranted: Boolean,
    overlayRunning: Boolean,
    geometry: String,
    message: String?,
    onOpenOverlaySettings: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Spacer(Modifier.height(10.dp))
        Text("ISLAND PROTOTYPE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text("Android layout probe", style = MaterialTheme.typography.headlineMedium)
        Text(
            "The demo pill tests an ordinary app overlay. Its contour and timing are provisional; it has no iPhone reference match or live publisher data.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Access", style = MaterialTheme.typography.titleMedium)
                Text(if (overlayGranted) "Draw over apps: granted" else "Draw over apps: not granted")
                Text("Notification access: not requested by this scaffold")
                if (!overlayGranted) {
                    Text("Open Android Settings and enable display over other apps for this app. Return here to recheck it.")
                    OutlinedButton(onClick = onOpenOverlaySettings) { Text("Open overlay settings") }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Demo window", style = MaterialTheme.typography.titleMedium)
                Text(if (overlayRunning) "Showing a bounded app overlay" else "Stopped")
                Text(geometry, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onStart, enabled = overlayGranted && !overlayRunning) {
                        Text("Show demo")
                    }
                    OutlinedButton(onClick = onStop, enabled = overlayRunning) {
                        Text("Stop")
                    }
                }
                Text("Tap the pill to expand or collapse; long-press it to stop. The window sits below the reserved status and camera area.")
            }
        }

        if (message != null) {
            Text(message, color = MaterialTheme.colorScheme.error)
        }

        Text("Next: measured geometry, real adapters, lifecycle and system coexistence checks.", style = MaterialTheme.typography.bodySmall)
    }
}
