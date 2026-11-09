// SPDX-FileCopyrightText: 2026 miner7222
// SPDX-License-Identifier: Apache-2.0

package io.github.miner7222.gamespace

import android.content.Context
import android.provider.Settings
import androidx.preference.PreferenceManager
import io.github.miner7222.gamespace.library.SessionOptions
import org.json.JSONObject

/** Global game touch values; a running game can override them for its own session. */
object TouchSensitivitySettings {
    const val KEY_HIGH_POLLING = "touch_sensitivity_high_polling"
    const val KEY_TAP = "touch_sensitivity_tap"
    const val KEY_MOVE = "touch_sensitivity_move"
    const val KEY_EDGE = "touch_sensitivity_edge"
    const val KEY_PEN_INPUT = "game_pen_input"
    const val OVERRIDES = "gamespace_touch_overrides"
    const val DEFAULTS = "gamespace_touch_defaults"
    private const val KEY_MIGRATED = "touch_sensitivity_migrated"
    private const val LEGACY_HIGH_POLLING = "gamespace_high_touch_polling_enabled"

    const val DEFAULT_TAP = 11
    const val DEFAULT_MOVE = 4
    const val DEFAULT_EDGE = 1

    private val keys = listOf(KEY_HIGH_POLLING, KEY_TAP, KEY_MOVE, KEY_EDGE, KEY_PEN_INPUT)
    private val lock = Any()

    fun migrate(context: Context) {
        val resolver = context.contentResolver
        if (Settings.Secure.getIntForUser(resolver, KEY_MIGRATED, 0, context.userId) == 1) return
        val legacy = PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(LEGACY_HIGH_POLLING, true)
        if (Settings.Secure.getIntForUser(resolver, KEY_HIGH_POLLING, -1, context.userId) < 0) {
            Settings.Secure.putIntForUser(resolver, KEY_HIGH_POLLING,
                if (legacy) 1 else 0, context.userId)
        }
        Settings.Secure.putIntForUser(resolver, KEY_MIGRATED, 1, context.userId)
    }

    fun highPolling(context: Context): Boolean =
        read(context, KEY_HIGH_POLLING, 1) == 1

    fun setHighPolling(context: Context, enabled: Boolean) {
        write(context, KEY_HIGH_POLLING, if (enabled) 1 else 0)
    }

    fun tap(context: Context): Int =
        read(context, KEY_TAP, DEFAULT_TAP).coerceIn(1, 21)

    /** 0..100 slider position; higher means more sensitive. */
    fun tapProgress(context: Context): Int = (21 - tap(context)) * 5

    fun setTap(context: Context, value: Int) {
        write(context, KEY_TAP, value.coerceIn(1, 21))
    }

    fun move(context: Context): Int =
        read(context, KEY_MOVE, DEFAULT_MOVE).coerceIn(2, 6)

    /** 0..100 slider position; higher means more sensitive. */
    fun moveProgress(context: Context): Int = (6 - move(context)) * 25

    fun setMove(context: Context, value: Int) {
        write(context, KEY_MOVE, value.coerceIn(2, 6))
    }

    fun edge(context: Context): Int =
        read(context, KEY_EDGE, DEFAULT_EDGE).coerceIn(0, 3)

    fun setEdge(context: Context, value: Int) {
        write(context, KEY_EDGE, value.coerceIn(0, 3))
    }

    fun penInput(context: Context): Boolean =
        read(context, KEY_PEN_INPUT, 0) == 1

    fun setPenInput(context: Context, enabled: Boolean) {
        write(context, KEY_PEN_INPUT, if (enabled) 1 else 0)
    }

    /** Value a screen shows: a game reads its override, the global screen reads the default. */
    fun value(context: Context, packageName: String?, key: String): Int = synchronized(lock) {
        val stored = packageName?.let { pkg ->
            readOverrides(context).optJSONObject(pkg)?.takeIf { it.has(key) }?.optInt(key)
        }
        coerce(key, stored ?: defaultValue(context, key))
    }

    /** True when this game has a value of its own for the key. */
    fun hasOverride(context: Context, packageName: String, key: String): Boolean = synchronized(lock) {
        readOverrides(context).optJSONObject(packageName)?.has(key) == true
    }

    fun hasOverrides(context: Context, packageName: String): Boolean = synchronized(lock) {
        val game = readOverrides(context).optJSONObject(packageName) ?: return@synchronized false
        keys.any { game.has(it) }
    }

    /** Write from an options screen; a game screen stores an override, the global one the default. */
    fun setValue(context: Context, packageName: String?, key: String, value: Int) {
        if (packageName == null) setDefaultValue(context, key, value)
        else setOverrideValue(context, packageName, key, value,
            applyLive = SessionOptions.packageName(context) == packageName &&
                SessionOptions.isActive(context))
    }

    /** Default currently shown when no running game overrides the key. */
    fun defaultValue(context: Context, key: String): Int = synchronized(lock) {
        val stored = readDefaults(context)
        if (stored != null && stored.has(key)) coerce(key, stored.optInt(key))
        else read(context, key, defaultFor(key))
    }

