// SPDX-FileCopyrightText: 2026 miner7222
// SPDX-License-Identifier: Apache-2.0

package io.github.miner7222.gamespace.library

import android.content.Context
import android.provider.Settings
import org.json.JSONObject
import io.chaldeaprjkt.gamespace.utils.GameModeUtils

/** Separates saved mode choices from the framework's temporarily published session mode. */
object ModePreferences {
    const val DEFAULT = "gamespace_default_game_mode"

    fun defaultMode(context: Context): Int = Settings.Secure.getIntForUser(
        context.contentResolver, DEFAULT, 1, context.userId).takeIf { it in 0..3 } ?: 1

    fun rawMode(context: Context, packageName: String): Int? = Settings.System.getStringForUser(
        context.contentResolver, LibraryKeys.GAMES, context.userId).orEmpty().split(';')
        .firstOrNull { it.substringBefore('=') == packageName }?.substringAfter('=')?.toIntOrNull()

    fun publish(context: Context, packageName: String, mode: Int): Unit = GameModeUtils.withModeLock {
        require(mode in 0..3)
        val raw = Settings.System.getStringForUser(context.contentResolver, LibraryKeys.GAMES, context.userId).orEmpty()
        val entries = raw.split(';').map { if (it.substringBefore('=') == packageName) "$packageName=$mode" else it }
        val next = entries.joinToString(";")
        if (next != raw) Settings.System.putStringForUser(context.contentResolver, LibraryKeys.GAMES, next, context.userId)
    }

    fun saveRecoveryMode(context: Context, packageName: String, mode: Int) {
        val prefs = context.getSharedPreferences("persisted_session", Context.MODE_PRIVATE)
        val raw = prefs.getString("session", "").orEmpty()
        if (raw.isEmpty()) return
        try {
            val state = JSONObject(raw)
            if (state.optString("packageName") != packageName) return
            state.put("preferredMode", mode)
            check(prefs.edit().putString("session", state.toString()).commit()) { "Cannot save preferred mode recovery state" }
        } catch (_: org.json.JSONException) { }
    }

    fun recoverIfIdle(context: Context) = SessionRecovery.recoverIfIdle(context)
}
