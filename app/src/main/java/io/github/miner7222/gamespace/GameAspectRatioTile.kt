// SPDX-License-Identifier: Apache-2.0
package io.github.miner7222.gamespace

import android.app.ActivityTaskManager
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import dagger.hilt.android.qualifiers.ApplicationContext
import io.chaldeaprjkt.gamespace.R
import io.chaldeaprjkt.gamespace.gamebar.tiles.TileAction
import io.chaldeaprjkt.gamespace.gamebar.tiles.TileOption
import org.json.JSONException
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GameAspectRatioTile @Inject constructor(
    @ApplicationContext private val context: Context,
) : TileAction {
    private var gamePackage: String? = null
    private var gameUserId = context.userId
    private val mode = mutableStateOf<String?>(null)
    private var available = false
    // The framework applies ratios from any writer, so follow the setting while a game runs.
    private var ratioObserver: ContentObserver? = null

    override val id = "screen_aspect_ratio"
    override val icon = R.drawable.materialsymbols_ic_aspect_ratio_rounded_filled
    // Names the selected ratio, or the tile itself while the game uses the full screen.
    override val label: String
        get() = mode.value?.let { modeName(it) } ?: context.getString(R.string.tile_aspect_ratio)
    override val isEnabled: Boolean
        get() = available && mode.value != null
    // The picker lists only centered modes; a stored bottom placement shows as its ratio.
    override val options: List<TileOption>
        get() {
            if (!available) return emptyList()
            val selected = mode.value?.let { if (it.startsWith(BOTTOM_PREFIX)) CENTER_PREFIX +
                it.removePrefix(BOTTOM_PREFIX) else it }
            return PICKER_MODES.map { TileOption(it ?: FULLSCREEN_ID, modeName(it), it == selected) }
        }
    val isAvailable: Boolean get() = available
    /** The stored encoding for the current game, or null for full screen. */
    val currentMode: String? get() = mode.value
    /** Called after the availability or stored mode may have changed; may run on any thread. */
    var onChanged: (() -> Unit)? = null

    @Composable
    override fun observeEnabled(): State<Boolean> = rememberUpdatedState(isEnabled)

    fun onGameStart(packageName: String) {
        gamePackage = packageName
        gameUserId = resolveGameUserId(packageName)
        observeRatios()
        refresh()
    }

    fun onGameStop() {
        unobserveRatios()
        gamePackage = null
        gameUserId = context.userId
        available = false
        mode.value = null
        onChanged?.invoke()
    }

    fun refresh() {
        reload()
        onChanged?.invoke()
    }

    private fun observeRatios() {
        unobserveRatios()
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refresh()
        }
        context.contentResolver.registerContentObserver(Settings.Secure.getUriFor(SETTING), false,
            observer, gameUserId)
        ratioObserver = observer
    }

    private fun unobserveRatios() {
        val observer = ratioObserver ?: return
        ratioObserver = null
        context.contentResolver.unregisterContentObserver(observer)
    }

    private fun reload() {
        available = false
        val pkg = gamePackage ?: return
        try {
            val gameContext = context.createContextAsUser(UserHandle.of(gameUserId), 0)
            if (gameContext.packageManager.getLaunchIntentForPackage(pkg) == null) return
            val stored = readRatios(gameUserId).optString(pkg)
            mode.value = stored.takeIf { it in MODES }
            available = true
        } catch (e: JSONException) {
            Log.w(TAG, "Unable to read game aspect ratio", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "Unable to read game aspect ratio", e)
        }
    }

    // Tapping the tile only reloads the stored mode before the picker lists it.
    override fun toggle() = refresh()

    override fun selectOption(option: TileOption) {
        val next = if (option.id == FULLSCREEN_ID) null else option.id
        if (next !in PICKER_MODES) return
        reload()
        // Keep a bottom placement while switching between ratios.
        val keepBottom = mode.value?.startsWith(BOTTOM_PREFIX) == true
        select(if (keepBottom && next != null && next != DEEP_VIEW) {
            BOTTOM_PREFIX + next.removePrefix(CENTER_PREFIX)
        } else {
            next
        })
    }

    /** Moves the game window to the bottom or back to the center, keeping its ratio. */
    fun setBottomPlacement(bottom: Boolean) {
        reload()
        val current = mode.value ?: return
        if (current == DEEP_VIEW) return
        select((if (bottom) BOTTOM_PREFIX else CENTER_PREFIX) + current.substringAfter(':'))
    }

    fun select(next: String?) {
        reload()
        val pkg = gamePackage ?: return
        if (!available) return
        try {
            val userId = gameUserId
            val ratios = readRatios(userId)
            if (next == null) ratios.remove(pkg) else ratios.put(pkg, next)
            if (!Settings.Secure.putStringForUser(context.contentResolver, SETTING,
                    ratios.toString(), userId)) {
                showFailure()
                return
            }
            mode.value = next
            onChanged?.invoke()
        } catch (e: JSONException) {
            Log.w(TAG, "Unable to change game aspect ratio", e)
            showFailure()
        } catch (e: SecurityException) {
            Log.w(TAG, "Unable to change game aspect ratio", e)
            showFailure()
        }
    }

    private fun resolveGameUserId(packageName: String): Int {
        // Prefer the session user's game when the same package runs for multiple users.
        try {
            val tasks = ActivityTaskManager.getInstance().getTasks(Int.MAX_VALUE).filter {
                it.baseActivity?.packageName == packageName ||
                    it.topActivity?.packageName == packageName
            }
            val task = tasks.firstOrNull { it.userId == context.userId && it.isVisible }
                ?: tasks.firstOrNull { it.isFocused }
                ?: tasks.firstOrNull { it.isVisible }
                ?: tasks.firstOrNull { it.userId == context.userId }
            if (task != null) return task.userId
        } catch (e: Exception) {
            Log.w(TAG, "Unable to query game task user", e)
        }
        // The session service is started as the game's user before its task may be visible.
        return context.userId
    }

    private fun readRatios(userId: Int): JSONObject {
        val stored = Settings.Secure.getStringForUser(context.contentResolver, SETTING, userId)
        return if (stored.isNullOrEmpty()) JSONObject() else JSONObject(stored)
    }

    private fun modeName(value: String?): String {
        if (value == null) return context.getString(R.string.aspect_ratio_fullscreen)
        if (value == DEEP_VIEW) return context.getString(R.string.aspect_ratio_deep_view)
        return context.getString(when (value.substringAfter(':')) {
            "1.8" -> R.string.aspect_ratio_16_9
            "2.1" -> R.string.aspect_ratio_21_10
            "2.4" -> R.string.aspect_ratio_24_10
            "2.8" -> R.string.aspect_ratio_28_10
            else -> R.string.aspect_ratio_32_9
        })
    }

    private fun showFailure() {
        Toast.makeText(context, R.string.aspect_ratio_change_failed, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "GameAspectRatioTile"
        // Framework-owned per-user window geometry, independent of PackageManager overrides.
        private const val SETTING = "game_window_ratios"
        // Picker id of the full screen mode, which is stored as no entry.
        private const val FULLSCREEN_ID = ""
        const val CENTER_PREFIX = "1:"
        const val BOTTOM_PREFIX = "2:"
        const val DEEP_VIEW = "1:1.2"
        private val PICKER_MODES = listOf(null, "1:1.8", "1:2.1", "1:2.4", "1:2.8", "1:3.5",
            DEEP_VIEW)
        private val MODES = listOf(null, "1:1.8", "2:1.8", "1:2.1", "2:2.1", "1:2.4", "2:2.4",
            "1:2.8", "2:2.8", "1:3.5", "2:3.5", "1:1.2")
    }
}
