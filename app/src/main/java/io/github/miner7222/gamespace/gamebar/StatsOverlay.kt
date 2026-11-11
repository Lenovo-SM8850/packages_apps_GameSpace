// SPDX-FileCopyrightText: 2026 miner7222
// SPDX-License-Identifier: Apache-2.0

package io.github.miner7222.gamespace.gamebar

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Handler
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import io.chaldeaprjkt.gamespace.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.axion.compose.lifecycle.repeatWhenAttached
import io.chaldeaprjkt.gamespace.data.AppSettings
import io.chaldeaprjkt.gamespace.gamebar.rememberBatteryInfo
import kotlin.math.roundToInt

/**
 * A touch-bounded window whose position and lifetime are independent of the handle. It is as wide
 * as the items it shows; its centre stays put when that width changes, and it snaps to the top
 * centre of the screen when dragged close to it.
 */
class StatsOverlay(
    private val context: Context,
    private val wm: WindowManager,
    private val handler: Handler,
    private val appSettings: AppSettings,
    private val fpsText: State<String>,
) {
    private var view: FrameLayout? = null
    private var landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private val compact = mutableStateOf(appSettings.statsOverlayCompact)
    private val items = PerfOverlayItems.entries(context)
    // The measured window size and the centre the window keeps when that size changes.
    private var stripWidth = 0
    private var stripHeight = 0
    private var centerX = 0
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true
        override fun onDoubleTap(event: MotionEvent): Boolean {
            toggleCompact()
            return true
        }
    }, handler).apply { setIsLongpressEnabled(false) }
    private val idle = mutableStateOf(false)
    private val timeout = Runnable { idle.value = true }
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var startX = 0
    private var startY = 0
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        setFitInsetsTypes(0)
        gravity = Gravity.TOP or Gravity.LEFT
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        preferMinimalPostProcessing = true
        // Re-centring after a size change must jump, not glide in from the old frame.
        privateFlags = privateFlags or WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION
    }
    private val sizeListener = View.OnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
        val width = right - left
        val height = bottom - top
        // A re-added window lays out empty first; keep the last size so it reappears in place.
        if (width <= 0 || height <= 0) return@OnLayoutChangeListener
        if (width == stripWidth && height == stripHeight) return@OnLayoutChangeListener
        stripWidth = width
        stripHeight = height
        // Grow or shrink around the kept centre, also while it is being dragged.
        placeCenter(centerX, params.y)
        updateLayout()
    }

    fun setVisible(visible: Boolean) {
        if (!visible) {
            handler.removeCallbacks(timeout)
            cancelGestures()
            saveDragPosition()
            pointerId = MotionEvent.INVALID_POINTER_ID
            val current = view
            view = null
            if (current != null) runCatching { wm.removeViewImmediate(current) }
            return
        }
        if (view != null) return
        landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        restorePosition()
        idle.value = false
        val content = ComposeView(context)
        val window = object : FrameLayout(context) {
            override fun onInterceptTouchEvent(event: MotionEvent) = true
            override fun onTouchEvent(event: MotionEvent) = handleTouch(event)
        }.apply {
            addView(content, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addOnLayoutChangeListener(sizeListener)
            repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    content.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                    content.setContent {
                        OverlayTheme(context) {
                            StatsStrip(fpsText.value, idle.value, compact.value,
                                items.value.filter { it.shown }.map { it.item })
                        }
                    }
                }
            }
        }
        view = window
        try {
            wm.addView(window, params)
            scheduleIdle()
        } catch (_: RuntimeException) {
            setVisible(false)
        }
    }

    /** Keep the strip reachable when the dashboard window is added above it. */
    fun raiseAbovePanel() {
        val current = view ?: return
        saveDragPosition()
        pointerId = MotionEvent.INVALID_POINTER_ID
        try {
            wm.removeViewImmediate(current)
            wm.addView(current, params)
            scheduleIdle()
        } catch (_: RuntimeException) {
            setVisible(false)
        }
    }

    fun onConfigurationChanged(config: Configuration) {
        cancelGestures()
        saveDragPosition()
        pointerId = MotionEvent.INVALID_POINTER_ID
        val nextLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (landscape != nextLandscape) {
            landscape = nextLandscape
            restorePosition()
        } else {
            placeCenter(centerX, params.y)
        }
        updateLayout()
        if (view != null) scheduleIdle()
    }

    /**
     * The top centre the strip starts at and snaps to: below the status bar while it shows, at the
     * top of the screen when a full screen game hides it.
     */
    private fun anchor(): Pair<Int, Int> {
        val metrics = wm.maximumWindowMetrics
        val types = WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()
        val top = view?.rootWindowInsets?.getInsets(types)?.top
            ?: metrics.windowInsets.getInsets(types).top
        return metrics.bounds.width() / 2 to top + px(8)
    }

    private fun restorePosition() {
        val saved = appSettings.statsOverlayPosition(landscape)
        val (anchorX, anchorY) = anchor()
        placeCenter(saved?.first ?: anchorX, saved?.second ?: anchorY)
    }

    /** Places the strip so its centre is at [x] (kept for later size changes), within the screen. */
    private fun placeCenter(x: Int, y: Int) {
        val bounds = wm.maximumWindowMetrics.bounds
        val width = bounds.width().coerceAtLeast(1)
        val height = bounds.height().coerceAtLeast(1)
        val half = stripWidth / 2
        centerX = x.coerceIn(half, (width - (stripWidth - half)).coerceAtLeast(half))
        params.x = (centerX - half).coerceIn(0, (width - stripWidth).coerceAtLeast(0))
        params.y = y.coerceIn(0, (height - stripHeight).coerceAtLeast(0))
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN || !dragging) gestures.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                wake()
                pointerId = event.getPointerId(0)
                startX = centerX
                startY = params.y
                downX = event.rawX
                downY = event.rawY
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (index < 0) return true
                val dx = event.getRawX(index) - downX
                val dy = event.getRawY(index) - downY
                if (!dragging && dx * dx + dy * dy > touchSlop * touchSlop) {
                    dragging = true
                    cancelGestures()
                }
                if (dragging) {
                    placeCenter(startX + dx.roundToInt(), startY + dy.roundToInt())
                    updateLayout()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == pointerId) {
                    val index = if (event.actionIndex == 0) 1 else 0
                    pointerId = event.getPointerId(index)
                    startX = centerX
                    startY = params.y
                    downX = event.getRawX(index)
                    downY = event.getRawY(index)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (pointerId == MotionEvent.INVALID_POINTER_ID) return true
                if (dragging) appSettings.saveStatsOverlayPosition(landscape, centerX, params.y)
                dragging = false
                pointerId = MotionEvent.INVALID_POINTER_ID
                scheduleIdle()
            }
        }
        return true
    }

    private fun cancelGestures() {
        val now = android.os.SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        gestures.onTouchEvent(cancel)
        cancel.recycle()
    }

    private fun toggleCompact() {
        compact.value = !compact.value
        appSettings.statsOverlayCompact = compact.value
    }

    private fun updateLayout() {
        view?.let { runCatching { wm.updateViewLayout(it, params) } }
    }

    private fun saveDragPosition() {
        if (pointerId != MotionEvent.INVALID_POINTER_ID && dragging) {
            appSettings.saveStatsOverlayPosition(landscape, centerX, params.y)
        }
    }

    private fun wake() {
        handler.removeCallbacks(timeout)
        idle.value = false
    }

    private fun scheduleIdle() {
        handler.removeCallbacks(timeout)
        if (view != null) handler.postDelayed(timeout, 3000L)
    }

    private fun px(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()

    private companion object {
        /** How close to the top centre the strip has to be dragged to snap to it. */
        const val SNAP_DP = 40
    }
}

