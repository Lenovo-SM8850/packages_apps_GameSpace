// SPDX-FileCopyrightText: 2026 miner7222
// SPDX-License-Identifier: Apache-2.0

package io.github.miner7222.gamespace.library

import android.content.Context

/** Panel choices live only between a service's game start and full session end. */
object SessionOptions {
    private class State(val packageName: String, var savedMode: Int) {
        val choices = mutableMapOf<GameOption, Int>()
        var wifi: Boolean? = null
        var mode: Int? = null
        var autoBrightness: Boolean? = null
        var brightness: Float? = null
        var suspended = false
    }
    private val states = mutableMapOf<Int, State>()
    private val listeners = mutableMapOf<Int, MutableSet<() -> Unit>>()

    fun start(context: Context, packageName: String) {
        if (states[context.userId]?.packageName == packageName) return
        states[context.userId] = State(packageName,
            ModePreferences.rawMode(context, packageName) ?: ModePreferences.defaultMode(context))
        notify(context)
    }

    fun end(context: Context) {
        val state = states.remove(context.userId) ?: return
        ModePreferences.publish(context, state.packageName, state.savedMode)
        notify(context)
    }

    fun isActive(context: Context): Boolean = states[context.userId]?.suspended == false

    fun setSuspended(context: Context, suspended: Boolean) {
        val state = states[context.userId] ?: return
        state.suspended = suspended
        notify(context)
    }

    fun brightness(context: Context): Float? = states[context.userId]?.brightness
    fun chooseBrightness(context: Context, percent: Float) {
        states[context.userId]?.brightness = percent
    }

    fun packageName(context: Context): String? = states[context.userId]?.packageName

    fun value(context: Context, option: GameOption): Int {
        val state = states[context.userId]
        return state?.choices?.get(option) ?: InGameOptions(context).effective(state?.packageName, option)
    }

    fun choose(context: Context, option: GameOption, value: Int): Boolean {
        require(option.accepts(value))
        val state = states[context.userId] ?: return false
        state.choices[option] = value
        if (option == GameOption.BRIGHTNESS) state.autoBrightness = null
        notify(context)
        return true
    }

    fun hasChoice(context: Context, option: GameOption): Boolean =
        states[context.userId]?.choices?.containsKey(option) == true

    /** Compare values, not whether the panel has ever made a choice. */
    fun differsFromSaved(context: Context, option: GameOption): Boolean {
        val state = states[context.userId] ?: return false
        val saved = InGameOptions(context).effective(state.packageName, option)
        return (state.choices[option] ?: saved) != saved
    }

    fun wifiDiffersFromSaved(context: Context, saved: Boolean): Boolean {
        val state = states[context.userId] ?: return false
        return (state.wifi ?: saved) != saved
    }

    fun modeDiffersFromSaved(context: Context): Boolean {
        val state = states[context.userId] ?: return false
        return (state.mode ?: state.savedMode) != state.savedMode
    }

    fun autoBrightness(context: Context): Boolean? = states[context.userId]?.autoBrightness
    fun chooseAutoBrightness(context: Context, enabled: Boolean): Boolean {
        val state = states[context.userId] ?: return false
        state.autoBrightness = enabled
        state.choices[GameOption.BRIGHTNESS] = if (enabled) 0 else 1
        notify(context)
        return true
    }

    fun wifi(context: Context, global: Boolean): Boolean = states[context.userId]?.wifi ?: global
    fun chooseWifi(context: Context, enabled: Boolean): Boolean {
        val state = states[context.userId] ?: return false
        state.wifi = enabled
        notify(context)
        return true
    }

    fun savedMode(context: Context, packageName: String): Int? =
        states[context.userId]?.takeIf { it.packageName == packageName }?.savedMode

    fun saveMode(context: Context, packageName: String, mode: Int) {
        require(mode in 0..3)
        states[context.userId]?.takeIf { it.packageName == packageName }?.savedMode = mode
        ModePreferences.saveRecoveryMode(context, packageName, mode)
        notify(context)
    }

    fun mode(context: Context): Int? = states[context.userId]?.mode
    fun chooseMode(context: Context, mode: Int): Boolean {
        require(mode in 0..3)
        val state = states[context.userId] ?: return false
        state.mode = mode
        state.savedMode = mode
        ModePreferences.saveRecoveryMode(context, state.packageName, mode)
        if (!state.suspended) ModePreferences.publish(context, state.packageName, mode)
        notify(context)
        return true
    }

    fun observe(context: Context, onChange: () -> Unit): AutoCloseable {
        val callbacks = listeners.getOrPut(context.userId) { mutableSetOf() }
        callbacks.add(onChange)
        return AutoCloseable { callbacks.remove(onChange) }
    }

    private fun notify(context: Context) {
        listeners[context.userId]?.toList()?.forEach { it() }
    }
}
