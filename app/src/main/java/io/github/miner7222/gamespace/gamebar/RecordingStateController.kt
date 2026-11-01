// SPDX-FileCopyrightText: 2026 miner7222
// SPDX-License-Identifier: Apache-2.0

package io.github.miner7222.gamespace.gamebar

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RecordingPhase { IDLE, STARTING, ACTIVE }

data class RecordingState(
    val phase: RecordingPhase = RecordingPhase.IDLE,
    /** Null if recording was already active at the first valid platform observation. */
    val startedAtElapsedRealtime: Long? = null,
    val elapsedMillis: Long? = null,
)

/** The platform supplies no timestamp; known times start at the observed active transition. */
class RecordingStateController {
    private val handler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(RecordingState())
    val state: StateFlow<RecordingState> = mutableState.asStateFlow()
    private var listening = false
    private var observed = false
    private val tick = object : Runnable {
        override fun run() {
            val current = state.value
            val start = current.startedAtElapsedRealtime ?: return
            if (!listening || current.phase != RecordingPhase.ACTIVE) return
            mutableState.value = current.copy(elapsedMillis = (SystemClock.elapsedRealtime() - start).coerceAtLeast(0L))
            handler.postDelayed(this, 1000)
        }
    }

    fun start() {
        dispose()
        listening = true
    }

    fun update(platformState: Bundle) {
        // An empty cache entry is not an observation of an idle recorder.
        if (!listening || !platformState.containsKey("active")) return
        val phase = when {
            platformState.getBoolean("active", false) -> RecordingPhase.ACTIVE
            platformState.getBoolean("starting", false) -> RecordingPhase.STARTING
            else -> RecordingPhase.IDLE
        }
        val previous = state.value
        val start = if (phase != RecordingPhase.ACTIVE) null
            else if (previous.phase == RecordingPhase.ACTIVE) previous.startedAtElapsedRealtime
            else if (observed) SystemClock.elapsedRealtime() else null
        observed = true
        handler.removeCallbacks(tick)
        mutableState.value = RecordingState(phase, start,
            start?.let { (SystemClock.elapsedRealtime() - it).coerceAtLeast(0L) })
        if (start != null) handler.postDelayed(tick, 1000)
    }

    fun dispose() {
        listening = false
        observed = false
        handler.removeCallbacks(tick)
        mutableState.value = RecordingState()
    }
}