    /** Capture the live values before the first override of a session changes them. */
    private fun ensureDefaults(context: Context) {
        synchronized(lock) {
            if (readDefaults(context) != null) return
            val defaults = JSONObject()
            keys.forEach { defaults.put(it, read(context, it, defaultFor(it))) }
            putSecure(context, DEFAULTS, defaults.toString())
        }
    }

    /** Apply this game's overrides and persist the values needed to undo them if the service dies. */
    fun beginSession(context: Context, packageName: String): Boolean = synchronized(lock) {
        if (!hasOverrides(context, packageName)) return@synchronized false
        ensureDefaults(context)
        applyOverrides(context, packageName)
        true
    }

    /** Reapply the current overrides, for a resume after the session restored the defaults. */
    fun applyOverrides(context: Context, packageName: String) {
        synchronized(lock) {
            val game = readOverrides(context).optJSONObject(packageName) ?: return
            keys.forEach { key ->
                if (game.has(key)) write(context, key, coerce(key, game.optInt(key)))
            }
        }
    }

    /** Return the live keys to the captured defaults and drop the capture. */
    fun restoreDefaults(context: Context): Boolean = synchronized(lock) {
        val defaults = readDefaults(context) ?: return@synchronized false
        keys.forEach { key ->
            if (defaults.has(key)) write(context, key, coerce(key, defaults.optInt(key)))
        }
        Settings.Secure.putStringForUser(context.contentResolver, DEFAULTS, null, context.userId)
        true
    }

    /** Reset this game's touch overrides, restoring the defaults while it is running. */
    fun clearOverrides(context: Context, packageName: String, applyLive: Boolean) {
        synchronized(lock) {
            val map = readOverrides(context)
            map.remove(packageName)
            putSecure(context, OVERRIDES, map.toString())
            if (!applyLive) return
            val defaults = readDefaults(context)
            keys.forEach { key ->
                val value = if (defaults != null && defaults.has(key)) coerce(key, defaults.optInt(key))
                    else read(context, key, defaultFor(key))
                write(context, key, value)
            }
        }
    }

    private fun setDefaultValue(context: Context, key: String, raw: Int) {
        val value = coerce(key, raw)
        synchronized(lock) {
            if (liveOverrideApplied(context, key)) ensureDefaults(context)
            val defaults = readDefaults(context)
            if (defaults != null) {
                defaults.put(key, value)
                putSecure(context, DEFAULTS, defaults.toString())
            }
            // A running override stays visible; otherwise the live key is the default.
            if (!liveOverrideApplied(context, key)) write(context, key, value)
        }
    }

    private fun setOverrideValue(context: Context, packageName: String, key: String, raw: Int,
        applyLive: Boolean) {
        val value = coerce(key, raw)
        synchronized(lock) {
            if (applyLive) ensureDefaults(context)
            val default = defaultValue(context, key)
            val map = readOverrides(context)
            val game = map.optJSONObject(packageName) ?: JSONObject()
            // Choosing the default releases the override.
            if (value == default) game.remove(key) else game.put(key, value)
            if (game.length() == 0) map.remove(packageName) else map.put(packageName, game)
            putSecure(context, OVERRIDES, map.toString())
            if (applyLive) write(context, key, value)
        }
    }

    private fun liveOverrideApplied(context: Context, key: String): Boolean {
        val packageName = SessionOptions.packageName(context) ?: return false
        return SessionOptions.isActive(context) && hasOverride(context, packageName, key)
    }

    private fun readOverrides(context: Context): JSONObject = try {
        val raw = Settings.Secure.getStringForUser(context.contentResolver, OVERRIDES, context.userId)
        if (raw.isNullOrEmpty()) JSONObject() else JSONObject(raw)
    } catch (_: RuntimeException) {
        JSONObject()
    } catch (_: org.json.JSONException) {
        JSONObject()
    }

    private fun readDefaults(context: Context): JSONObject? = try {
        val raw = Settings.Secure.getStringForUser(context.contentResolver, DEFAULTS, context.userId)
        if (raw.isNullOrEmpty()) null else JSONObject(raw)
    } catch (_: RuntimeException) {
        null
    } catch (_: org.json.JSONException) {
        null
    }

    private fun putSecure(context: Context, key: String, value: String) {
        Settings.Secure.putStringForUser(context.contentResolver, key, value, context.userId)
    }

    private fun defaultFor(key: String): Int = when (key) {
        KEY_HIGH_POLLING -> 1
        KEY_TAP -> DEFAULT_TAP
        KEY_MOVE -> DEFAULT_MOVE
        KEY_EDGE -> DEFAULT_EDGE
        else -> 0
    }

    private fun coerce(key: String, value: Int): Int = when (key) {
        KEY_HIGH_POLLING, KEY_PEN_INPUT -> if (value == 1) 1 else 0
        KEY_TAP -> value.coerceIn(1, 21)
        KEY_MOVE -> value.coerceIn(2, 6)
        KEY_EDGE -> value.coerceIn(0, 3)
        else -> value
    }

    private fun read(context: Context, key: String, default: Int): Int =
        Settings.Secure.getIntForUser(context.contentResolver, key, default, context.userId)

    private fun write(context: Context, key: String, value: Int) {
        Settings.Secure.putIntForUser(context.contentResolver, key, value, context.userId)
    }
}
