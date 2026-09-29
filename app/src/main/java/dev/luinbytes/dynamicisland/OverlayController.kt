package dev.luinbytes.dynamicisland

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
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
    private var displayManager: DisplayManager? = null
    private var displayListener: DisplayManager.DisplayListener? = null

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
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            if (snapshot.selectedSourceNeedsCaptureProtection()) {
                flags = flags or WindowManager.LayoutParams.FLAG_SECURE
            }
        }

        updatePosition(windowManager, layout, appContext)
        try {
            windowManager.addView(pill, layout)
        } catch (error: RuntimeException) {
            geometry = "Overlay could not be shown"
            return error.message ?: error.javaClass.simpleName
        }
        manager = windowManager
        view = pill
        params = layout
        running = true
        return try {
            registerDisplayListener(appContext)
            null
        } catch (error: RuntimeException) {
            stop()
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
        layout.flags = if (snapshot.selectedSourceNeedsCaptureProtection()) {
            layout.flags or WindowManager.LayoutParams.FLAG_SECURE
        } else {
            layout.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
        }
        updatePosition(windowManager, layout, context)
        try {
            windowManager.updateViewLayout(pill, layout)
        } catch (_: RuntimeException) {
            stop()
        }
    }

    fun stop() {
        val listener = displayListener
        val displays = displayManager
        displayListener = null
        displayManager = null
        if (listener != null && displays != null) {
            try {
                displays.unregisterDisplayListener(listener)
            } catch (_: RuntimeException) {
                // A listener that is already unregistered needs no further cleanup.
            }
        }
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

    private fun registerDisplayListener(context: Context) {
        val displays = context.getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit

            override fun onDisplayRemoved(displayId: Int) = Unit

            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) refresh(context)
            }
        }
        displays.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        displayManager = displays
        displayListener = listener
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

    private fun heightFor(snapshot: IslandSnapshot): Int {
        if (snapshot.presentation != IslandPresentation.EXPANDED) return 52
        val selected = snapshot.selectedId?.let(snapshot.sourcesById::get)
        return if (selected?.kind == IslandSourceKind.TIMER || selected?.kind == IslandSourceKind.MEDIA) 132 else 100
    }

    private fun IslandSnapshot.selectedSourceNeedsCaptureProtection(): Boolean =
        selectedId?.let(sourcesById::get)?.kind == IslandSourceKind.MEDIA

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
        val topCutout = insets.displayCutout?.boundingRectTop
        val cutoutBottom = if (topCutout != null && topCutout.left < pillRight && topCutout.right > pillLeft) {
            topCutout.bottom
        } else {
            0
        }
        // The application-overlay area begins below the status bar on the Samsung probe.
        // Only the cutout's excess below that area needs an additional offset.
        layout.y = maxOf(0, cutoutBottom - statusTop) + context.dp(8)
        geometry = "Status inset ${statusTop}px · cutout bottom ${cutoutBottom}px · window offset ${layout.y}px"
    }

    private fun Context.dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}

internal class IslandPillView(context: Context) : View(context) {
    private enum class TimerAction {
        PAUSE,
        RESUME,
        CLEAR,
    }

    private data class TimerActionTarget(
        val action: TimerAction,
        val timerId: String,
        val label: String,
        val bounds: RectF,
    )

    private data class MediaActionTarget(
        val action: MediaTransportAction,
        val sourceId: String,
        val label: String,
        val bounds: RectF,
    )

    private data class AccessibleTarget(
        val id: Int,
        val label: String,
        val bounds: RectF,
    )

    private companion object {
        const val ACCESSIBILITY_TIMER_TOGGLE = 1
        const val ACCESSIBILITY_TIMER_CLEAR = 2
        const val ACCESSIBILITY_MEDIA_PREVIOUS = 3
        const val ACCESSIBILITY_MEDIA_TOGGLE = 4
        const val ACCESSIBILITY_MEDIA_NEXT = 5
    }

    var onToggle: (() -> Unit)? = null
    private var snapshot = IslandStateEngine.snapshot
    private var touchDownAction: TimerActionTarget? = null
    private var touchDownMediaAction: MediaActionTarget? = null
    private var touchLongPressPerformed = false

