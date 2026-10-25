// SPDX-License-Identifier: Apache-2.0
package io.github.miner7222.gamespace

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import io.chaldeaprjkt.gamespace.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Moves a letterboxed game window between the center and the bottom of the display with a
 * double swipe across the black bars: down in either bar while centered, up in the bar above
 * the game while at the bottom. Each bar gets its own overlay window that never covers the game.
 */
class GameAspectRatioGesture(
    private val context: Context,
    private val wm: WindowManager,
    private val handler: Handler,
    private val tile: GameAspectRatioTile,
) {
    /** Screen-space touch area of a bar, and the placement the swipe leaves. */
    private data class Bar(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val bottomPlacement: Boolean,
        val threshold: Float,
    )

    private val density = context.resources.displayMetrics.density
    private var gameVisible = false
    // Set while the game bar or any GameSpace panel UI is expanded above the game.
    private var uiBlocked = false
    private var released = false
    private var bars = emptyList<Bar>()
    private val views = mutableListOf<View>()
    // Placement the first swipe was made in, or null when no swipe is pending.
    private var armedBottom: Boolean? = null
    private var armedAt = 0L

    init {
        tile.onChanged = { handler.post { update() } }
    }

    fun setGameVisible(visible: Boolean) {
        if (gameVisible == visible) return
        gameVisible = visible
        update()
    }

    fun setUiBlocked(blocked: Boolean) {
        if (uiBlocked == blocked) return
        uiBlocked = blocked
        update()
    }

    fun onConfigurationChanged() = update()

    fun release() {
        released = true
        tile.onChanged = null
        removeViews()
        bars = emptyList()
    }

    private fun update() {
        if (released) return
        val next = if (gameVisible && !uiBlocked) computeBars() else emptyList()
        if (next == bars && next.size == views.size) return
        if (next.firstOrNull()?.bottomPlacement != bars.firstOrNull()?.bottomPlacement) {
            armedBottom = null
        }
        val resize = next.size == views.size
        bars = next
        if (!resize) removeViews()
        next.forEachIndexed { index, bar ->
            val params = layoutParams(bar)
            if (resize) {
                runCatching { wm.updateViewLayout(views[index], params) }
            } else {
                val overlay = View(context)
                overlay.setOnTouchListener(SwipeListener(index))
                if (runCatching { wm.addView(overlay, params) }.isSuccess) views.add(overlay)
            }
        }
    }

    private fun removeViews() {
        views.forEach { runCatching { wm.removeViewImmediate(it) } }
        views.clear()
    }

    private fun layoutParams(bar: Bar) = WindowManager.LayoutParams(
        bar.width,
        bar.height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSPARENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = bar.left
        y = bar.top
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        title = "GameAspectRatioGesture"
    }

    // Mirrors the framework's game window sizing for supported centered or bottom ratios.
    private fun computeBars(): List<Bar> {
        if (!tile.isAvailable) return emptyList()
        val mode = tile.currentMode ?: return emptyList()
        if (mode == GameAspectRatioTile.DEEP_VIEW) return emptyList()
        val bottom = mode.startsWith(GameAspectRatioTile.BOTTOM_PREFIX)
        val ratio = mode.substringAfter(':').toFloatOrNull() ?: return emptyList()
        val metrics = wm.currentWindowMetrics
        val bounds = metrics.bounds
        val width = bounds.width()
        val height = bounds.height()
        // Only landscape displays get black bars above and below the game.
        if (width <= height || ratio <= 0f) return emptyList()
        // A display wider than the ratio is pillarboxed instead.
        if (!bottom && width.toFloat() / height > ratio) return emptyList()
        val gameHeight = (width / ratio).toInt()
        if (gameHeight <= 0 || gameHeight >= height) return emptyList()

        // Only the current edge-gesture zones and the cutout are excluded; the taskbar and
        // status bar insets stay usable because swipes here start away from the screen edge.
        val insets = runCatching {
            metrics.windowInsets.getInsets(WindowInsets.Type.systemGestures() or
                WindowInsets.Type.mandatorySystemGestures() or WindowInsets.Type.displayCutout())
        }.getOrNull()
        // Keep clear of the game's edge, the system gesture areas and the docked game bar.
        val fallback = (EDGE_MARGIN_DP * density).toInt()
        val safety = (4 * density).toInt()
        val side = (56 * density).toInt()
        val left = max(insets?.left ?: 0, side)
        val right = width - max(insets?.right ?: 0, side)
        val barWidth = right - left
        val topEdge = (insets?.top ?: 0).takeIf { it > 0 } ?: fallback
        val bottomEdge = height - ((insets?.bottom ?: 0).takeIf { it > 0 } ?: fallback)
        val gameTop = if (bottom) height - gameHeight else (height - gameHeight) / 2
        val gameBottom = gameTop + gameHeight
        // The bar above the game always exists; the one below only while centered.
        val spans = buildList {
            add(topEdge to gameTop - safety)
            if (!bottom) add(gameBottom + safety to bottomEdge)
        }
        return spans.mapNotNull { (top, end) ->
            val barHeight = end - top
            // A bar too small for a reliable swipe gets no overlay.
            if (barWidth < 160 * density || barHeight < MIN_BAR_DP * density) null
            else Bar(left, top, barWidth, barHeight, bottom, min(48 * density, barHeight / 2f))
        }
    }

    private fun onSwipe(swiped: Bar) {
        val now = SystemClock.uptimeMillis()
        if (armedBottom == swiped.bottomPlacement && now - armedAt <= CONFIRM_WINDOW_MS) {
            armedBottom = null
            tile.setBottomPlacement(!swiped.bottomPlacement)
            return
        }
        armedBottom = swiped.bottomPlacement
        armedAt = now
        Toast.makeText(context, if (swiped.bottomPlacement) R.string.aspect_ratio_swipe_up_again
            else R.string.aspect_ratio_swipe_down_again, Toast.LENGTH_SHORT).show()
    }

    private inner class SwipeListener(private val index: Int) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var handled = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val current = bars.getOrNull(index) ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    handled = false
                }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> if (!handled) {
                    // Down in either bar while centered, up while at the bottom.
                    val travel = if (current.bottomPlacement) downY - event.rawY
                        else event.rawY - downY
                    if (travel >= current.threshold && travel > abs(event.rawX - downX)) {
                        handled = true
                        onSwipe(current)
                    }
                }
            }
            return true
        }
    }

    private companion object {
        const val CONFIRM_WINDOW_MS = 3000L
        const val EDGE_MARGIN_DP = 24
        const val MIN_BAR_DP = 40
    }
}
