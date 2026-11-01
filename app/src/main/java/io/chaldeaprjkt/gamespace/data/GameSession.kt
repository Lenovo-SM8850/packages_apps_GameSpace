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

import io.github.miner7222.gamespace.library.SessionOptions
import io.github.miner7222.gamespace.library.GameOption
import android.content.Context
import android.media.AudioManager
import com.google.gson.Gson
import javax.inject.Inject

class GameSession @Inject constructor(
    private val context: Context,
    private val appSettings: AppSettings,
    private val systemSettings: SystemSettings,
    private val gson: Gson,
) {

    private val db by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private var state
        get() = db.getString(KEY_SAVED_SESSION, "")
            .takeIf { !it.isNullOrEmpty() }
            ?.let {
                try {
                    gson.fromJson(it, SessionState::class.java)
                } catch (e: RuntimeException) {
                    null
                }
            }
        set(value) = db.edit()
            .putString(KEY_SAVED_SESSION, value?.let {
                try {
                    gson.toJson(value)
                } catch (e: RuntimeException) {
                    ""
                }
            } ?: "")
            .apply()

    fun register(sessionName: String) {
        if (state?.packageName == sessionName) return
        unregister()

        state = SessionState(
            packageName = sessionName,
            autoBrightness = systemSettings.autoBrightness,
            statusbarBrightness = systemSettings.statusbarBrightness,
            headsup = systemSettings.headsup,
            threeScreenshot = systemSettings.threeScreenshot,
            ringerMode = audioManager.ringerModeInternal,
            pulseBassHaptics = systemSettings.pulseBassHaptics,
            ownsBrightness = appSettings.noAutoBrightness,
            ownsThreeScreenshot = appSettings.noThreeScreenshot,
            ownsRingerMode = appSettings.ringerMode != 3,
            ownsPulseBassHaptics = appSettings.noPulseBassHaptics,
        )
        systemSettings.bypassChargeEnabled = appSettings.isBypassChargeEnabled(sessionName)
        if (appSettings.noAutoBrightness) {
            systemSettings.autoBrightness = false
            systemSettings.statusbarBrightness = false
        }
        if (appSettings.danmakuNotification) {
            systemSettings.headsup = false
        }
        if (appSettings.noThreeScreenshot) {
            systemSettings.threeScreenshot = 0
        }
        if (appSettings.ringerMode != 3) {
            audioManager.ringerModeInternal = appSettings.ringerMode
        }
        if (appSettings.stayAwake) {
            systemSettings.stayAwake = true
        }
        if (appSettings.noPulseBassHaptics) {
            systemSettings.pulseBassHaptics = 0
        }
    }

    fun rememberBypassChargeEnabled() {
        val packageName = state?.packageName ?: return
        appSettings.setBypassChargeEnabled(packageName, systemSettings.bypassChargeEnabled)
    }

    fun setBypassChargeEnabled(enabled: Boolean) {
        state?.packageName?.let { appSettings.setBypassChargeEnabled(it, enabled) }
        systemSettings.bypassChargeEnabled = enabled
    }

    fun setDanmakuNotification(enabled: Boolean) {
        appSettings.danmakuNotification = enabled
        val orig = state ?: return
        if (enabled) {
            systemSettings.headsup = false
        } else {
            orig.headsup?.let { systemSettings.headsup = it }
        }
    }

    fun unregister() {
        val orig = state?.copy() ?: return
        if (orig.ownsBrightness) {
            orig.autoBrightness?.let { systemSettings.autoBrightness = it }
            orig.statusbarBrightness?.let { systemSettings.statusbarBrightness = it }
        }
        if (appSettings.danmakuNotification) {
            orig.headsup?.let { systemSettings.headsup = it }
        }
        if (orig.ownsThreeScreenshot) {
            systemSettings.threeScreenshot = orig.threeScreenshot
        }
        if (orig.ownsRingerMode) {
            audioManager.ringerModeInternal = orig.ringerMode
        }
        if (orig.ownsPulseBassHaptics) {
            systemSettings.pulseBassHaptics = orig.pulseBassHaptics
        }
        systemSettings.stayAwake = false
        state = null
        systemSettings.bypassChargeEnabled = false
    }

    fun finalize() {
        unregister()
    }

    companion object {
        const val PREFS_NAME = "persisted_session"
        const val KEY_SAVED_SESSION = "session"
    }

    fun setOption(option: GameOption, value: Int) {
        if (liveState != null && SessionOptions.choose(context, option, value)) applyOptions()
    }

    private var liveState: SessionState? = null

    /** Recovery metadata is written before taking ownership and after releasing it. */
    fun applyOptions() {
        val orig = liveState ?: return
        val options = appSettings.inGameOptions
        val brightness = options.current(GameOption.BRIGHTNESS) == 1
        val panelAuto = SessionOptions.autoBrightness(context)
        if (panelAuto != null || brightness) {
            if (!orig.ownsBrightness) {
                orig.autoBrightness = systemSettings.autoBrightness
                orig.statusbarBrightness = systemSettings.statusbarBrightness
                orig.ownsBrightness = true
                persistRecovery(orig)
            }
            val auto = panelAuto ?: false
            if (systemSettings.autoBrightness != auto) systemSettings.autoBrightness = auto
            val statusbar = if (auto) orig.statusbarBrightness ?: false else false
            if (systemSettings.statusbarBrightness != statusbar) systemSettings.statusbarBrightness = statusbar
        } else if (orig.ownsBrightness) {
            orig.autoBrightness?.let { systemSettings.autoBrightness = it }
            orig.statusbarBrightness?.let { systemSettings.statusbarBrightness = it }
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
        val screenshot = options.current(GameOption.SCREENSHOT) == 1
        if (screenshot != orig.ownsThreeScreenshot) {
            if (screenshot) {
                orig.threeScreenshot = systemSettings.threeScreenshot
                orig.ownsThreeScreenshot = true
                persistRecovery(orig)
                systemSettings.threeScreenshot = 0
            } else {
                systemSettings.threeScreenshot = orig.threeScreenshot
                orig.ownsThreeScreenshot = false
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
    }

    private fun persistRecovery(snapshot: SessionState) {
        snapshot.preferredMode = SessionOptions.savedMode(context, snapshot.packageName) ?: snapshot.preferredMode
        state = snapshot.copy(appliedRingerMode = 3)
    }
}
