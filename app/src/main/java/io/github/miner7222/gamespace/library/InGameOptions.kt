// SPDX-FileCopyrightText: 2026 miner7222
// SPDX-License-Identifier: Apache-2.0

package io.github.miner7222.gamespace.library

import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemProperties
import android.os.Vibrator
import android.provider.Settings
import androidx.preference.PreferenceManager
import io.chaldeaprjkt.gamespace.R
import io.chaldeaprjkt.gamespace.data.AppSettings
import io.github.miner7222.gamespace.GameSplitAspectRatio
import org.json.JSONObject

enum class GameOptionGroup(val title: Int, val options: List<GameOption>) {
    SCREEN(R.string.options_screen, listOf(GameOption.START_STATUS, GameOption.BRIGHTNESS,
        GameOption.AWAKE, GameOption.SPLIT_RATIO, GameOption.SIDEBAR)),
    NOTIFICATIONS(R.string.options_notifications, listOf(GameOption.DANMAKU, GameOption.DND,
        GameOption.CALL_OVERLAY, GameOption.CALLS, GameOption.RINGER)),
    HARDWARE(R.string.options_hardware, listOf(GameOption.GESTURES,
        GameOption.HAPTICS, GameOption.BYPASS));
}

enum class GameOption(val key: String, val title: Int, val summary: Int, val default: Int,
    val entries: Int = 0, val values: Int = 0) {
    BRIGHTNESS("gamespace_auto_brightness_disabled", R.string.auto_brightness_disabled_title, 0, 1),
    AWAKE("gamespace_stay_awake", R.string.stay_awake_title, 0, 0),
    DANMAKU("gamespace_danmaku_notification_mode", R.string.danmaku_notification_mode_title,
        R.string.danmaku_notification_mode_summary, 1),
    HAPTICS("gamespace_pulse_bass_haptics_disabled", R.string.pulse_bass_haptics_disabled_title,
        R.string.pulse_bass_haptics_disabled_summary, 1),
    DND("gamespace_auto_dnd", R.string.auto_dnd_title, R.string.auto_dnd_summary, 0),
    CALL_OVERLAY("call_overlay_enabled", R.string.call_overlay_enabled_title,
        R.string.call_overlay_enabled_summary, 1),
    CALLS("gamespace_calls_mode", R.string.in_game_calls_title, 0, 0,
        R.array.in_game_calls_entries, R.array.in_game_calls_values),
    RINGER("gamespace_ringer_mode", R.string.ringer_mode_title, 0, 3,
        R.array.ringer_mode_entries, R.array.ringer_mode_values),
    GESTURES("gamespace_lock_gesture", R.string.tile_gesture_lock,
        R.string.library_gesture_lock_summary, 0),
    SIDEBAR("gamespace_hide_sidebar", R.string.library_hide_sidebar_title, 0, 1),
    BYPASS("bypass_charge_enabled", R.string.bypass_charge_enabled_title,
        R.string.library_bypass_summary, 0),
    START_STATUS("gamespace_show_status_at_game_start", R.string.show_status_at_game_start_title, 0, 1),
    SPLIT_RATIO("gamespace_split_keep_ratio", R.string.per_app_keep_aspect_ratio_title,
        R.string.per_app_keep_aspect_ratio_summary, 0);

    fun accepts(value: Int): Boolean = when (this) {
        CALLS -> value in 0..2
        RINGER -> value in 0..3
        else -> value in 0..1
    }
}

/** Per-user overrides; globals remain in the existing default preferences. */
class InGameOptions(private val context: Context) {
    private val resolver = context.contentResolver
    private val userId = context.userId
    private val preferences = PreferenceManager.getDefaultSharedPreferences(context)

    private fun readMap(): JSONObject? = try {
        JSONObject(Settings.Secure.getStringForUser(resolver, OVERRIDES, userId).orEmpty().ifEmpty { "{}" })
    } catch (_: RuntimeException) { null }
      catch (_: org.json.JSONException) { null }

    private fun migrateSplitRatio(): Boolean {
        if (Settings.Secure.getIntForUser(resolver, SPLIT_RATIO_MIGRATED, 0, userId) == 1) return true
        val map = readMap() ?: return false
        GameSplitAspectRatio.readPackages(resolver, userId).forEach { pkg ->
            val game = map.optJSONObject(pkg) ?: JSONObject()
            if (!game.has(GameOption.SPLIT_RATIO.key)) game.put(GameOption.SPLIT_RATIO.key, 1)
            map.put(pkg, game)
        }
        return Settings.Secure.putStringForUser(resolver, OVERRIDES, map.toString(), userId) &&
            Settings.Secure.putIntForUser(resolver, SPLIT_RATIO_MIGRATED, 1, userId)
    }

