// SPDX-FileCopyrightText: 2026 miner7222
// SPDX-License-Identifier: Apache-2.0

package io.github.miner7222.gamespace.gamebar

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Handler
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
    private val onTap: () -> Unit,
) {
    private var view: FrameLayout? = null
    private var landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
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
            isClickable = true
            contentDescription = context.getString(R.string.gamebar_open_controls)
            setOnClickListener {
                wake()
                onTap()
                scheduleIdle()
            }
            addView(content, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    content.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                    content.setContent {
                        OverlayTheme(context) {
                            StatsStrip(fpsText.value, idle.value)
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

    /** Keep the strip reachable when the full-screen dashboard window is added above it. */
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
        clampPosition(saved?.first ?: ((metrics.bounds.width() - px(184)) / 2),
            saved?.second ?: (insets.top + px(8)))
    }

    private fun clampPosition(x: Int, y: Int) {
        val bounds = wm.maximumWindowMetrics.bounds
        val width = bounds.width().coerceAtLeast(1)
        val height = bounds.height().coerceAtLeast(1)
        params.width = px(184).coerceAtMost(width)
        params.height = px(48).coerceAtMost(height)
        params.x = x.coerceIn(0, width - params.width)
        params.y = y.coerceIn(0, height - params.height)
    }

    private fun handleTouch(event: MotionEvent): Boolean {
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
                if (dx * dx + dy * dy > touchSlop * touchSlop) dragging = true
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
                else if (event.actionMasked == MotionEvent.ACTION_UP) view?.performClick()
                pointerId = MotionEvent.INVALID_POINTER_ID
                scheduleIdle()
            }
        }
        return true
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
private fun StatsStrip(fpsText: String, idle: Boolean) {
    val battery = rememberBatteryInfo()
    val opacity = animateFloatAsState(if (idle) 0.7f else 1f,
        animationSpec = tween(if (idle) 350 else 0), label = "statsOpacity")
    Box(Modifier.fillMaxSize()
        .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = overlayPanelAlpha()),
            RoundedCornerShape(16.dp))
        .border(overlayBorder(), RoundedCornerShape(16.dp))
        .padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxSize().graphicsLayer { alpha = opacity.value },
            verticalAlignment = Alignment.CenterVertically) {
            StatValue(fpsText.ifEmpty { "–" }, stringResource(R.string.gamebar_fps_label), Modifier.weight(1f))
            StatValue(stringResource(R.string.dashboard_temperature, battery.temperatureC.roundToInt()),
                stringResource(R.string.gamebar_temperature_label), Modifier.weight(1f))
            StatValue(if (battery.level >= 0) "${battery.level}%" else "–",
                stringResource(R.string.gamebar_battery_label), Modifier.weight(1f),
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
