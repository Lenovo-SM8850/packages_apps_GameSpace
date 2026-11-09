// SPDX-FileCopyrightText: 2026 miner7222
// SPDX-License-Identifier: Apache-2.0

package io.github.miner7222.gamespace

import android.content.Context
import android.provider.Settings
import androidx.preference.PreferenceManager

/** Global game touch values; the touch policy service applies them per session. */
object TouchSensitivitySettings {
    const val KEY_HIGH_POLLING = "touch_sensitivity_high_polling"
    const val KEY_TAP = "touch_sensitivity_tap"
    const val KEY_MOVE = "touch_sensitivity_move"
    const val KEY_EDGE = "touch_sensitivity_edge"
    const val KEY_PEN_INPUT = "game_pen_input"
    private const val KEY_MIGRATED = "touch_sensitivity_migrated"
    private const val LEGACY_HIGH_POLLING = "gamespace_high_touch_polling_enabled"

    const val DEFAULT_TAP = 11
    const val DEFAULT_MOVE = 4
    const val DEFAULT_EDGE = 1

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

    private fun read(context: Context, key: String, default: Int): Int =
        Settings.Secure.getIntForUser(context.contentResolver, key, default, context.userId)

    private fun write(context: Context, key: String, value: Int) {
        Settings.Secure.putIntForUser(context.contentResolver, key, value, context.userId)
    }
}