    /** Publish effective choices through the existing window-manager package-set contract. */
    fun syncSplitAspectRatio(): Boolean = synchronized(lock) {
        if (!ensureMigrated()) return@synchronized false
        val map = readMap() ?: return@synchronized false
        val packages = Settings.System.getStringForUser(resolver, LibraryKeys.GAMES, userId)
            .orEmpty().split(';').map { it.substringBefore('=') }.filter { it.isNotEmpty() }.toMutableSet()
        val keys = map.keys()
        while (keys.hasNext()) {
            val pkg = keys.next()
            if (map.optJSONObject(pkg)?.has(GameOption.SPLIT_RATIO.key) == true) packages.add(pkg)
        }
        val fallback = global(GameOption.SPLIT_RATIO)
        val enabled = packages.filterTo(mutableSetOf()) { pkg ->
            val value = map.optJSONObject(pkg)?.optInt(GameOption.SPLIT_RATIO.key, -1) ?: -1
            (value.takeIf(GameOption.SPLIT_RATIO::accepts) ?: fallback) == 1
        }
        GameSplitAspectRatio.publish(resolver, userId, enabled)
    }

    fun clearSplitAspectRatio(packageName: String): Boolean = synchronized(lock) {
        if (!ensureMigrated()) return@synchronized false
        val map = readMap() ?: return@synchronized false
        map.optJSONObject(packageName)?.let { game ->
            game.remove(GameOption.SPLIT_RATIO.key)
            if (game.length() == 0) map.remove(packageName)
        }
        Settings.Secure.putStringForUser(resolver, OVERRIDES, map.toString(), userId) && syncSplitAspectRatio()
    }

    /** Merge the retired three-finger option once, including inherited per-game values. */
    private fun migrateGestureLock(): Boolean {
        if (preferences.getBoolean(GESTURE_LOCK_MIGRATED, false)) return true
        val map = readMap() ?: return false
        val legacyDefault = preferences.getBoolean(LEGACY_THREE_FINGER, false)
        val gestureDefault = preferences.getBoolean(GameOption.GESTURES.key, false)
        val mergedDefault = legacyDefault || gestureDefault
        val packages = map.keys()
        while (packages.hasNext()) {
            val game = map.optJSONObject(packages.next()) ?: continue
            val legacy = game.optInt(LEGACY_THREE_FINGER, if (legacyDefault) 1 else 0) == 1
            val gestures = game.optInt(GameOption.GESTURES.key, if (gestureDefault) 1 else 0) == 1
            // Retain legacy values and explicit results until both stores commit, for retries.
            game.put(GameOption.GESTURES.key, if (legacy || gestures) 1 else 0)
        }
        if (!Settings.Secure.putStringForUser(resolver, OVERRIDES, map.toString(), userId)) return false
        if (!preferences.edit()
            .putBoolean(GameOption.GESTURES.key, mergedDefault)
            .remove(LEGACY_THREE_FINGER)
            .putBoolean(GESTURE_LOCK_MIGRATED, true)
            .commit()) return false
        val migratedPackages = map.keys()
        while (migratedPackages.hasNext()) {
            val game = map.optJSONObject(migratedPackages.next()) ?: continue
            game.remove(LEGACY_THREE_FINGER)
            if (game.optInt(GameOption.GESTURES.key) == (if (mergedDefault) 1 else 0)) {
                game.remove(GameOption.GESTURES.key)
            }
        }
        // Cleanup is optional: a failed write leaves equivalent explicit overrides.
        Settings.Secure.putStringForUser(resolver, OVERRIDES, map.toString(), userId)
        return true
    }

    /** Only the old enabled set carried information; absent packages inherit defaults. */
    fun ensureMigrated(): Boolean = synchronized(lock) {
        if (!migrateGestureLock() || !migrateSplitRatio()) return@synchronized false
        if (Settings.Secure.getIntForUser(resolver, MIGRATED, 0, userId) == 1) {
            if (preferences.contains(LEGACY_BYPASS)) preferences.edit().remove(LEGACY_BYPASS).apply()
            return@synchronized true
        }
        val map = readMap() ?: return@synchronized false
        preferences.getStringSet(LEGACY_BYPASS, emptySet()).orEmpty().forEach { pkg ->
            val game = map.optJSONObject(pkg) ?: JSONObject()
            if (!game.has(GameOption.BYPASS.key)) game.put(GameOption.BYPASS.key, 1)
            map.put(pkg, game)
        }
        if (Settings.Secure.putStringForUser(resolver, OVERRIDES, map.toString(), userId) &&
            Settings.Secure.putIntForUser(resolver, MIGRATED, 1, userId)) {
            preferences.edit().remove(LEGACY_BYPASS).apply()
            true
        } else false
    }

    fun global(option: GameOption): Int {
        if (option == GameOption.GESTURES) ensureMigrated()
        return if (option == GameOption.BYPASS) {
            if (preferences.getBoolean(BYPASS_DEFAULT, false)) 1 else 0
        } else if (option.entries != 0) {
            preferences.getString(option.key, option.default.toString())?.toIntOrNull()
                ?.takeIf(option::accepts) ?: option.default
        } else if (preferences.getBoolean(option.key, option.default == 1)) 1 else 0
    }

