/*
 * Copyright (C) 2021 Chaldeaprjkt
 *               2022-2026 crDroid Android Project
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

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.mutableStateOf
import io.chaldeaprjkt.gamespace.utils.GameModeUtils
import javax.inject.Inject

import lineageos.providers.LineageSettings

class SystemSettings @Inject constructor(
    context: Context,
    private val gameModeUtils: GameModeUtils
) {

    private val libraryContext = context
    private val resolver = context.contentResolver
    private val userId = context.userId

    private val wakelock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.FULL_WAKE_LOCK, "GameSpace")

    var headsup
        get() = Settings.Global.getInt(
            resolver, Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED, 1) == 1
        set(it) {
            Settings.Global.putInt(
                resolver, Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED,
                it.toInt()
            )
        }

    var autoBrightness
        get() =
            Settings.System.getIntForUser(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
                userId
            ) ==
                    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        set(auto) {
            Settings.System.putIntForUser(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                if (auto) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                userId
            )
        }

    var statusbarBrightness
        get() =
            Settings.System.getIntForUser(
                resolver, Settings.System.STATUS_BAR_BRIGHTNESS_CONTROL, 0,
                userId
            ) == 1
        set(value) {
            Settings.System.putIntForUser(
                resolver, Settings.System.STATUS_BAR_BRIGHTNESS_CONTROL,
                if (value) 1 else 0, userId
            )
        }

    var bypassChargeEnabled
        get() =
            Settings.System.getIntForUser(
                resolver, "bypass_charge_enabled", 0,
                userId
            ) == 1
        set(value) {
            Settings.System.putIntForUser(
                resolver, "bypass_charge_enabled",
                if (value) 1 else 0, userId
            )
        }

    internal val stayAwakeState = mutableStateOf(false)

    var stayAwake: Boolean
        get() = wakelock.isHeld
        @SuppressLint("WakelockTimeout")
        set(enable) {
            if (enable) {
                if (!wakelock.isHeld) wakelock.acquire()
            } else {
                if (wakelock.isHeld) wakelock.release()
            }
            stayAwakeState.value = wakelock.isHeld
        }

    var threeScreenshot
        get() = LineageSettings.System.getIntForUser(
            resolver, LineageSettings.System.KEY_THREE_FINGERS_SWIPE_ACTION, 0,
            userId
        )
        set(value) {
            LineageSettings.System.putIntForUser(
                resolver, LineageSettings.System.KEY_THREE_FINGERS_SWIPE_ACTION,
                value, userId
            )
        }

    var userGames
        get() =
            Settings.System.getStringForUser(
                resolver, "gamespace_game_list",
                userId
            )
                ?.split(";")
                ?.toList()?.filter { it.isNotEmpty() }
                ?.map { UserGame.fromSettings(it) }
                ?.map { game -> game.copy(mode = io.github.miner7222.gamespace.library.SessionOptions
                    .savedMode(libraryContext, game.packageName) ?: game.mode) } ?: emptyList()
        set(games) {
            Settings.System.putStringForUser(
                resolver,
                "gamespace_game_list",
                if (games.isEmpty()) "" else
                    games.joinToString(";") { game ->
                        val runtime = if (io.github.miner7222.gamespace.library.SessionOptions.packageName(libraryContext) == game.packageName)
                            io.github.miner7222.gamespace.library.SessionOptions.mode(libraryContext) else null
                        game.copy(mode = runtime ?: game.mode).toString()
                    },
                userId
            )
            gameModeUtils.setupBatteryMode(games.isNotEmpty())
        }

    var autoGameDetect
        get() =
            Settings.System.getIntForUser(
                resolver, "gamespace_auto_game_detect", 1,
                userId
            ) == 1
        set(value) {
            Settings.System.putIntForUser(
                resolver, "gamespace_auto_game_detect",
                if (value) 1 else 0, userId
            )
        }

    var gestureLock: Boolean
        get() = Settings.Secure.getIntForUser(resolver, "ax_gaming_gesture_lock", 0, userId) == 1
        set(value) {
            Settings.Secure.putIntForUser(resolver, "ax_gaming_gesture_lock", if (value) 1 else 0, userId)
        }

    var pulseBassHaptics
        get() =
            Settings.Secure.getIntForUser(
                resolver, Settings.Secure.PULSE_BASS_HAPTICS, 0,
                userId
            )
        set(value) {
            Settings.Secure.putIntForUser(
                resolver, Settings.Secure.PULSE_BASS_HAPTICS,
                value, userId
            )
        }

    var libraryDefaultMode: Int
        get() = io.github.miner7222.gamespace.library.ModePreferences.defaultMode(libraryContext)
        set(value) {
            require(value in 0..3)
            Settings.Secure.putIntForUser(resolver,
                io.github.miner7222.gamespace.library.ModePreferences.DEFAULT, value, userId)
        }

    var libraryHideGames: Boolean
        get() = Settings.Secure.getIntForUser(resolver, "gamespace_hide_games", 0, userId) == 1
        set(value) { Settings.Secure.putIntForUser(resolver, "gamespace_hide_games", if (value) 1 else 0, userId) }

    var libraryHomeExceptions: Set<String>
        get() = Settings.Secure.getStringForUser(resolver, "gamespace_home_exceptions", userId)
            .orEmpty().split(',').filter { it.isNotEmpty() }.toSet()
        set(value) { Settings.Secure.putStringForUser(resolver, "gamespace_home_exceptions", value.sorted().joinToString(","), userId) }

    var libraryHintDismissed: Boolean
        get() = Settings.Secure.getIntForUser(resolver, "gamespace_library_hint_dismissed", 0, userId) == 1
        set(value) { Settings.Secure.putIntForUser(resolver, "gamespace_library_hint_dismissed", if (value) 1 else 0, userId) }

    var librarySort: Int
        get() = Settings.Secure.getIntForUser(resolver, "gamespace_library_sort", 0, userId)
        set(value) { Settings.Secure.putIntForUser(resolver, "gamespace_library_sort", value, userId) }

    private fun Boolean.toInt() = if (this) 1 else 0
}
