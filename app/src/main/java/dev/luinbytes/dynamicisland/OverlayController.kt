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

/** Bounded APK renderer. Source truth lives in [IslandStateEngine], not in this window. */
internal object OverlayController {
    var running by mutableStateOf(false)
        private set
    var geometry by mutableStateOf("No overlay window")
        private set

    private var manager: WindowManager? = null
    private var view: IslandPillView? = null
    private var params: WindowManager.LayoutParams? = null

    fun show(context: Context): String? {
        if (running) return null
        if (!Settings.canDrawOverlays(context)) return "Draw over apps is not granted"
        val snapshot = IslandStateEngine.snapshot
        if (snapshot.selectedId == null) return "Start a source before showing the Island"

        val appContext = context.applicationContext
        val windowManager = appContext.getSystemService(WindowManager::class.java)
        val pill = IslandPillView(appContext).apply {
            render(snapshot)
            onToggle = { toggle() }
        }
        val layout = WindowManager.LayoutParams(
            appContext.dp(widthFor(snapshot)),
            appContext.dp(heightFor(snapshot)),
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
        val snapshot = IslandStateEngine.snapshot
        if (snapshot.selectedId == null) {
            stop()
            return
        }
        val windowManager = manager ?: return
        val pill = view ?: return
        val layout = params ?: return
        pill.render(snapshot)
        layout.width = context.dp(widthFor(snapshot))
        layout.height = context.dp(heightFor(snapshot))
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
        val selected = IslandStateEngine.snapshot.selectedId ?: return
        IslandStateEngine.setExpanded(selected, IslandStateEngine.snapshot.expandedSourceId != selected)
        view?.let { refresh(it.context) }
    }

    private fun widthFor(snapshot: IslandSnapshot): Int = when (snapshot.presentation) {
        IslandPresentation.EXPANDED -> 304
        IslandPresentation.MULTIPLE -> 258
        else -> 220
    }

    private fun heightFor(snapshot: IslandSnapshot): Int =
        if (snapshot.presentation == IslandPresentation.EXPANDED) 100 else 52

    private fun updatePosition(
        windowManager: WindowManager,
        layout: WindowManager.LayoutParams,
        context: Context,
    ) {
        val metrics = windowManager.currentWindowMetrics
        val insets = metrics.windowInsets
        val statusTop = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
        val centerX = metrics.bounds.centerX()
        val pillLeft = centerX - layout.width / 2
        val pillRight = pillLeft + layout.width
        val cutoutBottom = insets.displayCutout?.boundingRects
            ?.filter { it.left < pillRight && it.right > pillLeft }
            ?.maxOfOrNull { it.bottom } ?: 0
        // The application-overlay area begins below the status bar on the Samsung probe.
        // Only the cutout's excess below that area needs an additional offset.
        layout.y = maxOf(0, cutoutBottom - statusTop) + context.dp(8)
        geometry = "Status inset ${statusTop}px · cutout bottom ${cutoutBottom}px · window offset ${layout.y}px"
    }

    private fun Context.dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}

internal class IslandPillView(context: Context) : View(context) {
    var onToggle: (() -> Unit)? = null
    private var snapshot = IslandStateEngine.snapshot

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
        contentDescription = "Island activity"
        setOnLongClickListener {
            IslandRuntime.changeOverlayEnabled(false)
            true
        }
    }

    fun render(value: IslandSnapshot) {
        snapshot = value
        val selected = value.selectedId?.let(value.sourcesById::get)
        val state = if (value.presentation == IslandPresentation.EXPANDED) "expanded" else "compact"
        contentDescription = "${selected?.title ?: "Island"}, $state. Double tap to change size. Long press to stop."
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = height / 2f
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(bounds, radius, radius, body)
        val expanded = snapshot.presentation == IslandPresentation.EXPANDED
        val selected = snapshot.selectedId?.let(snapshot.sourcesById::get)
        val centerY = if (expanded) height * 0.36f else height / 2f
        canvas.drawCircle(context.dp(25).toFloat(), centerY, context.dp(5).toFloat(), accent)
        val textX = context.dp(42).toFloat()
        val titleText = if (!expanded && selected?.kind == IslandSourceKind.TIMER) {
            "${selected.title} ${selected.detail?.substringBefore(' ').orEmpty()}"
        } else {
            selected?.title ?: "ISLAND"
        }
        val badgeReserve = if (!expanded && snapshot.visibleIds.size > 1) context.dp(45) else context.dp(16)
        canvas.drawText(fitText(titleText, title, width - textX - badgeReserve), textX, centerY + title.textSize * 0.34f, title)
        if (expanded) {
            canvas.drawText(
                fitText(selected?.detail ?: "Activity in progress", detail, width - textX - context.dp(16)),
                textX,
                height * 0.68f,
                detail,
            )
        } else if (snapshot.visibleIds.size > 1) {
            canvas.drawText("+${snapshot.visibleIds.size - 1}", width - context.dp(28).toFloat(), centerY, detail)
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        onToggle?.invoke()
        return true
    }

    private fun Context.dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun fitText(value: String, paint: Paint, availablePixels: Float): String {
        if (availablePixels <= 0f) return ""
        if (paint.measureText(value) <= availablePixels) return value
        var count = paint.breakText(value, true, availablePixels - paint.measureText("…"), null)
        while (count > 0 && paint.measureText(value.substring(0, count) + "…") > availablePixels) count--
        return if (count > 0) value.substring(0, count) + "…" else ""
    }
}