    fun setGlobal(option: GameOption, value: Int) {
        require(option.accepts(value))
        if (option == GameOption.SPLIT_RATIO && !ensureMigrated()) return
        if (option == GameOption.BYPASS) setBypassDefault(value == 1)
        else if (option.entries != 0) preferences.edit().putString(option.key, value.toString()).apply()
        else preferences.edit().putBoolean(option.key, value == 1).apply()
        if (option == GameOption.SPLIT_RATIO) syncSplitAspectRatio()
    }

    fun setBypassDefault(enabled: Boolean) {
        preferences.edit().putBoolean(BYPASS_DEFAULT, enabled).apply()
    }

    fun overrides(packageName: String): Map<GameOption, Int> {
        ensureMigrated()
        val game = readMap()?.optJSONObject(packageName) ?: return emptyMap()
        return GameOption.entries.mapNotNull { option ->
            val value = game.optInt(option.key, -1)
            if (option.accepts(value)) option to value else null
        }.toMap()
    }

    fun effective(packageName: String?, option: GameOption): Int =
        packageName?.let { overrides(it)[option] } ?: global(option)

    fun enabled(packageName: String?, option: GameOption) = effective(packageName, option) == 1

    fun currentPackage(): String? = SessionOptions.packageName(context)

    fun current(option: GameOption): Int = SessionOptions.value(context, option)

    fun set(packageName: String, option: GameOption, value: Int): Boolean = synchronized(lock) {
        require(packageName.isNotEmpty() && option.accepts(value))
        if (!ensureMigrated()) return@synchronized false
        val map = readMap() ?: return@synchronized false
        val game = map.optJSONObject(packageName) ?: JSONObject()
        // Choosing the current default releases this game's override for that option.
        if (value == global(option)) game.remove(option.key) else game.put(option.key, value)
        if (game.length() == 0) map.remove(packageName) else map.put(packageName, game)
        val saved = Settings.Secure.putStringForUser(resolver, OVERRIDES, map.toString(), userId)
        if (saved && option == GameOption.GESTURES && currentPackage() == packageName) {
            SessionOptions.choose(context, option, value)
        }
        if (saved && option == GameOption.SPLIT_RATIO) syncSplitAspectRatio() else saved
    }

    fun reset(packageName: String): Boolean = synchronized(lock) {
        if (!ensureMigrated()) return@synchronized false
        val map = readMap() ?: return@synchronized false
        map.remove(packageName)
        val saved = Settings.Secure.putStringForUser(resolver, OVERRIDES, map.toString(), userId)
        if (saved && currentPackage() == packageName) {
            SessionOptions.choose(context, GameOption.GESTURES, global(GameOption.GESTURES))
        }
        if (saved) syncSplitAspectRatio() else false
    }

    fun availableOptions(): List<GameOption> = GameOption.entries.filter {
        when (it) {
            GameOption.HAPTICS -> context.getSystemService(Vibrator::class.java)?.hasVibrator() == true
            GameOption.SIDEBAR -> SidebarVisibility.isAvailable(context)
            GameOption.BYPASS -> SystemProperties.getBoolean("persist.sys.battery_bypass_supported", false)
            else -> true
        }
    }

    /** Watches both sources, with callbacks serialized on the main thread. */
    fun observe(onChange: () -> Unit): AutoCloseable {
        val handler = Handler(Looper.getMainLooper())
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) { onChange() }
        }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == BYPASS_DEFAULT || key == AppSettings.KEY_WIFI_LOW_LATENCY ||
                GameOption.entries.any { it.key == key }) {
                handler.post { onChange() }
            }
        }
        val packages = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.data?.schemeSpecificPart == SidebarVisibility.PACKAGE) onChange()
            }
        }
        context.registerReceiver(packages, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }, Context.RECEIVER_NOT_EXPORTED)
        val session = SessionOptions.observe(context) { handler.post { onChange() } }
        resolver.registerContentObserver(Settings.Secure.getUriFor(OVERRIDES), false, observer, userId)
        resolver.registerContentObserver(Settings.System.getUriFor(LibraryKeys.GAMES), false, observer, userId)
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return AutoCloseable {
            session.close()
            context.unregisterReceiver(packages)
            resolver.unregisterContentObserver(observer)
            preferences.unregisterOnSharedPreferenceChangeListener(listener)
            handler.removeCallbacksAndMessages(null)
        }
    }

    companion object {
        const val OVERRIDES = "gamespace_in_game_overrides"
        private const val SPLIT_RATIO_MIGRATED = "gamespace_split_keep_ratio_migrated"
        const val MIGRATED = "gamespace_in_game_overrides_migrated"
        const val BYPASS_DEFAULT = "gamespace_bypass_charge_default"
        private const val LEGACY_BYPASS = "bypass_charge_games"
        private const val LEGACY_THREE_FINGER = "gamespace_tfgesture_disabled"
        private const val GESTURE_LOCK_MIGRATED = "gamespace_gesture_lock_migrated"
        private val lock = Any()
    }
}
