/*
 * Copyright (C) 2020 The exTHmUI Open Source Project
 * Copyright (C) 2021 AOSP-Krypton Project
 * Copyright (C) 2022 Nameless-AOSP Project
 * Copyright (C) 2023 the risingOS android Project
 * Copyright (C) 2022-2024 crDroid Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.chaldeaprjkt.gamespace.gamebar

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.os.SystemClock
import android.os.UserHandle
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceControl
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.view.WindowManagerGlobal
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import android.window.ScreenCapture
import android.window.ScreenCaptureInternal

import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ServiceScoped

import java.util.ArrayDeque
import java.util.concurrent.Executors

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

import javax.inject.Inject

import io.chaldeaprjkt.gamespace.R
import io.chaldeaprjkt.gamespace.data.AppSettings

interface DanmakuServiceInterface {
    val danmakuNotificationMode: Boolean
    fun showNotificationAsOverlay(danmakuText: String)
    fun getApplabel(packageName: String): String
}

@ServiceScoped
class DanmakuService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appSettings: AppSettings
) : DanmakuServiceInterface {

    private lateinit var notificationListener: DanmakuServiceListener

    /** A horizontal track that holds one line of moving text at a time. */
    private class Lane(val top: Int)

    /** A notification waiting for a free lane. Width and speed are measured on demand. */
    private class Pending(val text: String) {
        var width = 0
        var speed = 0f
        var prepared = false
    }

    /** A notification crossing the screen. Positions follow from the start time and speed. */
    private class Danmaku(
        val text: String,
        val view: TextView,
        val backing: GradientDrawable,
        val lane: Lane,
        val width: Int,
        val speed: Float,
        val startTime: Long,
        val duration: Long
    ) {
        var finished = false
        var backingVisible = false
        var darkSamples = 0
        var backingAnimator: ValueAnimator? = null
        var endRunnable: Runnable? = null
    }

    private class SampleTarget(val danmaku: Danmaku, val bounds: Rect)

    private val density = context.resources.displayMetrics.density

    private val windowManager: WindowManager = context.getSystemService(WindowManager::class.java)!!

    private val handler = Handler(Looper.getMainLooper())

    // The container only holds the moving lines. It never takes touches or focus.
    private val container = FrameLayout(context).apply {
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        isFocusable = false
        isClickable = false
    }

    private val measurer = createTextView("")

    private val pending = ArrayDeque<Pending>()
    private val active = ArrayList<Danmaku>()
    private val lastInLane = HashMap<Lane, Danmaku>()
    private var lanes: List<Lane> = emptyList()

    private var layoutParams: LayoutParams = LayoutParams().apply {
        flags = flags or LayoutParams.FLAG_NOT_FOCUSABLE or
                LayoutParams.FLAG_NOT_TOUCHABLE or
                LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                LayoutParams.FLAG_HARDWARE_ACCELERATED
        type = LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY
        format = PixelFormat.TRANSLUCENT
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
        setTitle(TAG)
    }

    private var isPortrait: Boolean =
            context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    private var verticalOffsetLandscape = 0
    private var verticalOffsetPortrait = 0

    private var screenWidth = 0
    private var screenHeight = 0
    private var lineHeight = 0
    private var bandTop = 0
    private var laneGap = 0f
    private var drainAfterAttach = false

    private val samplingExecutor = Executors.newSingleThreadExecutor()
    private var samplingEnabled = false
    private var samplingInFlight = false
    private var samplingGeneration = 0
    private var destroyed = false
    private val sampleBrightness = Runnable { sampleBackgroundBrightness() }
    private val drainRunnable = Runnable { drainPending() }

    fun init() {
        notificationListener = DanmakuServiceListener()
        notificationListener.danmakuServiceInterface = this
        updateParams()
        registerListener()
    }

    fun updateConfiguration(newConfig: Configuration) {
        isPortrait = newConfig.orientation == Configuration.ORIENTATION_PORTRAIT
        // Lanes and speeds depend on the screen size, so lines in flight start over in the new
        // geometry instead of being lost.
        val replay = active.map { Pending(it.text) }
        clearActive()
        removeWindow()
        replay.asReversed().forEach { pending.addFirst(it) }
        updateParams()
        pending.forEach { it.prepared = false }
        if (pending.isNotEmpty()) drainPending()
    }

    fun destroy() {
        destroyed = true
        unregisterListener()
        handler.removeCallbacksAndMessages(null)
        pending.clear()
        clearActive()
        removeWindow()
        samplingExecutor.shutdownNow()
    }

    private fun registerListener() {
        val componentName = ComponentName(context, DanmakuService::class.java)
        try {
            notificationListener.registerAsSystemService(
                context,
                componentName,
                UserHandle.USER_CURRENT
            )
        } catch (e: RemoteException) {
            Log.e(TAG, "RemoteException while registering danmaku service")
        }
    }

    private fun unregisterListener() {
        try {
            notificationListener.unregisterAsSystemService()
        } catch (e: RemoteException) {
            Log.e(TAG, "RemoteException while registering danmaku service")
        }
    }

    private fun createTextView(text: String): TextView = TextView(context).apply {
        layoutParams = FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT)
        maxLines = 1
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(Color.WHITE)
        setPadding((8 * density).toInt(), (4 * density).toInt(),
                (8 * density).toInt(), (4 * density).toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_PX, NOTIFICATION_SIZE.toFloat())
        isFocusable = false
        isClickable = false
        this.text = text
    }

    private fun updateParams() {
        with(context.resources) {
            verticalOffsetLandscape =
                getDimensionPixelSize(R.dimen.notification_vertical_offset_landscape)
            verticalOffsetPortrait =
                getDimensionPixelSize(R.dimen.notification_vertical_offset_portrait)
        }
        val metrics = windowManager.currentWindowMetrics
        screenWidth = metrics.bounds.width()
        screenHeight = metrics.bounds.height()
        laneGap = LANE_GAP_DP * density

        // Every line has the same height, so one probe line sizes all lanes.
        measurer.text = LINE_HEIGHT_PROBE
        measurer.measure(
            View.MeasureSpec.makeMeasureSpec(screenWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.UNSPECIFIED
        )
        lineHeight = measurer.measuredHeight
        val pitch = lineHeight + (LANE_SPACING_DP * density).toInt()

        // Keep the text of the base lane where the single line used to sit.
        val baseTop = getOffsetForPosition() - measurer.paddingTop
        val cutout = metrics.windowInsets.displayCutout
        val safeTop = cutout?.safeInsetTop ?: 0
        val safeBottom = cutout?.safeInsetBottom ?: 0
        val cutoutRects = cutout?.boundingRects ?: emptyList()
        fun usable(top: Int): Boolean {
            val bottom = top + lineHeight
            return top >= safeTop && bottom <= screenHeight - safeBottom &&
                    cutoutRects.none { it.top < bottom && it.bottom > top }
        }

        // Alternate around the base lane, towards the roomier side of the screen first.
        val belowFirst = baseTop + lineHeight / 2 < screenHeight / 2
        val candidates = ArrayList<Int>()
        candidates.add(baseTop)
        for (step in 1..MAX_LANE_STEPS) {
            val first = if (belowFirst) step else -step
            candidates.add(baseTop + first * pitch)
            candidates.add(baseTop - first * pitch)
        }
        lanes = candidates.filter { usable(it) }.take(MAX_LANES).map { Lane(it) }
            .ifEmpty {
                val lowest = max(safeTop, screenHeight - safeBottom - lineHeight)
                listOf(Lane(baseTop.coerceIn(safeTop, lowest)))
            }
        bandTop = lanes.minOf { it.top }
        val bandBottom = lanes.maxOf { it.top } + lineHeight

        layoutParams.x = 0
        layoutParams.y = bandTop
        layoutParams.width = screenWidth
        layoutParams.height = bandBottom - bandTop
    }

    private fun getOffsetForPosition(): Int {
        return if (isPortrait) verticalOffsetPortrait else verticalOffsetLandscape
    }

    override fun showNotificationAsOverlay(notification: String) {
        if (destroyed) return
        val text = notification.replace(WHITESPACE, " ").trim()
        if (text.isEmpty()) return
        pending.addLast(Pending(text))
        // A flood must not grow without bound: the oldest waiting line goes first.
        while (pending.size > MAX_PENDING) pending.removeFirst()
        drainPending()
    }

    override fun getApplabel(packageName: String): String {
        return packageName.let {
                try {
                    val appInfo = context.packageManager.getApplicationInfo(it, 0)
                    context.packageManager.getApplicationLabel(appInfo).toString()
                } catch (e: Exception) {
                    ""
                }
            }
    }

    override val danmakuNotificationMode: Boolean
        get() = appSettings.danmakuNotification

    /** Moves waiting lines onto the nearest free lanes, or schedules the next attempt. */
    private fun drainPending() {
        handler.removeCallbacks(drainRunnable)
        if (destroyed || pending.isEmpty()) return
        if (!container.isAttachedToWindow) {
            // Lines start together once the window exists, so lane timing stays exact.
            if (container.parent == null) addWindow()
            if (!drainAfterAttach) {
                drainAfterAttach = true
                container.post {
                    drainAfterAttach = false
                    drainPending()
                }
            }
            return
        }
        val now = SystemClock.uptimeMillis()
        var nextTry = Long.MAX_VALUE
        while (pending.isNotEmpty()) {
            val line = pending.first()
            prepare(line)
            var chosen: Lane? = null
            for (lane in lanes) {
                val enter = earliestEnter(lane, line)
                if (enter <= now) {
                    chosen = lane
                    break
                }
                nextTry = min(nextTry, enter)
            }
            if (chosen == null) break
            pending.removeFirst()
            place(line, chosen, now)
        }
        if (pending.isNotEmpty()) {
            handler.postDelayed(drainRunnable,
                    (nextTry - now).coerceIn(MIN_RETRY_DELAY, MAX_RETRY_DELAY))
        }
    }

    private fun prepare(line: Pending) {
        if (line.prepared) return
        measurer.text = line.text
        measurer.measure(
            View.MeasureSpec.makeMeasureSpec(screenWidth * 2, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.UNSPECIFIED
        )
        line.width = measurer.measuredWidth
        // Wider lines move a bit faster so that long text does not hang on screen for too long.
        val base = screenWidth / CROSSING_TIME_MS
        val boost = 1f + (MAX_SPEED_BOOST - 1f) * min(line.width.toFloat() / screenWidth, 1f)
        line.speed = base * boost
        line.prepared = true
    }

    /**
     * Earliest uptime at which a line may enter the lane at the right edge: the previous line
     * must have moved clear of the edge, and a faster new line must not run into the previous
     * one before that one has left on the left.
     */
    private fun earliestEnter(lane: Lane, line: Pending): Long {
        val last = lastInLane[lane] ?: return 0L
        val clearAt = last.startTime + ((last.width + laneGap) / last.speed).toLong() +
                FRAME_SKEW_MS
        val exitAt = last.startTime + ((screenWidth + last.width) / last.speed).toLong()
        val catchUpAt = exitAt - ((screenWidth - laneGap) / line.speed).toLong()
        return max(clearAt, catchUpAt)
    }

    private fun place(line: Pending, lane: Lane, now: Long) {
        val view = createTextView(line.text)
        val backing = GradientDrawable().apply {
            setColor(Color.BLACK)
            cornerRadius = 8f * density
            alpha = 0
        }
        view.background = backing
        view.layoutParams = FrameLayout.LayoutParams(line.width, lineHeight,
                Gravity.TOP or Gravity.LEFT).apply { topMargin = lane.top - bandTop }
        // The line starts just outside the right edge and ends just outside the left edge.
        view.translationX = screenWidth.toFloat()
        val duration = ((screenWidth + line.width) / line.speed).toLong()
        val danmaku = Danmaku(line.text, view, backing, lane, line.width, line.speed, now,
                duration)
        active.add(danmaku)
        lastInLane[lane] = danmaku
        container.addView(view)
        // Property animation of the translation runs on the render thread, without layout.
        view.animate()
            .translationX(-line.width.toFloat())
            .setDuration(duration)
            .setInterpolator(LinearInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    finish(danmaku)
                }
            })
            .start()
        // Backstop for animations that never report their end, such as with the screen off.
        val end = Runnable { finish(danmaku) }
        danmaku.endRunnable = end
        handler.postDelayed(end, duration + END_BACKSTOP_MS)
        ensureBrightnessSampling()
    }

    private fun finish(danmaku: Danmaku) {
        if (danmaku.finished) return
        danmaku.finished = true
        danmaku.endRunnable?.let { handler.removeCallbacks(it) }
        danmaku.backingAnimator?.cancel()
        danmaku.view.animate().cancel()
        container.removeView(danmaku.view)
        active.remove(danmaku)
        if (active.isEmpty()) {
            stopBrightnessSampling()
            if (pending.isEmpty()) removeWindow()
        }
    }

    private fun clearActive() {
        stopBrightnessSampling()
        active.toList().forEach { danmaku ->
            danmaku.finished = true
            danmaku.endRunnable?.let { handler.removeCallbacks(it) }
            danmaku.backingAnimator?.cancel()
            danmaku.view.animate().cancel()
        }
        container.removeAllViews()
        active.clear()
        lastInLane.clear()
    }

    private fun addWindow() {
        if (container.parent != null) return
        try {
            windowManager.addView(container, layoutParams)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Failed to add the danmaku window", e)
        }
    }

    private fun removeWindow() {
        stopBrightnessSampling()
        if (container.parent != null) {
            try {
                windowManager.removeViewImmediate(container)
            } catch (e: RuntimeException) {
                Log.e(TAG, "Failed to remove the danmaku window", e)
            }
        }
    }

    private fun ensureBrightnessSampling() {
        if (samplingEnabled || destroyed || !danmakuNotificationMode) return
        samplingEnabled = true
        handler.post(sampleBrightness)
    }

    private fun stopBrightnessSampling() {
        samplingEnabled = false
        samplingGeneration++
        handler.removeCallbacks(sampleBrightness)
    }

    /** Where the readable part of a line is on the screen, if visible. */
    private fun getTextBoundsOnScreen(danmaku: Danmaku): Rect? {
        val view = danmaku.view
        val left = view.left + view.translationX
        return Rect(
            floor(left).toInt() + view.paddingLeft,
            danmaku.lane.top + view.paddingTop,
            ceil(left).toInt() + danmaku.width - view.paddingRight,
            danmaku.lane.top + lineHeight - view.paddingBottom
        ).takeIf { it.intersect(0, 0, screenWidth, screenHeight) && !it.isEmpty }
    }

    private fun sampleBackgroundBrightness() {
        if (!samplingEnabled) return
        if (!danmakuNotificationMode || active.isEmpty()) {
            stopBrightnessSampling()
            return
        }
        if (samplingInFlight) return
        val targets = active.mapNotNull { danmaku ->
            getTextBoundsOnScreen(danmaku)?.let { SampleTarget(danmaku, it) }
        }
        val surface = container.viewRootImpl?.surfaceControl
        if (targets.isEmpty() || surface == null || !surface.isValid) {
            handler.postDelayed(sampleBrightness, BRIGHTNESS_SAMPLE_INTERVAL)
            return
        }

        // One capture of the lane band serves every line in it.
        val band = Rect(0, layoutParams.y, screenWidth, layoutParams.y + layoutParams.height)
        // Keep our surface alive until the capture request has been sent on the worker.
        val excludedSurface = SurfaceControl(surface, TAG)
        val displayId = container.display.displayId
        val generation = samplingGeneration
        samplingInFlight = true
        samplingExecutor.execute {
            val shares = try {
                captureBrightShares(band, targets, displayId, excludedSurface)
            } catch (e: RemoteException) {
                null
            } catch (e: RuntimeException) {
                null
            } finally {
                excludedSurface.release()
            }
            handler.post {
                samplingInFlight = false
                if (samplingEnabled) {
                    if (generation == samplingGeneration) {
                        targets.forEach { target ->
                            val danmaku = target.danmaku
                            if (danmaku.finished) return@forEach
                            // Keep text readable if a capture is temporarily unavailable.
                            if (shares == null) {
                                updateBacking(danmaku, null)
                            } else {
                                shares[danmaku]?.let { updateBacking(danmaku, it) }
                            }
                        }
                    }
                    handler.postDelayed(sampleBrightness, BRIGHTNESS_SAMPLE_INTERVAL)
                }
            }
        }
    }

    private class BackgroundCaptureArgsBuilder :
            ScreenCaptureInternal.CaptureArgs.Builder<BackgroundCaptureArgsBuilder>()

    private fun captureBrightShares(band: Rect, targets: List<SampleTarget>, displayId: Int,
            excludedSurface: SurfaceControl): Map<Danmaku, Float>? {
        val scale = min(1f, SAMPLE_MAX_WIDTH.toFloat() / band.width())
        val args = BackgroundCaptureArgsBuilder()
            .setSourceCrop(band)
            .setFrameScale(scale)
            .setPixelFormat(PixelFormat.RGBA_8888)
            .setSecureContentPolicy(ScreenCapture.ScreenCaptureParams.SECURE_CONTENT_POLICY_CAPTURE)
            .setExcludeLayers(arrayOf(excludedSurface))
            .build()
        val wmService = WindowManagerGlobal.getWindowManagerService() ?: return null
        val listener = ScreenCaptureInternal.createSyncCaptureListener()
        // WindowManager captures the display's layer tree in logical screen coordinates.
        wmService.captureDisplay(displayId, args, listener)
        val capture = listener.buffer ?: return null
        val buffer = capture.hardwareBuffer ?: return null
        try {
            val hardwareBitmap = capture.asBitmap() ?: return null
            val bitmap = try {
                hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
            } finally {
                hardwareBitmap.recycle()
            } ?: return null
            try {
                val width = bitmap.width
                val height = bitmap.height
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                val scaleX = width.toFloat() / band.width()
                val scaleY = height.toFloat() / band.height()
                val shares = HashMap<Danmaku, Float>()
                for (target in targets) {
                    val left = floor((target.bounds.left - band.left) * scaleX).toInt()
                        .coerceIn(0, width)
                    val right = ceil((target.bounds.right - band.left) * scaleX).toInt()
                        .coerceIn(0, width)
                    val top = floor((target.bounds.top - band.top) * scaleY).toInt()
                        .coerceIn(0, height)
                    val bottom = ceil((target.bounds.bottom - band.top) * scaleY).toInt()
                        .coerceIn(0, height)
                    if (right <= left || bottom <= top) continue
                    var bright = 0
                    for (y in top until bottom) {
                        for (x in left until right) {
                            // Color.luminance linearizes sRGB before applying the weights.
                            if (Color.luminance(pixels[y * width + x]) > BRIGHT_LUMINANCE) bright++
                        }
                    }
                    shares[target.danmaku] = bright.toFloat() / ((right - left) * (bottom - top))
                }
                return shares
            } finally {
                bitmap.recycle()
            }
        } finally {
            buffer.close()
        }
    }

    private fun updateBacking(danmaku: Danmaku, brightShare: Float?) {
        val showBacking = when {
            brightShare == null || brightShare >= BRIGHT_SHARE_SHOW -> {
                danmaku.darkSamples = 0
                true
            }
            brightShare < BRIGHT_SHARE_HIDE -> {
                danmaku.darkSamples++
                danmaku.backingVisible && danmaku.darkSamples < DARK_SAMPLES_TO_HIDE
            }
            else -> {
                danmaku.darkSamples = 0
                danmaku.backingVisible
            }
        }
        if (showBacking == danmaku.backingVisible) return
        danmaku.backingVisible = showBacking
        danmaku.backingAnimator?.cancel()
        danmaku.backingAnimator = ValueAnimator.ofInt(danmaku.backing.alpha,
                if (showBacking) BACKING_ALPHA else 0).apply {
            duration = BACKING_FADE_DURATION
            addUpdateListener { danmaku.backing.alpha = it.animatedValue as Int }
            start()
        }
    }

    companion object {

        private const val TAG = "DanmakuService"

        private const val NOTIFICATION_SIZE = 60

        private const val LINE_HEIGHT_PROBE = "Ag"
        private val WHITESPACE = Regex("\\s+")

        // Time a short line needs to cross the screen width, which sets the base speed.
        private const val CROSSING_TIME_MS = 8000f
        private const val MAX_SPEED_BOOST = 1.5f

        private const val MAX_LANES = 8
        private const val MAX_LANE_STEPS = 16
        private const val LANE_SPACING_DP = 2
        private const val LANE_GAP_DP = 32f
        private const val FRAME_SKEW_MS = 32L

        private const val MAX_PENDING = 64
        private const val MIN_RETRY_DELAY = 16L
        private const val MAX_RETRY_DELAY = 500L
        private const val END_BACKSTOP_MS = 1000L

        private const val BRIGHTNESS_SAMPLE_INTERVAL = 200L
        private const val SAMPLE_MAX_WIDTH = 512
        private const val BRIGHT_LUMINANCE = 0.7f
        private const val BRIGHT_SHARE_SHOW = 0.08f
        private const val BRIGHT_SHARE_HIDE = 0.04f
        private const val DARK_SAMPLES_TO_HIDE = 2
        private const val BACKING_ALPHA = 102
        private const val BACKING_FADE_DURATION = 150L
    }
}