    private val accessibilityHelper = object : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int =
            accessibleTargets().firstOrNull { it.bounds.contains(x, y) }?.id ?: HOST_ID

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            virtualViewIds.addAll(accessibleTargets().map { it.id })
        }

        override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
            val target = accessibleTargets().firstOrNull { it.id == virtualViewId }
            node.className = "android.widget.Button"
            node.contentDescription = target?.label ?: "Unavailable control"
            node.isEnabled = target != null
            node.setBoundsInParent(target?.bounds?.let {
                Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt())
            } ?: Rect(0, 0, 1, 1))
            if (target != null) {
                node.isClickable = true
                node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            }
        }

        override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            if (action != AccessibilityNodeInfo.ACTION_CLICK || accessibleTargets().none { it.id == virtualViewId }) {
                return false
            }
            val performed = when (virtualViewId) {
                ACCESSIBILITY_TIMER_TOGGLE -> performTimerToggleAction()
                ACCESSIBILITY_TIMER_CLEAR -> performTimerClearAction()
                ACCESSIBILITY_MEDIA_PREVIOUS, ACCESSIBILITY_MEDIA_TOGGLE, ACCESSIBILITY_MEDIA_NEXT ->
                    performMediaAccessibilityAction(virtualViewId)
                else -> false
            }
            if (performed) sendEventForVirtualView(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED)
            return performed
        }
    }

    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val bounds = RectF()
    private val actionBody = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(34, 40, 44) }
    private val actionOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(70, 91, 94)
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1).toFloat()
    }
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
    private val actionText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    init {
        isClickable = true
        contentDescription = "Island activity"
        ViewCompat.setAccessibilityDelegate(this, accessibilityHelper)
        setOnLongClickListener {
            touchLongPressPerformed = true
            IslandRuntime.changeOverlayEnabled(false)
            true
        }
    }

    fun render(value: IslandSnapshot) {
        snapshot = value
        val selected = value.selectedId?.let(value.sourcesById::get)
        val state = if (value.presentation == IslandPresentation.EXPANDED) "expanded" else "compact"
        val timerActions = timerActionTargets()
        val mediaActions = mediaActionTargets()
        contentDescription = buildString {
            append("${selected?.title ?: "Island"}, $state.")
            if (timerActions.isNotEmpty() || mediaActions.isNotEmpty()) {
                if (timerActions.isNotEmpty()) {
                    append(" Timer controls: ")
                    append(timerActions.joinToString(", ") { "${it.label} timer" })
                }
                if (mediaActions.isNotEmpty()) {
                    append(" Media controls: ")
                    append(mediaActions.joinToString(", ") { it.label })
                }
                append(". Double tap outside the controls to collapse.")
            } else if (state == "expanded") {
                append(" Double tap to collapse.")
            } else {
                append(" Double tap to expand.")
            }
            append(" Long press to stop.")
        }
        accessibilityHelper.invalidateRoot()
        invalidate()
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibilityHelper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        accessibilityHelper.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        accessibilityHelper.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = height / 2f
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(bounds, radius, radius, body)
        val expanded = snapshot.presentation == IslandPresentation.EXPANDED
        val selected = snapshot.selectedId?.let(snapshot.sourcesById::get)
        val hasActions = timerActionTargets().isNotEmpty() || mediaActionTargets().isNotEmpty()
        val centerY = if (expanded && hasActions) height * 0.30f else if (expanded) height * 0.36f else height / 2f
        canvas.drawCircle(context.dp(25).toFloat(), centerY, context.dp(5).toFloat(), accent)
        val textX = context.dp(42).toFloat()
        val titleText = if (!expanded && selected?.kind == IslandSourceKind.TIMER) {
            val countdown = selected.detail?.substringBefore(' ').orEmpty()
            if (countdown.firstOrNull()?.isDigit() == true) "${selected.title} $countdown"
            else selected.title
        } else {
            selected?.title ?: "ISLAND"
        }
        val badgeReserve = if (!expanded && snapshot.visibleIds.size > 1) context.dp(45) else context.dp(16)
        canvas.drawText(fitText(titleText, title, width - textX - badgeReserve), textX, centerY + title.textSize * 0.34f, title)
        if (expanded) {
            canvas.drawText(
                fitText(selected?.detail ?: "Activity in progress", detail, width - textX - context.dp(16)),
                textX,
                if (hasActions) height * 0.49f else height * 0.68f,
                detail,
            )
            if (hasActions) drawActions(canvas)
        } else if (snapshot.visibleIds.size > 1) {
            canvas.drawText("+${snapshot.visibleIds.size - 1}", width - context.dp(28).toFloat(), centerY, detail)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownAction = timerActionAt(event.x, event.y)
                touchDownMediaAction = mediaActionAt(event.x, event.y)
                touchLongPressPerformed = false
            }
            MotionEvent.ACTION_UP -> {
                val downAction = touchDownAction
                val downMediaAction = touchDownMediaAction
                touchDownAction = null
                touchDownMediaAction = null
                if ((downAction != null || downMediaAction != null) && !touchLongPressPerformed) {
                    val upAction = timerActionAt(event.x, event.y)
                    val upMediaAction = mediaActionAt(event.x, event.y)
                    val cancelEvent = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    try {
                        super.onTouchEvent(cancelEvent)
                    } finally {
                        cancelEvent.recycle()
                    }
                    cancelLongPress()

                    val performed = when {
                        downAction != null && upAction?.action == downAction.action &&
                            upAction.timerId == downAction.timerId ->
                            performTimerAction(downAction.action, downAction.timerId)
                        downMediaAction != null && upMediaAction?.action == downMediaAction.action &&
                            upMediaAction.sourceId == downMediaAction.sourceId ->
                            performMediaAction(downMediaAction.action, downMediaAction.sourceId)
                        else -> false
                    }
                    if (performed) {
                        // Send the standard click feedback/event without routing through this view's
                        // toggle action. The synthetic cancel above prevents View from posting a
                        // delayed performClick for the original touch sequence.
                        super.performClick()
                    }
                    touchLongPressPerformed = false
                    return true
                }
                touchDownAction = null
                touchDownMediaAction = null
                touchLongPressPerformed = false
            }
            MotionEvent.ACTION_CANCEL -> {
                touchDownAction = null
                touchDownMediaAction = null
                touchLongPressPerformed = false
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        onToggle?.invoke()
        return true
    }

    private fun drawActions(canvas: Canvas) {
        for (target in timerActionTargets().map { it.label to it.bounds } + mediaActionTargets().map { it.label to it.bounds }) {
            val (label, rect) = target
            val radius = rect.height() / 2f
            canvas.drawRoundRect(rect, radius, radius, actionBody)
            canvas.drawRoundRect(rect, radius, radius, actionOutline)
            val baseline = rect.centerY() - (actionText.ascent() + actionText.descent()) / 2f
            canvas.drawText(label, rect.centerX(), baseline, actionText)
        }
    }

    private fun accessibleTargets(): List<AccessibleTarget> = buildList {
        for (target in timerActionTargets()) {
            add(
                AccessibleTarget(
                    if (target.action == TimerAction.CLEAR) ACCESSIBILITY_TIMER_CLEAR else ACCESSIBILITY_TIMER_TOGGLE,
                    "${target.label} timer",
                    target.bounds,
                ),
            )
        }
        for (target in mediaActionTargets()) {
            val id = when (target.action) {
                MediaTransportAction.PREVIOUS -> ACCESSIBILITY_MEDIA_PREVIOUS
                MediaTransportAction.NEXT -> ACCESSIBILITY_MEDIA_NEXT
                else -> ACCESSIBILITY_MEDIA_TOGGLE
            }
            add(AccessibleTarget(id, target.label, target.bounds))
        }
    }

    private fun timerActionTargets(): List<TimerActionTarget> {
        val timer = selectedTimer() ?: return emptyList()
        if (snapshot.presentation != IslandPresentation.EXPANDED || snapshot.expandedSourceId != snapshot.selectedId) {
            return emptyList()
        }

        val top = (height.takeIf { it > 0 } ?: context.dp(132)) - context.dp(56)
        val bottom = (height.takeIf { it > 0 } ?: context.dp(132)) - context.dp(4)
        val left = context.dp(8).toFloat()
        val right = (width.takeIf { it > 0 } ?: context.dp(304)) - context.dp(8).toFloat()
        val clearWidth = context.dp(84).toFloat()
        val gap = context.dp(8).toFloat()
        val clearLeft = if (timer.state == TimerState.FINISHED) left else right - clearWidth
        val targets = mutableListOf<TimerActionTarget>()

        when (timer.state) {
            TimerState.RUNNING -> targets += TimerActionTarget(
                TimerAction.PAUSE,
                timer.id,
                "Pause",
                RectF(left, top.toFloat(), clearLeft - gap, bottom.toFloat()),
            )
            TimerState.PAUSED -> targets += TimerActionTarget(
                TimerAction.RESUME,
                timer.id,
                "Resume",
                RectF(left, top.toFloat(), clearLeft - gap, bottom.toFloat()),
            )
            TimerState.FINISHED -> Unit
        }
        targets += TimerActionTarget(
            TimerAction.CLEAR,
            timer.id,
            "Clear",
            RectF(clearLeft, top.toFloat(), right, bottom.toFloat()),
        )
        return targets
    }

    private fun selectedTimer(): TimerSnapshot? {
        val sourceId = snapshot.selectedId ?: return null
        val source = snapshot.sourcesById[sourceId] ?: return null
        if (source.kind != IslandSourceKind.TIMER || !sourceId.startsWith("timer:")) return null
        val timerId = sourceId.removePrefix("timer:").takeIf(String::isNotBlank) ?: return null
        return IslandRuntime.timerSnapshots.firstOrNull { it.id == timerId }
    }

    private fun timerActionAt(x: Float, y: Float): TimerActionTarget? =
        timerActionTargets().firstOrNull { it.bounds.contains(x, y) }

    private fun mediaActionTargets(): List<MediaActionTarget> {
        if (snapshot.presentation != IslandPresentation.EXPANDED || snapshot.expandedSourceId != snapshot.selectedId) {
            return emptyList()
        }
        val sourceId = snapshot.selectedId ?: return emptyList()
        if (snapshot.sourcesById[sourceId]?.kind != IslandSourceKind.MEDIA) return emptyList()
        val facts = IslandRuntime.mediaFactsForSource(sourceId) ?: return emptyList()
        val available = facts.playbackActions
        val controls = buildList {
            if (MediaTransportAction.PREVIOUS in available) add(MediaTransportAction.PREVIOUS to "Previous")
            val toggle = when (facts.playbackState) {
                PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING -> when {
                    MediaTransportAction.PAUSE in available -> MediaTransportAction.PAUSE to "Pause"
                    MediaTransportAction.PLAY_PAUSE in available -> MediaTransportAction.PLAY_PAUSE to "Toggle"
                    else -> null
                }
                PlaybackState.STATE_PAUSED -> when {
                    MediaTransportAction.PLAY in available -> MediaTransportAction.PLAY to "Play"
                    MediaTransportAction.PLAY_PAUSE in available -> MediaTransportAction.PLAY_PAUSE to "Toggle"
                    else -> null
                }
                else -> null
            }
            if (toggle != null) add(toggle)
            if (MediaTransportAction.NEXT in available) add(MediaTransportAction.NEXT to "Next")
        }
        if (controls.isEmpty()) return emptyList()
        val actualWidth = width.takeIf { it > 0 } ?: context.dp(304)
        val actualHeight = height.takeIf { it > 0 } ?: context.dp(132)
        val left = context.dp(8).toFloat()
        val right = actualWidth - left
        val gap = context.dp(8).toFloat()
        val controlWidth = (right - left - gap * (controls.size - 1)) / controls.size
        val top = (actualHeight - context.dp(56)).toFloat()
        val bottom = (actualHeight - context.dp(4)).toFloat()
        return controls.mapIndexed { index, (action, label) ->
            val controlLeft = left + index * (controlWidth + gap)
            MediaActionTarget(action, sourceId, label, RectF(controlLeft, top, controlLeft + controlWidth, bottom))
        }
    }

    private fun mediaActionAt(x: Float, y: Float): MediaActionTarget? =
        mediaActionTargets().firstOrNull { it.bounds.contains(x, y) }

    private fun performMediaAccessibilityAction(actionId: Int): Boolean {
        val target = mediaActionTargets().firstOrNull {
            when (actionId) {
                ACCESSIBILITY_MEDIA_PREVIOUS -> it.action == MediaTransportAction.PREVIOUS
                ACCESSIBILITY_MEDIA_NEXT -> it.action == MediaTransportAction.NEXT
                else -> it.action != MediaTransportAction.PREVIOUS && it.action != MediaTransportAction.NEXT
            }
        } ?: return false
        return performMediaAction(target.action, target.sourceId)
    }

    private fun performMediaAction(action: MediaTransportAction, expectedSourceId: String): Boolean {
        if (snapshot.selectedId != expectedSourceId || snapshot.sourcesById[expectedSourceId]?.kind != IslandSourceKind.MEDIA) {
            return false
        }
        if (mediaActionTargets().none { it.action == action && it.sourceId == expectedSourceId }) return false
        val dispatched = IslandRuntime.sendMediaActionForSource(expectedSourceId, action)
        if (dispatched) render(IslandStateEngine.snapshot)
        return dispatched
    }

    private fun performTimerToggleAction(): Boolean {
        val action = timerActionTargets().firstOrNull {
            it.action == TimerAction.PAUSE || it.action == TimerAction.RESUME
        }?.action ?: return false
        return performTimerAction(action)
    }

    private fun performTimerClearAction(): Boolean = performTimerAction(TimerAction.CLEAR)

    private fun performTimerAction(action: TimerAction, expectedTimerId: String? = null): Boolean {
        val timer = selectedTimer() ?: return false
        if (expectedTimerId != null && timer.id != expectedTimerId) return false
        val performed = when (action) {
            TimerAction.PAUSE -> timer.state == TimerState.RUNNING && IslandRuntime.pauseTimer(timer.id)
            TimerAction.RESUME -> timer.state == TimerState.PAUSED && IslandRuntime.resumeTimer(timer.id)
            TimerAction.CLEAR -> IslandRuntime.cancelTimer(timer.id)
        }
        if (performed) render(IslandStateEngine.snapshot)
        return performed
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
