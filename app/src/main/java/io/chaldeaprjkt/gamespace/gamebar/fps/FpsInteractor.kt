/*
 * Copyright (C) 2025 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.chaldeaprjkt.gamespace.gamebar.fps

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.SystemProperties
import android.util.Log
import android.view.WindowManager
import android.window.TaskFpsCallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FpsInteractor @Inject constructor(context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val _fpsHistory = MutableStateFlow<List<Float>>(emptyList())
    val fpsHistory: StateFlow<List<Float>> get() = _fpsHistory
    private val _dynamicMaxFps = MutableStateFlow(60f)
    val dynamicMaxFps: StateFlow<Float> get() = _dynamicMaxFps
    val maxRefreshRate: Float get() = wm.defaultDisplay?.mode?.refreshRate ?: 60f

    var onFpsChanged: ((Float) -> Unit)? = null
    private val buffer = ArrayDeque<Float>(100)
    private var callback: TaskFpsCallback? = null
    private var taskId = -1
    private var generation = 0
    private var lastSample = 0L

    // SurfaceFlinger dispatches to one listener per task. Share that listener between
    // the panel and overlay, and keep it registered when the panel opens or closes.
    fun start(gameTaskId: Int) {
        if (taskId == gameTaskId && callback != null) return
        dispose()
        taskId = gameTaskId
        buffer.clear()
        _fpsHistory.value = emptyList()
        _dynamicMaxFps.value = 60f
        lastSample = 0L
        val epoch = generation
        val listener = object : TaskFpsCallback() {
            override fun onFpsReported(fps: Float) {
                handler.post {
                    if (epoch != generation || taskId != gameTaskId || !fps.isFinite()) return@post
                    onFpsChanged?.invoke(fps)
                    val now = SystemClock.uptimeMillis()
                    if (now - lastSample < 1000) return@post
                    lastSample = now
                    val smoothed = (buffer.lastOrNull() ?: fps) * 0.75f + fps * 0.25f
                    if (buffer.size == 100) buffer.removeFirst()
                    buffer.addLast(smoothed)
                    _fpsHistory.value = buffer.toList()
                    _dynamicMaxFps.value = buffer.maxOrNull()?.coerceAtLeast(15f) ?: 60f
                    if (SystemProperties.get("vendor.gpp.log.msg") == "0x21") {
                        Log.d("GameSpaceFps", "task=$gameTaskId output=$fps graph=$smoothed")
                    }
                }
            }
        }
        try {
            wm.registerTaskFpsCallback(gameTaskId, Runnable::run, listener)
            callback = listener
        } catch (e: Exception) {
            taskId = -1
            Log.w("GameSpaceFps", "Cannot track task $gameTaskId", e)
        }
    }

    fun dispose() {
        generation++
        callback?.let { runCatching { wm.unregisterTaskFpsCallback(it) } }
        callback = null
        taskId = -1
    }
}
