// SPDX-FileCopyrightText: 2026 miner7222
// SPDX-License-Identifier: Apache-2.0

package io.github.miner7222.gamespace.gamebar

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.Looper
import android.util.Log
import android.util.SparseArray
import android.view.Display
import android.view.InputEvent
import android.view.InputEventReceiver
import android.view.InputMonitor
import android.view.MotionEvent

/**
 * Reports a swipe that starts at any display edge, without taking part in the gesture.
 *
 * It only watches: events are always finished as unhandled and no pointer is pilfered, so the
 * system gesture proceeds exactly as if this monitor did not exist. It is created only while the
 * handle is hidden.
 */
class EdgeSwipeMonitor(
    private val context: Context,
    private val onEdgeSwipe: () -> Unit,
) {
    private val inputManager = context.getSystemService(InputManager::class.java)
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private var monitor: InputMonitor? = null
    private var receiver: InputEventReceiver? = null
    private var tracker: EdgeSwipeTracker? = null

    /** Returns false when the monitor could not be registered. */
    fun start(): Boolean {
        if (monitor != null) return true
        val density = context.resources.displayMetrics.density
        val swipes = EdgeSwipeTracker(EDGE_ZONE_DP * density, TRAVEL_DP * density)
        val created = try {
            inputManager.monitorGestureInput(MONITOR_NAME, Display.DEFAULT_DISPLAY)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Unable to monitor edge swipes", e)
            return false
        }
        tracker = swipes
        monitor = created
        receiver = object : InputEventReceiver(created.inputChannel, Looper.getMainLooper()) {
            override fun onInputEvent(event: InputEvent) {
                var swipe = false
                try {
                    if (event is MotionEvent) swipe = handle(swipes, event)
                } finally {
                    finishInputEvent(event, false)
                }
                if (swipe) onEdgeSwipe()
            }
        }
        return true
    }

    fun stop() {
        receiver?.dispose()
        receiver = null
        monitor?.dispose()
        monitor = null
        tracker = null
    }

    private fun handle(swipes: EdgeSwipeTracker, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipes.clear()
                track(swipes, event, 0)
            }
            MotionEvent.ACTION_POINTER_DOWN -> track(swipes, event, event.actionIndex)
            MotionEvent.ACTION_MOVE -> for (index in 0 until event.pointerCount) {
                if (swipes.move(event.getPointerId(index), event.getRawX(index), event.getRawY(index))) {
                    return true
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val id = event.getPointerId(event.actionIndex)
                // A recogniser that pilfers a single pointer cancels just that pointer.
                val cancelled = event.flags and MotionEvent.FLAG_CANCELED != 0
                val fired = swipes.release(id, cancelled)
                if (event.actionMasked == MotionEvent.ACTION_UP) swipes.clear()
                return fired
            }
            // Recognisers pilfer pointers, which delivers a cancel to spy windows.
            MotionEvent.ACTION_CANCEL -> return swipes.cancel()
        }
        return false
    }

    private fun track(swipes: EdgeSwipeTracker, event: MotionEvent, index: Int) {
        val size = Point()
        // Monitor coordinates are logical display coordinates, so the bounds follow rotation.
        displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.getRealSize(size)
        if (size.x <= 0 || size.y <= 0) return
        swipes.down(event.getPointerId(index), event.getRawX(index), event.getRawY(index),
            size.x.toFloat(), size.y.toFloat())
    }

    private companion object {
        const val TAG = "EdgeSwipeMonitor"
        const val MONITOR_NAME = "GameSpaceEdgeSwipe"
        const val EDGE_ZONE_DP = 24f
        const val TRAVEL_DP = 16f
    }
}

internal object Edge {
    const val LEFT = 1
    const val TOP = 2
    const val RIGHT = 4
    const val BOTTOM = 8
}

/** Edges whose zone contains the point; a corner belongs to two edges. */
internal fun edgesAt(x: Float, y: Float, width: Float, height: Float, zone: Float): Int {
    var edges = 0
    if (x <= zone) edges = edges or Edge.LEFT
    if (y <= zone) edges = edges or Edge.TOP
    if (x >= width - zone) edges = edges or Edge.RIGHT
    if (y >= height - zone) edges = edges or Edge.BOTTOM
    return edges
}

/** Largest movement of the pointer toward the inside of the display along any of its edges. */
internal fun inwardTravel(edges: Int, startX: Float, startY: Float, x: Float, y: Float): Float {
    var travel = 0f
    if (edges and Edge.LEFT != 0) travel = maxOf(travel, x - startX)
    if (edges and Edge.TOP != 0) travel = maxOf(travel, y - startY)
    if (edges and Edge.RIGHT != 0) travel = maxOf(travel, startX - x)
    if (edges and Edge.BOTTOM != 0) travel = maxOf(travel, startY - y)
    return travel
}

/** Follows every pointer that went down inside an edge zone, so a second thumb counts too. */
internal class EdgeSwipeTracker(private val zone: Float, private val travel: Float) {
    private class Candidate(val edges: Int, val startX: Float, val startY: Float)

    private val candidates = SparseArray<Candidate>()

    fun down(id: Int, x: Float, y: Float, width: Float, height: Float) {
        val edges = edgesAt(x, y, width, height, zone)
        if (edges != 0) candidates.put(id, Candidate(edges, x, y))
    }

    /** True once the pointer has moved far enough inward from its edge. */
    fun move(id: Int, x: Float, y: Float): Boolean {
        val candidate = candidates.get(id) ?: return false
        return inwardTravel(candidate.edges, candidate.startX, candidate.startY, x, y) >= travel
    }

    /** True when a cancelled pointer was still an active candidate. */
    fun release(id: Int, cancelled: Boolean): Boolean {
        val known = candidates.indexOfKey(id) >= 0
        candidates.remove(id)
        return cancelled && known
    }

    /** True when any candidate was still active at the cancel. */
    fun cancel(): Boolean {
        val active = candidates.size() > 0
        candidates.clear()
        return active
    }

    fun clear() = candidates.clear()
}
