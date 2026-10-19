// SPDX-License-Identifier: Apache-2.0
package io.github.miner7222.gamespace

import android.app.ActivityManager
import android.app.ActivityTaskManager
import android.app.KeyguardManager
import android.app.TaskStackListener
import android.content.Context
import android.hardware.display.IDisplayManager
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.ServiceManager
import android.telephony.TelephonyManager
import android.util.Log
import android.view.Display
import android.widget.Toast
import io.chaldeaprjkt.gamespace.R

class ScreenOffSessionController(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val displayManager = IDisplayManager.Stub.asInterface(
        ServiceManager.getService(Context.DISPLAY_SERVICE)
    )
    private val taskManager = ActivityTaskManager.getService()
    private val powerManager = context.getSystemService(PowerManager::class.java)!!
    private val keyguardManager = context.getSystemService(KeyguardManager::class.java)!!
    private val telephonyManager = context.getSystemService(TelephonyManager::class.java)!!
    @Suppress("DEPRECATION")
    private val wakeLock = powerManager.newWakeLock(
        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
        "GameSpace:ScreenOff"
    ).apply { setReferenceCounted(false) }
    private val token = Binder()
    private var gamePackage: String? = null
    private var gameTaskId = -1
    private var gameUserId = -1
    private var pending = false
    private var blanked = false
    private var listening = false

    private val taskListener = object : TaskStackListener() {
        override fun onTaskMovedToFront(taskInfo: ActivityManager.RunningTaskInfo) {
            handler.post {
                if ((pending || blanked) && taskInfo.displayId == Display.DEFAULT_DISPLAY
                    && taskInfo.topActivity?.packageName != gamePackage) end()
            }
        }

        override fun onTaskStackChanged() {
            handler.post {
                if ((pending || blanked) && !isGameForeground()) end()
            }
        }
    }

    private val enter = Runnable {
        handler.removeCallbacks(checkSession)
        pending = false
        if (!canEnter() || !isGameForeground()) {
            end()
        } else {
            try {
                wakeLock.acquire()
                displayManager.setBuiltInDisplayBlanked(token, true)
                blanked = displayManager.isBuiltInDisplayBlanked(token)
                if (blanked) handler.postDelayed(checkSession, 1000) else end()
            } catch (e: Exception) {
                Log.w(TAG, "Unable to blank the built-in display", e)
                end()
            }
        }
    }

    private val checkSession = object : Runnable {
        override fun run() {
            if (!pending && !blanked) return
            try {
                if (!isGameForeground() || !powerManager.isInteractive
                    || keyguardManager.isKeyguardLocked
                    || (blanked && !displayManager.isBuiltInDisplayBlanked(token))) {
                    end()
                    return
                }
                handler.postDelayed(this, if (blanked) 1000 else 250)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to query the screen off session", e)
                end()
            }
        }
    }

    fun onGameStart(packageName: String) {
        onGameStop()
        gamePackage = packageName
        try {
            taskManager.registerTaskStackListener(taskListener)
            listening = true
        } catch (e: Exception) {
            Log.w(TAG, "Unable to observe the game task", e)
        }
    }

    fun request() {
        if (pending || blanked || gamePackage == null || !canEnter()) return
        val task = runCatching { taskManager.focusedRootTaskInfo }.getOrNull() ?: return
        if (task.topActivity?.packageName != gamePackage
            || task.displayId != Display.DEFAULT_DISPLAY) return
        gameTaskId = task.taskId
        gameUserId = task.userId
        pending = true
        Toast.makeText(context, R.string.screen_off_notice, Toast.LENGTH_SHORT).show()
        handler.postDelayed(enter, 1000)
        handler.postDelayed(checkSession, 250)
    }

    @Suppress("DEPRECATION")
    private fun canEnter(): Boolean = powerManager.isInteractive &&
        !keyguardManager.isKeyguardLocked &&
        telephonyManager.callState != TelephonyManager.CALL_STATE_RINGING

    private fun isGameForeground(): Boolean = runCatching {
        val task = taskManager.focusedRootTaskInfo
        task != null && task.taskId == gameTaskId && task.userId == gameUserId &&
            task.topActivity?.packageName == gamePackage &&
            task.displayId == Display.DEFAULT_DISPLAY
    }.getOrDefault(false)

    fun end() {
        handler.removeCallbacks(enter)
        handler.removeCallbacks(checkSession)
        pending = false
        try {
            // Also clean up if the entry transaction succeeded but its query failed.
            displayManager.setBuiltInDisplayBlanked(token, false)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to end the screen off session", e)
        } finally {
            blanked = false
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    fun onGameStop() {
        end()
        gamePackage = null
        gameTaskId = -1
        gameUserId = -1
        if (listening) {
            runCatching { taskManager.unregisterTaskStackListener(taskListener) }
            listening = false
        }
    }

    companion object {
        private const val TAG = "ScreenOffSession"
    }
}