@Composable
private fun StatsStrip(fpsText: String, idle: Boolean, compact: Boolean, items: List<PerfItem>) {
    val battery = rememberBatteryInfo()
    val values by rememberPerfValues(items.toSet())
    val opacity = animateFloatAsState(if (idle) 0.7f else 1f,
        animationSpec = tween(if (idle) 350 else 0), label = "statsOpacity")
    val shown = items.map { item ->
        Triple(perfValueText(item, fpsText, values, battery.temperatureC, battery.level),
            stringResource(item.labelRes), stringResource(item.nameRes))
    }
    Box(Modifier.height(if (compact) 28.dp else 48.dp)
        .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = overlayPanelAlpha()),
            RoundedCornerShape(16.dp))
        .border(overlayBorder(), RoundedCornerShape(16.dp))
        .padding(horizontal = if (compact) 10.dp else 12.dp, vertical = if (compact) 2.dp else 4.dp),
        contentAlignment = Alignment.Center) {
        if (compact) {
            // Without labels the frame rate carries its unit, as the clocks do.
            val fpsUnit = stringResource(R.string.perf_value_fps, "%s")
            Text(shown.zip(items).joinToString(" · ") { (entry, item) ->
                if (item == PerfItem.FPS && entry.first != "–") fpsUnit.format(entry.first)
                else entry.first
            },
                Modifier.graphicsLayer { alpha = opacity.value }
                    .clearAndSetSemantics {
                        contentDescription = shown.joinToString(" · ") { "${it.third}: ${it.first}" }
                    },
                color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, lineHeight = 20.sp,
                fontWeight = FontWeight.Bold, maxLines = 1,
                style = TextStyle(fontFeatureSettings = "tnum"))
        } else Row(Modifier.fillMaxHeight().graphicsLayer { alpha = opacity.value },
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            val width = rememberStatWidth()
            shown.forEach { (value, label, name) -> StatValue(value, label, name, width) }
        }
    }
}

private val StatValueStyle = TextStyle(fontSize = 17.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold,
    fontFeatureSettings = "tnum")

/**
 * The width of every item: the widest value any item can show, so items line up the same whatever
 * is shown and however the values change.
 */
@Composable
private fun rememberStatWidth(): Int {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val samples = listOf("144", "100%", "99°", stringResource(R.string.perf_value_ghz, "4.47"),
        stringResource(R.string.perf_value_mhz, 1200))
    return remember(samples, density) {
        maxOf(samples.maxOf { measurer.measure(it, StatValueStyle).size.width },
            with(density) { 36.dp.roundToPx() })
    }
}

/**
 * A value over its label, [width] pixels wide. A label that does not fit scrolls within it, so a
 * long label in some language does not widen its item.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StatValue(value: String, label: String, name: String, width: Int) {
    Layout(content = {
        Text(value, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, style = StatValueStyle)
        Text(label, Modifier.basicMarquee(iterations = Int.MAX_VALUE),
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 14.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp, maxLines = 1)
    }, modifier = Modifier.clearAndSetSemantics { contentDescription = "$name: $value" }) { parts, _ ->
        val top = parts[0].measure(Constraints(maxWidth = width))
        val bottom = parts[1].measure(Constraints(maxWidth = width))
        layout(width, top.height + bottom.height) {
            top.place((width - top.width) / 2, 0)
            bottom.place((width - bottom.width) / 2, top.height)
        }
    }
}
