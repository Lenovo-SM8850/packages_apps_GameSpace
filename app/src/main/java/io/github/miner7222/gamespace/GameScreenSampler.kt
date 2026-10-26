// SPDX-License-Identifier: Apache-2.0
package io.github.miner7222.gamespace

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.SurfaceControl
import android.view.WindowManager
import android.view.WindowManagerGlobal
import android.window.ScreenCapture
import android.window.ScreenCaptureInternal
import androidx.palette.graphics.Palette
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ServiceScoped
import java.io.PrintWriter
import java.util.concurrent.Executors
import javax.inject.Inject
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** One capture owner for game colour and notification readability, confined to the main looper. */
@ServiceScoped
class GameScreenSampler @Inject constructor(@ApplicationContext private val context: Context) {
    class Frame(val bounds: Rect, val width: Int, val height: Int,
            private val pixels: IntArray, val capturedAt: Long) {
        fun brightShare(region: Rect, threshold: Float): Float? {
            val sx = width.toFloat() / bounds.width()
            val sy = height.toFloat() / bounds.height()
            val left = floor((region.left - bounds.left) * sx).toInt().coerceIn(0, width)
            val right = ceil((region.right - bounds.left) * sx).toInt().coerceIn(0, width)
            val top = floor((region.top - bounds.top) * sy).toInt().coerceIn(0, height)
            val bottom = ceil((region.bottom - bounds.top) * sy).toInt().coerceIn(0, height)
            if (right <= left || bottom <= top) return null
            var bright = 0
            for (y in top until bottom) for (x in left until right) {
                if (Color.luminance(pixels[y * width + x]) > threshold) bright++
            }
            return bright.toFloat() / ((right - left) * (bottom - top))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { Thread(it, TAG) }
    private val power = context.getSystemService(PowerManager::class.java)
    private val windows = context.getSystemService(WindowManager::class.java)
    private var game: String? = null
    private var haloWanted = false
    private var danmakuRegion: Rect? = null
    private var registered = false
    private var inFlight = false
    private var generation = 0
    private var lastFrame: Frame? = null
    private var lastColor = Int.MIN_VALUE
    private var captures = 0
    private var lastExcluded = 0
    private var captureCpuNanos = 0L
    private var captureWallMs = 0L
    private var nextErrorLogAt = 0L
    private val sample = Runnable { capture() }
    private val demand = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val wanted = intent.getBooleanExtra("enabled", false)
            if (wanted && intent.getStringExtra("package") != game) return
            if (haloWanted != wanted) {
                haloWanted = wanted
                restart()
            }
        }
    }
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = restart()
    }
    private val settings = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            queryHalo()
            restart()
        }
    }

    fun start(packageName: String) {
        stop()
        if (UserHandle.myUserId() != UserHandle.USER_SYSTEM) return
        game = packageName
        captures = 0
        captureCpuNanos = 0
        captureWallMs = 0
        context.registerReceiver(demand, IntentFilter(ACTION_DEMAND),
                Manifest.permission.WRITE_SECURE_SETTINGS, handler, Context.RECEIVER_EXPORTED)
        context.registerReceiver(screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }, Context.RECEIVER_NOT_EXPORTED)
        for (key in listOf("halo_reactive_enabled", "halo_ring_available")) {
            context.contentResolver.registerContentObserver(Settings.Secure.getUriFor(key),
                    false, settings, UserHandle.USER_SYSTEM)
        }
        registered = true
        queryHalo()
    }

    fun stop() {
        if (game != null) {
            Log.i(TAG, "Stopped game=$game captures=$captures cpuMs=${captureCpuNanos / 1_000_000} wallMs=$captureWallMs")
            sendColor(null)
        }
        if (registered) {
            context.unregisterReceiver(demand)
            context.unregisterReceiver(screen)
            context.contentResolver.unregisterContentObserver(settings)
        }
        registered = false
        game = null
        haloWanted = false
        danmakuRegion = null
        restart()
    }

    fun destroy() {
        stop()
        executor.shutdownNow()
    }

    fun setDanmakuRegion(region: Rect?) {
        if (danmakuRegion == region) return
        danmakuRegion = region?.let { Rect(it) }
        restart()
    }

    fun freshFrame(): Frame? = lastFrame?.takeIf {
        active() && SystemClock.uptimeMillis() - it.capturedAt <= 1000
    }

    fun dump(writer: PrintWriter) {
        writer.println("GameScreenSampler game=$game active=${active()} halo=${haloEnabled()} danmaku=${danmakuRegion != null} inFlight=$inFlight")
        writer.println("captures=$captures cpuMs=${captureCpuNanos / 1_000_000} wallMs=$captureWallMs frame=${lastFrame?.width}x${lastFrame?.height} lastAt=${lastFrame?.capturedAt} excluded=$lastExcluded")
    }

    private fun haloEnabled() = haloWanted && Settings.Secure.getIntForUser(
            context.contentResolver, "halo_reactive_enabled", 0, UserHandle.USER_SYSTEM) == 1 &&
            Settings.Secure.getIntForUser(context.contentResolver, "halo_ring_available", 0, UserHandle.USER_SYSTEM) == 1

    private fun active() = game != null && power.isInteractive &&
            (haloEnabled() && !power.isPowerSaveMode || danmakuRegion != null)

    private fun queryHalo() {
        context.sendBroadcast(Intent(ACTION_QUERY).setPackage(HALO_PACKAGE),
                Manifest.permission.WRITE_SECURE_SETTINGS)
    }

    private fun restart() {
        generation++
        lastFrame = null
        lastColor = Int.MIN_VALUE
        handler.removeCallbacks(sample)
        if (active() && !inFlight) handler.post(sample)
        Log.i(TAG, "Sampling active=${active()} halo=${haloEnabled()} danmaku=${danmakuRegion != null}")
    }

    private class ArgsBuilder : ScreenCaptureInternal.CaptureArgs.Builder<ArgsBuilder>()

    private fun capture() {
        if (!active() || inFlight) return
        val halo = haloEnabled() && !power.isPowerSaveMode
        val danmaku = danmakuRegion != null
        val displayBounds = windows.currentWindowMetrics.bounds
        val crop = if (halo) Rect(displayBounds) else danmakuRegion?.let { Rect(it) } ?: return
        if (!crop.intersect(displayBounds) || crop.isEmpty) return
        val scale = if (danmaku) min(1f, 512f / crop.width())
                else min(1f, 64f / max(crop.width(), crop.height()))
        // Every root belongs to this process: exclude the sidebar, panel, mapper and danmaku.
        val global = WindowManagerGlobal.getInstance()
        val excluded = global.viewRootNames.mapNotNull { name ->
            global.getRootView(name)?.viewRootImpl?.surfaceControl?.takeIf { it.isValid }?.let {
                SurfaceControl(it, TAG)
            }
        }
        val args = ArgsBuilder().setSourceCrop(crop).setFrameScale(scale)
                .setPixelFormat(PixelFormat.RGBA_8888)
                .setSecureContentPolicy(ScreenCapture.ScreenCaptureParams.SECURE_CONTENT_POLICY_REDACT)
                .setExcludeLayers(excluded.toTypedArray()).build()
        lastExcluded = excluded.size
        val requestGeneration = generation
        inFlight = true
        executor.execute {
            val startWall = SystemClock.uptimeMillis()
            val startCpu = Debug.threadCpuTimeNanos()
            var frame: Frame? = null
            var color: Int? = null
            try {
                val listener = ScreenCaptureInternal.createSyncCaptureListener()
                WindowManagerGlobal.getWindowManagerService()?.captureDisplay(Display.DEFAULT_DISPLAY, args, listener)
                listener.buffer?.let { capture ->
                    val buffer = capture.hardwareBuffer
                    try {
                        capture.asBitmap()?.let { hardware ->
                            val bitmap = try { hardware.copy(Bitmap.Config.ARGB_8888, false) }
                                    finally { hardware.recycle() }
                            bitmap?.let {
                                try {
                                    if (danmaku) {
                                        val pixels = IntArray(it.width * it.height)
                                        it.getPixels(pixels, 0, it.width, 0, 0, it.width, it.height)
                                        frame = Frame(crop, it.width, it.height, pixels, startWall)
                                    }
                                    if (halo) {
                                        val palette = Palette.from(it).maximumColorCount(16)
                                                .clearFilters().addFilter { _, hsl -> hsl[2] in 0.15f..0.90f }
                                                .generate()
                                        color = (palette.vibrantSwatch ?: palette.dominantSwatch)?.rgb?.and(0xffffff)
                                    }
                                } finally { it.recycle() }
                            }
                        }
                    } finally { buffer?.close() }
                }
            } catch (exception: Exception) {
                val now = SystemClock.uptimeMillis()
                if (now >= nextErrorLogAt) {
                    nextErrorLogAt = now + 5000
                    Log.w(TAG, "Game frame unavailable", exception)
                }
            } finally {
                excluded.forEach { it.release() }
            }
            val cpu = Debug.threadCpuTimeNanos() - startCpu
            val wall = SystemClock.uptimeMillis() - startWall
            handler.post {
                inFlight = false
                if (requestGeneration == generation && active()) {
                    captures++
                    captureCpuNanos += cpu
                    captureWallMs += wall
                    lastFrame = frame
                    if (halo) sendColor(color)
                }
                if (active()) handler.postDelayed(sample, 500)
            }
        }
    }

    private fun sendColor(color: Int?) {
        val value = color ?: -1
        if (lastColor == value) return
        lastColor = value
        context.sendBroadcast(Intent(ACTION_COLOR).setPackage(HALO_PACKAGE)
                .putExtra("package", game).putExtra("color", value), Manifest.permission.WRITE_SECURE_SETTINGS)
    }

    companion object {
        private const val TAG = "GameScreenSampler"
        private const val HALO_PACKAGE = "io.github.miner7222.halo"
        private const val ACTION_DEMAND = "io.github.miner7222.halo.GAME_COLOR_DEMAND"
        private const val ACTION_QUERY = "io.github.miner7222.halo.QUERY_GAME_COLOR_DEMAND"
        private const val ACTION_COLOR = "io.github.miner7222.halo.GAME_COLOR"
    }
}
