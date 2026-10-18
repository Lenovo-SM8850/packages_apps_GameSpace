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

import android.animation.ValueAnimator
import android.app.Notification
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
import android.os.UserHandle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceControl
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.view.WindowManagerGlobal
import android.widget.TextView
import android.window.ScreenCapture
import android.window.ScreenCaptureInternal

import androidx.core.animation.addListener

import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ServiceScoped

import java.util.LinkedList
import java.util.concurrent.Executors

import kotlin.math.ceil
import kotlin.math.floor

import javax.inject.Inject

import io.chaldeaprjkt.gamespace.R
import io.chaldeaprjkt.gamespace.data.AppSettings
import io.chaldeaprjkt.gamespace.gamebar.DanmakuServiceListener

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

    private val notificationBacking = GradientDrawable().apply {
        setColor(Color.BLACK)
        cornerRadius = 8f * context.resources.displayMetrics.density
        alpha = 0
    }

    private val notificationOverlay = TextView(context).apply {
        gravity = Gravity.CENTER
        maxLines = 2
        setTextColor(Color.WHITE)
        background = notificationBacking
        val density = resources.displayMetrics.density
        setPadding((8 * density).toInt(), (4 * density).toInt(),
                (8 * density).toInt(), (4 * density).toInt())
        isFocusable = false
        isClickable = false
    }

    private val windowManager: WindowManager = context.getSystemService(WindowManager::class.java)!!

    private val handler = Handler(Looper.getMainLooper())

    private val notificationStack = LinkedList<String>()

    private var layoutParams: LayoutParams = LayoutParams().apply {
        height = LayoutParams.WRAP_CONTENT
        flags = flags or LayoutParams.FLAG_NOT_FOCUSABLE or
                LayoutParams.FLAG_NOT_TOUCHABLE or
                LayoutParams.FLAG_HARDWARE_ACCELERATED
        type = LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY
        format = PixelFormat.TRANSLUCENT
        gravity = Gravity.TOP
    }

    private var isPortrait: Boolean =
            context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    private var verticalOffsetLandscape = 0
    private var verticalOffsetPortrait = 0

    private var overlayAlphaAnimator: ValueAnimator? = null
    private var overlayPositionAnimator: ValueAnimator? = null
    private var backingAnimator: ValueAnimator? = null
    private val samplingExecutor = Executors.newSingleThreadExecutor()
    private var samplingEnabled = false
    private var samplingInFlight = false
    private var samplingGeneration = 0
    private var backingVisible = false
    private var darkSamples = 0
    private var destroyed = false
    private val sampleBrightness = Runnable { sampleBackgroundBrightness() }

    fun init() {
        notificationListener = DanmakuServiceListener()
        notificationListener.danmakuServiceInterface = this
        updateParams()
        registerListener()
    }

    fun updateConfiguration(newConfig: Configuration) {
        stopBrightnessSampling()
        isPortrait = newConfig.orientation == Configuration.ORIENTATION_PORTRAIT
        overlayAlphaAnimator?.end()
        overlayPositionAnimator?.end()
        updateParams()
        updateViewLayoutSafely(layoutParams)
        if (notificationOverlay.parent != null) startBrightnessSampling()
    }

    fun destroy() {
        destroyed = true
        unregisterListener()
        overlayAlphaAnimator?.cancel()
        overlayPositionAnimator?.cancel()
        removeViewSafely()
        handler.removeCallbacksAndMessages(null)
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

    private fun updateParams() {
        with(context.resources) {
            verticalOffsetLandscape =
                getDimensionPixelSize(R.dimen.notification_vertical_offset_landscape)
            verticalOffsetPortrait =
                getDimensionPixelSize(R.dimen.notification_vertical_offset_portrait)
        }
        // Leave the text's original position and wrapping unchanged by the backing's padding.
        layoutParams.y = getOffsetForPosition() - notificationOverlay.paddingTop
        layoutParams.width =
                (NOTIFICATION_MAX_WIDTH * windowManager.currentWindowMetrics.bounds.width()) / 100 +
                notificationOverlay.paddingLeft + notificationOverlay.paddingRight
        notificationOverlay.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            (if (isPortrait) NOTIFICATION_SIZE_PORTRAIT else NOTIFICATION_SIZE_LANDSCAPE).toFloat()
        )
    }

    private fun getOffsetForPosition(): Int {
        return if (isPortrait) verticalOffsetPortrait else verticalOffsetLandscape
    }

    override fun showNotificationAsOverlay(notification: String) {
        if (destroyed) return
        if (notificationOverlay.parent == null) {
            notificationOverlay.alpha = 0f
            notificationOverlay.text = notification
            windowManager.addView(notificationOverlay, layoutParams)
            pushNotification()
        } else {
            notificationStack.add(notification)
        }
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

    private fun pushNotification() {
        startBrightnessSampling()
        val end = getOffsetForPosition().toFloat()
        val start = end * (1 - SLIDE_ANIMATION_DISTANCE_FACTOR)
        overlayPositionAnimator = getPositionAnimator(APPEAR_ANIMATION_DURATION, start, end).also {
            it.addListener(onEnd = {
                if (destroyed) return@addListener
                handler.postDelayed({
                    popNotification()
                }, DISPLAY_NOTIFICATION_DURATION)
            })
            it.start()
        }
        startAlphaAnimation(APPEAR_ANIMATION_DURATION, 0f, 1f)
    }

    private fun popNotification() {
        val start = getOffsetForPosition().toFloat()
        val end = start * (1 + SLIDE_ANIMATION_DISTANCE_FACTOR)
        overlayPositionAnimator =
            getPositionAnimator(DISAPPEAR_ANIMATION_DURATION, start, end).also {
                it.addListener(onEnd = {
                    if (destroyed) return@addListener
                    if (notificationStack.isEmpty()) {
                        removeViewSafely()
                    } else {
                        notificationOverlay.alpha = 0f
                        notificationOverlay.text = notificationStack.pop()
                        pushNotification()
                    }
                })
                it.start()
            }
        startAlphaAnimation(DISAPPEAR_ANIMATION_DURATION, 1f, 0f)
    }

    private fun startBrightnessSampling() {
        stopBrightnessSampling()
        if (destroyed || !danmakuNotificationMode) return
        samplingEnabled = true
        handler.post(sampleBrightness)
    }

    private fun stopBrightnessSampling() {
        samplingEnabled = false
        samplingGeneration++
        handler.removeCallbacks(sampleBrightness)
        backingAnimator?.cancel()
        backingVisible = false
        darkSamples = 0
        notificationBacking.alpha = 0
    }

    private fun getTextBoundsOnScreen(): Rect? {
        val textLayout = notificationOverlay.layout ?: return null
        val lineCount = minOf(textLayout.lineCount, notificationOverlay.maxLines)
        if (lineCount == 0) return null
        var left = Float.MAX_VALUE
        var right = 0f
        for (line in 0 until lineCount) {
            left = minOf(left, textLayout.getLineLeft(line))
            right = maxOf(right, textLayout.getLineRight(line))
        }
        val location = IntArray(2)
        notificationOverlay.getLocationOnScreen(location)
        val textTop = location[1] + notificationOverlay.baseline - textLayout.getLineBaseline(0)
        return Rect(
            location[0] + notificationOverlay.totalPaddingLeft + floor(left).toInt(),
            textTop,
            location[0] + notificationOverlay.totalPaddingLeft + ceil(right).toInt(),
            textTop + textLayout.getLineBottom(lineCount - 1)
        ).takeIf {
            it.intersect(Rect(location[0], location[1],
                    location[0] + notificationOverlay.width,
                    location[1] + notificationOverlay.height)) &&
                    it.intersect(windowManager.currentWindowMetrics.bounds) && !it.isEmpty
        }
    }

    private fun sampleBackgroundBrightness() {
        if (!samplingEnabled) return
        if (!danmakuNotificationMode || notificationOverlay.parent == null) {
            stopBrightnessSampling()
            return
        }
        if (samplingInFlight) return
        val bounds = getTextBoundsOnScreen()
        val surface = notificationOverlay.viewRootImpl?.surfaceControl
        if (bounds == null || surface == null || !surface.isValid) {
            handler.postDelayed(sampleBrightness, BRIGHTNESS_SAMPLE_INTERVAL)
            return
        }

        // Keep our surface alive until the capture request has been sent on the worker.
        val excludedSurface = SurfaceControl(surface, TAG)
        val displayId = notificationOverlay.display.displayId
        val generation = samplingGeneration
        samplingInFlight = true
        samplingExecutor.execute {
            val brightShare = try {
                captureBrightShare(bounds, displayId, excludedSurface)
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
                        // Keep text readable if a capture is temporarily unavailable.
                        updateBacking(brightShare)
                    }
                    handler.postDelayed(sampleBrightness, BRIGHTNESS_SAMPLE_INTERVAL)
                }
            }
        }
    }

    private class BackgroundCaptureArgsBuilder :
            ScreenCaptureInternal.CaptureArgs.Builder<BackgroundCaptureArgsBuilder>()

    private fun captureBrightShare(bounds: Rect, displayId: Int,
            excludedSurface: SurfaceControl): Float? {
        val scale = minOf(1f, SAMPLE_MAX_WIDTH.toFloat() / bounds.width(),
                SAMPLE_MAX_HEIGHT.toFloat() / bounds.height())
        val args = BackgroundCaptureArgsBuilder()
            .setSourceCrop(bounds)
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
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                // Color.luminance linearizes sRGB before applying the luminance weights.
                val brightPixels = pixels.count { Color.luminance(it) > BRIGHT_LUMINANCE }
                return brightPixels.toFloat() / pixels.size
            } finally {
                bitmap.recycle()
            }
        } finally {
            buffer.close()
        }
    }

    private fun updateBacking(brightShare: Float?) {
        val showBacking = when {
            brightShare == null || brightShare >= BRIGHT_SHARE_SHOW -> {
                darkSamples = 0
                true
            }
            brightShare < BRIGHT_SHARE_HIDE -> {
                darkSamples++
                backingVisible && darkSamples < DARK_SAMPLES_TO_HIDE
            }
            else -> {
                darkSamples = 0
                backingVisible
            }
        }
        if (showBacking == backingVisible) return
        backingVisible = showBacking
        backingAnimator?.cancel()
        backingAnimator = ValueAnimator.ofInt(notificationBacking.alpha,
                if (showBacking) BACKING_ALPHA else 0).apply {
            duration = BACKING_FADE_DURATION
            addUpdateListener { notificationBacking.alpha = it.animatedValue as Int }
            start()
        }
    }

    private fun getPositionAnimator(duration: Long, vararg values: Float): ValueAnimator {
        val lpCopy = LayoutParams().also { it.copyFrom(layoutParams) }
        return ValueAnimator.ofFloat(*values).apply {
            this.duration = duration
            addUpdateListener {
                lpCopy.y = (it.animatedValue as Float).toInt() - notificationOverlay.paddingTop
                updateViewLayoutSafely(lpCopy)
            }
        }
    }

    private fun startAlphaAnimation(duration: Long, vararg values: Float) {
        overlayAlphaAnimator = ValueAnimator.ofFloat(*values).apply {
            this.duration = duration
            addUpdateListener {
                notificationOverlay.alpha = it.animatedValue as Float
            }
        }.also { it.start() }
    }

    private fun updateViewLayoutSafely(layoutParams: LayoutParams) {
        if (notificationOverlay.parent != null)
            windowManager.updateViewLayout(notificationOverlay, layoutParams)
    }

    private fun removeViewSafely() {
        stopBrightnessSampling()
        if (notificationOverlay.parent != null)
            windowManager.removeViewImmediate(notificationOverlay)
    }

    companion object {
    
        private const val TAG = "DanmakuService"

        private const val SLIDE_ANIMATION_DISTANCE_FACTOR = 0.5f

        private const val APPEAR_ANIMATION_DURATION = 500L
        private const val DISPLAY_NOTIFICATION_DURATION = 2000L
        private const val DISAPPEAR_ANIMATION_DURATION = 300L

        private const val NOTIFICATION_SIZE_LANDSCAPE = 60
        private const val NOTIFICATION_SIZE_PORTRAIT = 60

        private const val NOTIFICATION_MAX_WIDTH = 75

        private const val BRIGHTNESS_SAMPLE_INTERVAL = 200L
        private const val SAMPLE_MAX_WIDTH = 128
        private const val SAMPLE_MAX_HEIGHT = 32
        private const val BRIGHT_LUMINANCE = 0.7f
        private const val BRIGHT_SHARE_SHOW = 0.08f
        private const val BRIGHT_SHARE_HIDE = 0.04f
        private const val DARK_SAMPLES_TO_HIDE = 2
        private const val BACKING_ALPHA = 102
        private const val BACKING_FADE_DURATION = 150L

        private const val NOTIFICATIONS_MAX_CACHED = 99
    }
}
