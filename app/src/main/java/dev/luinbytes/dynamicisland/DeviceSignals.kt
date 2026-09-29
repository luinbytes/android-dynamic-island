package dev.luinbytes.dynamicisland

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper

/** Read-only device facts. These do not by themselves assert an Island activity. */
internal data class DeviceSignalSnapshot(
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val ringerMode: Int? = null,
    val torchAvailable: Boolean? = null,
    val torchEnabled: Boolean? = null,
)

internal class DeviceSignals(
    context: Context,
    private val onChanged: (DeviceSignalSnapshot) -> Unit,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)
    private var started = false
    private var receiverRegistered = false
    private var torchRegistered = false
    private var torchCameraId: String? = null
    private var current = DeviceSignalSnapshot()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> updateBattery(intent)
                AudioManager.RINGER_MODE_CHANGED_ACTION -> updateRinger()
            }
        }
    }

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchCameraId) publish(current.copy(torchAvailable = true, torchEnabled = enabled))
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            if (cameraId == torchCameraId) publish(current.copy(torchAvailable = false, torchEnabled = null))
        }
    }

    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (started) return
        started = true
        updateRinger()
        try {
            val battery = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (battery != null) updateBattery(battery)
        } catch (_: RuntimeException) {
            // This signal remains unknown on devices that withhold it.
        }
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            }
            appContext.registerReceiver(receiver, filter)
            receiverRegistered = true
        } catch (_: RuntimeException) {
            receiverRegistered = false
        }
        torchCameraId = findBackTorch()
        if (torchCameraId != null) {
            try {
                cameraManager.registerTorchCallback(torchCallback, mainHandler)
                torchRegistered = true
            } catch (_: RuntimeException) {
                publish(current.copy(torchAvailable = null, torchEnabled = null))
            }
        } else {
            publish(current.copy(torchAvailable = false, torchEnabled = null))
        }
        onChanged(current)
    }

    fun stop() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!started) return
        started = false
        if (receiverRegistered) {
            try {
                appContext.unregisterReceiver(receiver)
            } catch (_: RuntimeException) {
                // Android may have already detached the receiver.
            }
            receiverRegistered = false
        }
        if (torchRegistered) {
            cameraManager.unregisterTorchCallback(torchCallback)
            torchRegistered = false
        }
    }

    private fun findBackTorch(): String? = try {
        cameraManager.cameraIdList.firstOrNull { id ->
            val camera = cameraManager.getCameraCharacteristics(id)
            camera.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                camera.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    } catch (_: RuntimeException) {
        null
    }

    private fun updateBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
            BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
            else -> null
        }
        publish(current.copy(batteryPercent = percent, charging = charging))
    }

    private fun updateRinger() {
        val mode = try { audioManager.ringerMode } catch (_: RuntimeException) { null }
        publish(current.copy(ringerMode = mode))
    }

    private fun publish(next: DeviceSignalSnapshot) {
        if (next == current) return
        current = next
        if (started) onChanged(next)
    }
}
