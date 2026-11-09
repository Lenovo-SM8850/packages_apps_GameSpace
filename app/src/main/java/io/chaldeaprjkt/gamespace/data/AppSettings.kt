/*
 * Copyright (C) 2021 Chaldeaprjkt
 * Copyright (C) 2023 risingOS Android Project
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

import android.app.Service
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.view.WindowManager
import androidx.preference.PreferenceManager
import io.chaldeaprjkt.gamespace.utils.dp
import io.chaldeaprjkt.gamespace.utils.statusbarHeight
import io.github.miner7222.gamespace.library.InGameOptions
import io.github.miner7222.gamespace.library.GameOption
import javax.inject.Inject

class AppSettings @Inject constructor(private val context: Context) {

    private val db by lazy { PreferenceManager.getDefaultSharedPreferences(context) }
    private val wm by lazy { context.getSystemService(Service.WINDOW_SERVICE) as WindowManager }

    val inGameOptions by lazy { InGameOptions(context) }

    fun isBypassChargeEnabled(packageName: String): Boolean =
        inGameOptions.enabled(packageName, GameOption.BYPASS)

    fun setBypassChargeEnabled(packageName: String, enabled: Boolean) {
        inGameOptions.set(packageName, GameOption.BYPASS, if (enabled) 1 else 0)
    }

    var x
        get() = db.getInt("offset_x", wm.maximumWindowMetrics.bounds.width() / 2)
        set(point) = db.edit().putInt("offset_x", point).apply()

    var y
        get() = db.getInt("offset_y", context.statusbarHeight + 8.dp)
        set(point) = db.edit().putInt("offset_y", point).apply()

    var showFps
        get() = db.getBoolean("show_fps", false)
        set(point) = db.edit().putBoolean("show_fps", point).apply()

    var statsOverlayCompact: Boolean
        get() = db.getBoolean("stats_overlay_compact", false)
        set(value) = db.edit().putBoolean("stats_overlay_compact", value).apply()

    fun statsOverlayPosition(landscape: Boolean): Pair<Int, Int>? {
        val key = if (landscape) "stats_overlay_landscape" else "stats_overlay_portrait"
        if (!db.contains("${key}_x") || !db.contains("${key}_y")) return null
        return db.getInt("${key}_x", 0) to db.getInt("${key}_y", 0)
    }

    fun saveStatsOverlayPosition(landscape: Boolean, x: Int, y: Int) {
        val key = if (landscape) "stats_overlay_landscape" else "stats_overlay_portrait"
        db.edit().putInt("${key}_x", x).putInt("${key}_y", y).apply()
    }

    var noAutoBrightness
        get() = db.getBoolean(KEY_AUTO_BRIGHTNESS_DISABLE, true)
        set(it) = db.edit().putBoolean(KEY_AUTO_BRIGHTNESS_DISABLE, it).apply()

    // Global default for the cleanup at game start; the per-game value is a GameOption.MEMORY override.
    var autoBoostMemory
        get() = db.getBoolean(KEY_AUTO_BOOST_MEMORY, false)
        set(value) = db.edit().putBoolean(KEY_AUTO_BOOST_MEMORY, value).apply()

    var danmakuNotification
        get() = db.getBoolean(KEY_DANMAKU_NOTIFICATION_MODE, true)
        set(value) = db.edit().putBoolean(KEY_DANMAKU_NOTIFICATION_MODE, value).apply()

    var callsMode: Int
        get() = db.getString(KEY_CALLS_MODE, "0")?.toIntOrNull() ?: 0
        set(value) = db.edit().putString(KEY_CALLS_MODE, value.toString()).apply()

    var ringerMode: Int
        get() = db.getString(KEY_RINGER_MODE, "3")?.toIntOrNull() ?: 3
        set(value) = db.edit().putString(KEY_RINGER_MODE, value.toString()).apply()

    var menuOpacity: Int
        get() {
            migrateHandleOpacity()
            return db.getInt(KEY_MENU_OPACITY, 100)
        }
        set(value) = db.edit().putInt(KEY_MENU_OPACITY, value).apply()

    var handleAutoHide: Boolean
        get() = db.getBoolean(KEY_HANDLE_AUTO_HIDE, false)
        set(value) = db.edit().putBoolean(KEY_HANDLE_AUTO_HIDE, value).apply()

    var tileOrder: List<String>
        get() = db.getString(KEY_TILE_ORDER, null)?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        set(value) = db.edit().putString(KEY_TILE_ORDER, value.joinToString(",")).apply()

    // A missing key and an explicitly empty selection have different migration semantics.
    val savedTileOrder: List<String>?
        get() = db.getString(KEY_TILE_ORDER, null)?.split(",")?.filter { it.isNotBlank() }

    val tileOrderVersion: Int get() = db.getInt(KEY_TILE_ORDER_VERSION, 0)

    fun saveTileConfiguration(order: List<String>, snapshot: List<String>, version: Int) {
        db.edit()
            .putString(KEY_PREFERRED_TILE_ORDER, order.joinToString(","))
            .putString(KEY_DEFAULT_TILE_SNAPSHOT, snapshot.joinToString(","))
            .putInt(KEY_TILE_ORDER_VERSION, version)
            .apply()
    }

    // Preserve selections while a capability is temporarily absent.
    var preferredTileOrder: List<String>?
        get() = db.getString(KEY_PREFERRED_TILE_ORDER, null)
            ?.split(",")?.filter { it.isNotBlank() }
        set(value) = db.edit().putString(KEY_PREFERRED_TILE_ORDER, value?.joinToString(",")).apply()

    var defaultTileSnapshot: List<String>?
        get() = db.getString(KEY_DEFAULT_TILE_SNAPSHOT, null)
            ?.split(",")?.filter { it.isNotBlank() }
        set(value) = db.edit().putString(KEY_DEFAULT_TILE_SNAPSHOT, value?.joinToString(",")).apply()

    var brightnessEnabled: Boolean
        get() = db.getBoolean(KEY_BRIGHTNESS_ENABLED, true)
        set(value) = db.edit().putBoolean(KEY_BRIGHTNESS_ENABLED, value).apply()

    var fpsGraphEnabled: Boolean
        get() = db.getBoolean(KEY_FPS_GRAPH_ENABLED, true)
        set(value) = db.edit().putBoolean(KEY_FPS_GRAPH_ENABLED, value).apply()
        
    var quickStartApps: String
        get() = db.getString(KEY_QUICK_START_APPS, "") ?: ""
        set(value) = db.edit().putString(KEY_QUICK_START_APPS, value).apply()

    var callOverlayEnabled
        get() = db.getBoolean(KEY_CALL_OVERLAY_ENABLED, true)
        set(point) = db.edit().putBoolean(KEY_CALL_OVERLAY_ENABLED, point).apply()

    fun migrateHandleOpacity() {
        if (!db.contains(KEY_MENU_OPACITY) && db.contains(KEY_ICON_IDLE_ALPHA)) {
            db.edit().putInt(KEY_MENU_OPACITY, db.getInt(KEY_ICON_IDLE_ALPHA, 25)).apply()
        }
    }

    var iconIdleAlpha: Int
        get() = menuOpacity
        set(value) { menuOpacity = value }

    val showStatusAtGameStart: Boolean
        get() = inGameOptions.current(GameOption.START_STATUS) == 1

    // The running game's saved choice, or the global default when no session is active.
    val wifiLowLatency: Boolean
        get() = inGameOptions.enabled(
            io.github.miner7222.gamespace.library.SessionOptions.packageName(context),
            GameOption.WIFI_LOW_LATENCY)

    val wifiLowLatencySupported: Boolean get() = inGameOptions.wifiLowLatencySupported()

    fun registerPreferenceListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        db.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterPreferenceListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        db.unregisterOnSharedPreferenceChangeListener(listener)

    var autoDnd: Boolean
        get() = db.getBoolean(KEY_AUTO_DND, false)
        set(value) = db.edit().putBoolean(KEY_AUTO_DND, value).apply()

    var lockGesture
        get() = db.getBoolean(KEY_LOCK_GESTURE, false)
        set(value) = db.edit().putBoolean(KEY_LOCK_GESTURE, value).apply()

    var stayAwake
        get() = db.getBoolean(KEY_STAY_AWAKE, false)
        set(value) = db.edit().putBoolean(KEY_STAY_AWAKE, value).apply()

    var noPulseBassHaptics
        get() = db.getBoolean(KEY_PULSE_BASS_DISABLE, true)
        set(it) = db.edit().putBoolean(KEY_PULSE_BASS_DISABLE, it).apply()

    companion object {
        const val KEY_BYPASS_CHARGE_GAMES = "bypass_charge_games"
        const val KEY_AUTO_BRIGHTNESS_DISABLE = "gamespace_auto_brightness_disabled"
        const val KEY_STAY_AWAKE = "gamespace_stay_awake"
        const val KEY_DANMAKU_NOTIFICATION_MODE = "gamespace_danmaku_notification_mode"
        const val KEY_AUTO_BOOST_MEMORY = "game_memory_management"
        const val KEY_CALLS_MODE = "gamespace_calls_mode"
        const val KEY_RINGER_MODE = "gamespace_ringer_mode"
        const val KEY_LOCK_GESTURE = "gamespace_lock_gesture"
        const val KEY_MENU_OPACITY = "gamespace_menu_opacity"
        const val KEY_HANDLE_AUTO_HIDE = "gamespace_handle_auto_hide"
        const val KEY_TILE_ORDER_VERSION = "tile_order_version"
        const val KEY_TILE_ORDER = "tile_order"
        const val KEY_PREFERRED_TILE_ORDER = "preferred_tile_order"
        const val KEY_DEFAULT_TILE_SNAPSHOT = "default_tile_snapshot"
        const val KEY_BRIGHTNESS_ENABLED = "brightness_enabled"
        const val KEY_FPS_GRAPH_ENABLED = "fps_graph_enabled"
        const val KEY_QUICK_START_APPS = "quick_start_apps"
        const val KEY_CALL_OVERLAY_ENABLED = "call_overlay_enabled"
        const val KEY_ICON_IDLE_ALPHA = "gamespace_icon_idle_alpha"
        const val KEY_AUTO_DND = "gamespace_auto_dnd"
        const val KEY_WIFI_LOW_LATENCY = "gamespace_wifi_low_latency"
        const val KEY_PULSE_BASS_DISABLE = "gamespace_pulse_bass_haptics_disabled"
    }
}
