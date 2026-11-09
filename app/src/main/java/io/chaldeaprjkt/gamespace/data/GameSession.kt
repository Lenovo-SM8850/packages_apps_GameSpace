/*
 * Copyright (C) 2021 Chaldeaprjkt
 *               2022-2026 crDroid Android Project
 * Copyright (C) 2023 risingOS Android Project
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
package io.chaldeaprjkt.gamespace.data

import android.app.NotificationManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.media.AudioManager
import com.google.gson.Gson
import io.github.miner7222.gamespace.library.SessionOptions
import io.github.miner7222.gamespace.library.GameOption
import io.github.miner7222.gamespace.TouchSensitivitySettings
import io.chaldeaprjkt.gamespace.utils.GameModeUtils
import javax.inject.Inject

class GameSession @Inject constructor(
    private val context: Context,
    private val appSettings: AppSettings,
    private val systemSettings: SystemSettings,
    private val gson: Gson,
) {

    private val db by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private var liveState: SessionState? = null
    private val displayManager by lazy { context.getSystemService(DisplayManager::class.java) }

    private fun captureBrightness(orig: SessionState) {
        if (orig.ownsBrightness) return
        orig.autoBrightness = systemSettings.autoBrightness
        orig.statusbarBrightness = systemSettings.statusbarBrightness
        orig.brightness = displayManager.getBrightness(Display.DEFAULT_DISPLAY)
        orig.ownsBrightness = true
        persistRecovery(orig)
    }

    fun capturePanelBrightness() {
        val orig = liveState?.takeUnless { it.suspended } ?: return
        captureBrightness(orig)
    }

    private var state
        get() = db.getString(KEY_SAVED_SESSION, "")
            .takeIf { !it.isNullOrEmpty() }
            ?.let {
                try {
                    gson.fromJson(it, SessionState::class.java)?.apply {
                        // Saved sessions from before ownership tracking still need heads-up restoration.
                        if (!org.json.JSONObject(it).has("ownsHeadsup")) ownsHeadsup = appSettings.danmakuNotification
                    }
                } catch (e: RuntimeException) {
                    null
                }
            }
        set(value) {
            check(db.edit().putString(KEY_SAVED_SESSION, value?.let { gson.toJson(it) } ?: "").commit()) {
                "Cannot save session recovery state"
            }
        }

    private fun persistRecovery(snapshot: SessionState) {
        snapshot.preferredMode = SessionOptions.savedMode(context, snapshot.packageName) ?: snapshot.preferredMode
        state = snapshot.copy(appliedRingerMode = 3)
    }

    fun register(sessionName: String, active: Boolean = true) {
        if (liveState?.packageName == sessionName) return
        unregister()

        SessionOptions.start(context, sessionName)
        val saved = SessionState(
            packageName = sessionName,
            userId = context.userId,
            preferredMode = SessionOptions.savedMode(context, sessionName),
            suspended = !active,
        )
        // Commit original values and ownership before each system setting is changed.
        state = saved
        liveState = saved
        if (active) applyOptions() else {
            SessionOptions.setSuspended(context, true)
            GameModeUtils(context).applyRuntimeModeForUser(sessionName, 0, context.userId)
        }
    }

    /** Recovery metadata is written before taking ownership and after releasing it. */
    fun applyOptions() {
        val orig = liveState?.takeUnless { it.suspended } ?: return
        val disableWindowBlurs = systemSettings.disableWindowBlurs
        if (disableWindowBlurs != 1) {
            if (!orig.ownsWindowBlurs) {
                orig.disableWindowBlurs = disableWindowBlurs
                orig.ownsWindowBlurs = true
                persistRecovery(orig)
            }
            systemSettings.disableWindowBlurs = 1
        }
        val options = appSettings.inGameOptions
        val brightness = options.current(GameOption.BRIGHTNESS) == 1
        val panelAuto = SessionOptions.autoBrightness(context)
        if (panelAuto != null || brightness || SessionOptions.brightness(context) != null) {
            captureBrightness(orig)
            val auto = panelAuto ?: if (brightness) false
                else orig.autoBrightness ?: systemSettings.autoBrightness
            if (systemSettings.autoBrightness != auto) systemSettings.autoBrightness = auto
            val statusbar = if (auto) orig.statusbarBrightness ?: false else false
            if (systemSettings.statusbarBrightness != statusbar) systemSettings.statusbarBrightness = statusbar
        } else if (orig.ownsBrightness) {
            restoreBrightness(orig)
            orig.ownsBrightness = false
            persistRecovery(orig)
        }
        val headsup = options.current(GameOption.DANMAKU) == 1
        if (headsup != orig.ownsHeadsup) {
            if (headsup) {
                orig.headsup = systemSettings.headsup
                orig.ownsHeadsup = true
                persistRecovery(orig)
                systemSettings.headsup = false
            } else {
                orig.headsup?.let { systemSettings.headsup = it }
                orig.ownsHeadsup = false
                persistRecovery(orig)
            }
        }
        val ringer = options.current(GameOption.RINGER)
        if (ringer != orig.appliedRingerMode) {
            if (ringer != 3) {
                if (!orig.ownsRingerMode) {
                    orig.ringerMode = audioManager.ringerModeInternal
                    orig.ownsRingerMode = true
                    persistRecovery(orig)
                }
                audioManager.ringerModeInternal = ringer
            } else if (orig.ownsRingerMode) {
                audioManager.ringerModeInternal = orig.ringerMode
                orig.ownsRingerMode = false
                persistRecovery(orig)
            }
            orig.appliedRingerMode = ringer
        }
        val haptics = options.current(GameOption.HAPTICS) == 1
        if (haptics != orig.ownsPulseBassHaptics) {
            if (haptics) {
                orig.pulseBassHaptics = systemSettings.pulseBassHaptics
                orig.ownsPulseBassHaptics = true
                persistRecovery(orig)
                systemSettings.pulseBassHaptics = 0
            } else {
                systemSettings.pulseBassHaptics = orig.pulseBassHaptics
                orig.ownsPulseBassHaptics = false
                persistRecovery(orig)
            }
        }
        val awake = options.current(GameOption.AWAKE) == 1
        if (systemSettings.stayAwake != awake) systemSettings.stayAwake = awake
        val bypass = options.current(GameOption.BYPASS) == 1
        if (systemSettings.bypassChargeEnabled != bypass) systemSettings.bypassChargeEnabled = bypass
        // Capture the live touch values before this game's overrides replace them.
        TouchSensitivitySettings.beginSession(context, orig.packageName)
    }

    fun captureDndOwnership() {
        val orig = liveState?.takeUnless { it.suspended } ?: return
        if (orig.ownsDnd) return
        orig.dndFilter = context.getSystemService(NotificationManager::class.java).currentInterruptionFilter
        orig.ownsDnd = true
        persistRecovery(orig)
    }

    fun releaseDndOwnership() {
        val orig = liveState ?: return
        if (!orig.ownsDnd) return
        orig.ownsDnd = false
        persistRecovery(orig)
    }

    fun restoreDnd() {
        val orig = liveState ?: return
        restoreDnd(orig)
        releaseDndOwnership()
    }

    private fun restoreDnd(orig: SessionState) {
        if (!orig.ownsDnd) return
        val filter = orig.dndFilter ?: return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.currentInterruptionFilter != filter) manager.setInterruptionFilter(filter)
    }

    fun rememberBypassChargeEnabled() {
        if (liveState?.suspended != false) return
        val enabled = systemSettings.bypassChargeEnabled
        if (enabled != (appSettings.inGameOptions.current(GameOption.BYPASS) == 1)) {
            setOption(GameOption.BYPASS, if (enabled) 1 else 0)
        }
    }

    fun setAutoBrightness(enabled: Boolean) {
        if (liveState != null && SessionOptions.chooseAutoBrightness(context, enabled)) applyOptions()
    }

    fun setBypassChargeEnabled(enabled: Boolean) = setOption(GameOption.BYPASS, if (enabled) 1 else 0)

    fun setOption(option: GameOption, value: Int) {
        if (liveState != null && SessionOptions.choose(context, option, value)) applyOptions()
    }

    /** Applies a global-only choice now and keeps it for later games. */
    fun saveGlobalOption(option: GameOption, value: Int) {
        if (liveState == null) return
        // Store first so the apply below reads the new global value.
        appSettings.inGameOptions.setGlobal(option, value)
        setOption(option, value)
    }

    /** Applies a choice now and keeps it as this game's setting for its next sessions. */
    fun saveOption(option: GameOption, value: Int) {
        if (liveState == null) return
        setOption(option, value)
        SessionOptions.packageName(context)?.let { appSettings.inGameOptions.set(it, option, value) }
    }

    private fun restoreBrightness(orig: SessionState) {
        orig.brightness?.takeIf { it.isFinite() && it >= 0f }?.let {
            displayManager.setBrightness(Display.DEFAULT_DISPLAY, it)
        }
        orig.autoBrightness?.let { systemSettings.autoBrightness = it }
        orig.statusbarBrightness?.let { systemSettings.statusbarBrightness = it }
    }

    /** Keep choices and identity, but journal only ownership that still needs recovery. */
    fun suspend() {
        val orig = liveState?.takeUnless { it.suspended } ?: return
        orig.suspended = true
        persistRecovery(orig)
        SessionOptions.setSuspended(context, true)
        releaseEffects(orig)
        // Remove GameSpace's runtime interventions while leaving the selected mode in memory.
        GameModeUtils(context).applyRuntimeModeForUser(orig.packageName, 0,
            orig.userId ?: context.userId)
    }

    fun resume() {
        val orig = liveState?.takeIf { it.suspended } ?: return
        orig.suspended = false
        persistRecovery(orig)
        SessionOptions.setSuspended(context, false)
        applyOptions()
    }

    private fun releaseEffects(orig: SessionState) {
        systemSettings.gestureLock = false
        systemSettings.bypassChargeEnabled = false
        systemSettings.stayAwake = false
        // Touch overrides are a session effect; the saved per-game values stay for later games.
        TouchSensitivitySettings.restoreDefaults(context)
        if (orig.ownsBrightness) {
            restoreBrightness(orig)
            orig.ownsBrightness = false
            persistRecovery(orig)
        }
        if (orig.ownsHeadsup) {
            orig.headsup?.let { systemSettings.headsup = it }
            orig.ownsHeadsup = false
            persistRecovery(orig)
        }
        if (orig.ownsThreeScreenshot) {
            systemSettings.threeScreenshot = orig.threeScreenshot
            orig.ownsThreeScreenshot = false
            persistRecovery(orig)
        }
        if (orig.ownsRingerMode) {
            audioManager.ringerModeInternal = orig.ringerMode
            orig.ownsRingerMode = false
            persistRecovery(orig)
        }
        orig.appliedRingerMode = 3
        if (orig.ownsPulseBassHaptics) {
            systemSettings.pulseBassHaptics = orig.pulseBassHaptics
            orig.ownsPulseBassHaptics = false
            persistRecovery(orig)
        }
        restoreDnd(orig)
        orig.ownsDnd = false
        persistRecovery(orig)
    }

    fun unregister() {
        val orig = liveState?.copy() ?: state?.copy()
        val savedMode = orig?.let { SessionOptions.savedMode(context, it.packageName) ?: it.preferredMode }
        liveState = null
        SessionOptions.end(context)
        // These are session requests, never saved user defaults. Clear even without a journal.
        systemSettings.gestureLock = false
        systemSettings.bypassChargeEnabled = false
        systemSettings.stayAwake = false
        TouchSensitivitySettings.restoreDefaults(context)
        if (orig == null) return
        check(orig.userId == null || orig.userId == context.userId) { "Session recovery user mismatch" }
        if (orig.ownsWindowBlurs) {
            systemSettings.disableWindowBlurs = orig.disableWindowBlurs
            orig.ownsWindowBlurs = false
            persistRecovery(orig)
        }
        releaseEffects(orig)
        savedMode?.let {
            io.github.miner7222.gamespace.library.ModePreferences.publish(context, orig.packageName, it)
        }
        // Recovery and full end only release runtime interventions. A live start applies its mode.
        if (!GameModeUtils(context).applyRuntimeModeForUser(orig.packageName, 0,
                orig.userId ?: context.userId)) return
        state = null
    }


    companion object {
        const val PREFS_NAME = "persisted_session"
        const val KEY_SAVED_SESSION = "session"
    }
}
