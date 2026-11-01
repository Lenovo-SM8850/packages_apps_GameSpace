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
import org.json.JSONObject

enum class GameOption(val key: String, val title: Int, val summary: Int, val default: Int,
    val entries: Int = 0, val values: Int = 0) {
    BRIGHTNESS("gamespace_auto_brightness_disabled", R.string.auto_brightness_disabled_title,
        R.string.auto_brightness_disabled_summary, 1),
    AWAKE("gamespace_stay_awake", R.string.stay_awake_title, R.string.stay_awake_summary, 0),
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
    SCREENSHOT("gamespace_tfgesture_disabled", R.string.three_screenshot_disabled_title,
        R.string.three_screenshot_disabled_summary, 0),
    GESTURES("gamespace_lock_gesture", R.string.lock_gesture_title,
        R.string.library_gesture_lock_summary, 0),
    TOUCH("gamespace_high_touch_polling_enabled", R.string.library_high_touch_title,
        R.string.library_high_touch_summary, 1),
    SIDEBAR("gamespace_hide_sidebar", R.string.library_hide_sidebar_title,
        R.string.library_hide_sidebar_summary, 1),
    BYPASS("bypass_charge_enabled", R.string.bypass_charge_enabled_title,
        R.string.library_bypass_summary, 0);

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

    /** Only the old enabled set carried information; absent packages inherit defaults. */
    fun ensureMigrated(): Boolean = synchronized(lock) {
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

    fun global(option: GameOption): Int = if (option == GameOption.BYPASS) {
        if (preferences.getBoolean(BYPASS_DEFAULT, false)) 1 else 0
    } else if (option.entries != 0) {
        preferences.getString(option.key, option.default.toString())?.toIntOrNull()
            ?.takeIf(option::accepts) ?: option.default
    } else if (preferences.getBoolean(option.key, option.default == 1)) 1 else 0

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
        Settings.Secure.putStringForUser(resolver, OVERRIDES, map.toString(), userId)
    }

    fun reset(packageName: String): Boolean = synchronized(lock) {
        if (!ensureMigrated()) return@synchronized false
        val map = readMap() ?: return@synchronized false
        map.remove(packageName)
        Settings.Secure.putStringForUser(resolver, OVERRIDES, map.toString(), userId)
    }

    fun availableOptions(): List<GameOption> = GameOption.entries.filter {
        when (it) {
            GameOption.HAPTICS -> context.getSystemService(Vibrator::class.java)?.hasVibrator() == true
            GameOption.TOUCH -> SidebarVisibility.isHighTouchSupported(context)
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
            if (key == null || key == BYPASS_DEFAULT || GameOption.entries.any { it.key == key }) {
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
        const val MIGRATED = "gamespace_in_game_overrides_migrated"
        const val BYPASS_DEFAULT = "gamespace_bypass_charge_default"
        private const val LEGACY_BYPASS = "bypass_charge_games"
        private val lock = Any()
    }
}
