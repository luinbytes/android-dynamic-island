package dev.luinbytes.dynamicisland

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** A process-local layout probe. It has no publisher data or background service. */
internal object OverlayController {
    var running by mutableStateOf(false)
        private set
    var geometry by mutableStateOf("No overlay window")
        private set

    private var manager: WindowManager? = null
    private var view: PreviewPillView? = null
    private var params: WindowManager.LayoutParams? = null

    fun show(context: Context): String? {
        if (running) return null
        if (!Settings.canDrawOverlays(context)) return "Draw over apps is not granted"

        val appContext = context.applicationContext
        val windowManager = appContext.getSystemService(WindowManager::class.java)
        val pill = PreviewPillView(appContext).apply { onToggle = { toggle() } }
        val layout = WindowManager.LayoutParams(
            appContext.dp(220),
            appContext.dp(52),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }

        updatePosition(windowManager, layout, appContext)
        return try {
            windowManager.addView(pill, layout)
            manager = windowManager
            view = pill
            params = layout
            running = true
            null
        } catch (error: RuntimeException) {
            geometry = "Overlay could not be shown"
            error.message ?: error.javaClass.simpleName
        }
    }

    fun refresh(context: Context) {
        if (!running) return
        if (!Settings.canDrawOverlays(context)) {
            stop()
            return
        }
        val windowManager = manager ?: return
        val pill = view ?: return
        val layout = params ?: return
        updatePosition(windowManager, layout, context)
        try {
            windowManager.updateViewLayout(pill, layout)
        } catch (_: RuntimeException) {
            stop()
        }
    }

    fun stop() {
        val pill = view
        if (pill != null) {
            try {
                manager?.removeViewImmediate(pill)
            } catch (_: RuntimeException) {
                // A removed window is already stopped.
            }
        }
        view = null
        params = null
        manager = null
        running = false
        geometry = "No overlay window"
    }

    private fun toggle() {
        val pill = view ?: return
        val layout = params ?: return
        pill.expanded = !pill.expanded
        layout.width = pill.context.dp(if (pill.expanded) 304 else 220)
        layout.height = pill.context.dp(if (pill.expanded) 100 else 52)
        try {
            manager?.updateViewLayout(pill, layout)
        } catch (_: RuntimeException) {
            stop()
        }
    }

    private fun updatePosition(
        windowManager: WindowManager,
        layout: WindowManager.LayoutParams,
        context: Context,
    ) {
        val insets = windowManager.currentWindowMetrics.windowInsets
        val statusTop = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
        val cutoutBottom = insets.displayCutout?.boundingRects?.maxOfOrNull { it.bottom } ?: 0
        // The application-overlay area begins below the status bar on the Samsung probe.
        // Only the cutout's excess below that area needs an additional offset.
        layout.y = maxOf(0, cutoutBottom - statusTop) + context.dp(8)
        geometry = "Status inset ${statusTop}px · cutout bottom ${cutoutBottom}px · window offset ${layout.y}px"
    }

    private fun Context.dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}

internal class PreviewPillView(context: Context) : View(context) {
    var onToggle: (() -> Unit)? = null
    var expanded = false
        set(value) {
            field = value
            contentDescription = if (value) {
                "Demo Island expanded. Double tap to collapse."
            } else {
                "Demo Island compact. Double tap to expand."
            }
            invalidate()
        }

    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val bounds = RectF()
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(96, 218, 208) }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
        isFakeBoldText = true
    }
    private val detail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(180, 190, 195)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
    }

    init {
        isClickable = true
        contentDescription = "Demo Island compact. Double tap to expand."
        setOnLongClickListener {
            OverlayController.stop()
            true
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = height / 2f
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(bounds, radius, radius, body)
        val centerY = if (expanded) height * 0.36f else height / 2f
        canvas.drawCircle(context.dp(25).toFloat(), centerY, context.dp(5).toFloat(), accent)
        val textX = context.dp(42).toFloat()
        canvas.drawText("ISLAND · DEMO", textX, centerY + title.textSize * 0.34f, title)
        if (expanded) {
            canvas.drawText("Layout preview; no live signals", textX, height * 0.68f, detail)
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        onToggle?.invoke()
        return true
    }

    private fun Context.dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
