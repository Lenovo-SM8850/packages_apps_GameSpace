// SPDX-FileCopyrightText: 2026 miner7222
// SPDX-License-Identifier: Apache-2.0

package io.github.miner7222.gamespace.gamebar

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Handler
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import io.chaldeaprjkt.gamespace.R
import io.chaldeaprjkt.gamespace.data.AppSettings
import io.chaldeaprjkt.gamespace.gamebar.rememberBatteryInfo
import io.github.miner7222.gamespace.FitLabel
import io.github.miner7222.gamespace.shortLabelResource
import kotlin.math.roundToInt

/** A touch-bounded window whose position and lifetime are independent of the handle. */
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
    private var stripWidth = px(if (compact.value) 128 else 184)
    private var stripHeight = px(if (compact.value) 28 else 48)
    private var resizeAnimator: ValueAnimator? = null
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
    }

    fun setVisible(visible: Boolean) {
        if (!visible) {
            handler.removeCallbacks(timeout)
            cancelGestures()
            resizeAnimator?.cancel()
            resizeAnimator = null
            saveDragPosition()
            pointerId = MotionEvent.INVALID_POINTER_ID
            val current = view
            view = null
            if (current != null) runCatching { wm.removeViewImmediate(current) }
            return
        }
        if (view != null) return
        landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        stripWidth = px(if (compact.value) 128 else 184)
        stripHeight = px(if (compact.value) 28 else 48)
        restorePosition()
        idle.value = false
        val content = ComposeView(context)
        val window = object : FrameLayout(context) {
            override fun onInterceptTouchEvent(event: MotionEvent) = true
            override fun onTouchEvent(event: MotionEvent) = handleTouch(event)
        }.apply {
            addView(content, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    content.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                    content.setContent {
                        OverlayTheme(context) {
                            StatsStrip(fpsText.value, idle.value, compact.value)
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
        resizeAnimator?.cancel()
        resizeAnimator = null
        stripWidth = px(if (compact.value) 128 else 184)
        stripHeight = px(if (compact.value) 28 else 48)
        saveDragPosition()
        pointerId = MotionEvent.INVALID_POINTER_ID
        val nextLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (landscape != nextLandscape) {
            landscape = nextLandscape
            restorePosition()
        } else {
            clampPosition(params.x, params.y)
        }
        updateLayout()
        if (view != null) scheduleIdle()
    }

    private fun restorePosition() {
        val saved = appSettings.statsOverlayPosition(landscape)
        val metrics = wm.maximumWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        clampPosition(saved?.first ?: ((metrics.bounds.width() - stripWidth) / 2),
            saved?.second ?: (insets.top + px(8)))
    }

    private fun clampPosition(x: Int, y: Int) {
        val bounds = wm.maximumWindowMetrics.bounds
        val width = bounds.width().coerceAtLeast(1)
        val height = bounds.height().coerceAtLeast(1)
        params.width = stripWidth.coerceAtMost(width)
        params.height = stripHeight.coerceAtMost(height)
        params.x = x.coerceIn(0, width - params.width)
        params.y = y.coerceIn(0, height - params.height)
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN || !dragging) gestures.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                wake()
                pointerId = event.getPointerId(0)
                startX = params.x
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
                    clampPosition(startX + dx.roundToInt(), startY + dy.roundToInt())
                    updateLayout()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == pointerId) {
                    val index = if (event.actionIndex == 0) 1 else 0
                    pointerId = event.getPointerId(index)
                    startX = params.x
                    startY = params.y
                    downX = event.getRawX(index)
                    downY = event.getRawY(index)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (pointerId == MotionEvent.INVALID_POINTER_ID) return true
                if (dragging) appSettings.saveStatsOverlayPosition(landscape, params.x, params.y)
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
        resizeAnimator?.cancel()
        val fromWidth = params.width
        val fromHeight = params.height
        val centerX = params.x + fromWidth / 2f
        val top = params.y
        val toWidth = px(if (compact.value) 128 else 184)
        val toHeight = px(if (compact.value) 28 else 48)
        resizeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220L
            addUpdateListener {
                val progress = it.animatedValue as Float
                stripWidth = (fromWidth + (toWidth - fromWidth) * progress).roundToInt()
                stripHeight = (fromHeight + (toHeight - fromHeight) * progress).roundToInt()
                clampPosition((centerX - stripWidth / 2f).roundToInt(), top)
                updateLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    appSettings.saveStatsOverlayPosition(landscape, params.x, params.y)
                }
            })
            start()
        }
    }

    private fun updateLayout() {
        view?.let { runCatching { wm.updateViewLayout(it, params) } }
    }

    private fun saveDragPosition() {
        if (pointerId != MotionEvent.INVALID_POINTER_ID && dragging) {
            appSettings.saveStatsOverlayPosition(landscape, params.x, params.y)
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
}

@Composable
private fun StatsStrip(fpsText: String, idle: Boolean, compact: Boolean) {
    val battery = rememberBatteryInfo()
    val opacity = animateFloatAsState(if (idle) 0.7f else 1f,
        animationSpec = tween(if (idle) 350 else 0), label = "statsOpacity")
    val fps = fpsText.ifEmpty { "–" }
    val temperature = stringResource(R.string.dashboard_temperature, battery.temperatureC.roundToInt())
    val level = if (battery.level >= 0) "${battery.level}%" else "–"
    val fpsLabel = stringResource(R.string.gamebar_fps_label)
    val temperatureLabel = stringResource(R.string.gamebar_temperature_label)
    val batteryLabel = stringResource(R.string.gamebar_battery_label)
    Box(Modifier.fillMaxSize()
        .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = overlayPanelAlpha()),
            RoundedCornerShape(16.dp))
        .border(overlayBorder(), RoundedCornerShape(16.dp))
        .padding(horizontal = if (compact) 6.dp else 8.dp, vertical = if (compact) 2.dp else 4.dp)) {
        if (compact) {
            Text("$fps · $temperature · $level",
                Modifier.align(Alignment.Center).graphicsLayer { alpha = opacity.value }
                    .clearAndSetSemantics {
                        contentDescription = "$fpsLabel: $fps · $temperatureLabel: $temperature · $batteryLabel: $level"
                    },
                color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, lineHeight = 20.sp,
                fontWeight = FontWeight.Bold, maxLines = 1,
                style = TextStyle(fontFeatureSettings = "tnum"))
        } else Row(Modifier.fillMaxSize().graphicsLayer { alpha = opacity.value },
            verticalAlignment = Alignment.CenterVertically) {
            StatValue(fps, fpsLabel, Modifier.weight(1f))
            StatValue(temperature, temperatureLabel, Modifier.weight(1f))
            StatValue(level, batteryLabel, Modifier.weight(1f),
                shortLabelResource(R.string.gamebar_battery_label_short))
        }
    }
}

@Composable
private fun StatValue(value: String, label: String, modifier: Modifier, shortLabel: String? = null) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Text(value, color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp, lineHeight = 20.sp,
            fontWeight = FontWeight.Bold, style = TextStyle(fontFeatureSettings = "tnum"))
        FitLabel(label, shortLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 14.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp)
    }
}
