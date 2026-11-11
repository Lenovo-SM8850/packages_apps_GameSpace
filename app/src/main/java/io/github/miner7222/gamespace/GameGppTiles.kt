// SPDX-License-Identifier: Apache-2.0
package io.github.miner7222.gamespace

import android.app.ActivityTaskManager
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemProperties
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.preference.PreferenceManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import dagger.hilt.android.qualifiers.ApplicationContext
import io.chaldeaprjkt.gamespace.R
import io.chaldeaprjkt.gamespace.gamebar.tiles.TileAction
import io.chaldeaprjkt.gamespace.gamebar.tiles.TileCategory
import io.chaldeaprjkt.gamespace.gamebar.tiles.TileKind
import io.chaldeaprjkt.gamespace.gamebar.tiles.TileOption
import org.json.JSONObject
import io.github.miner7222.gamespace.library.SessionOptions
import io.github.miner7222.gamespace.library.ModePreferences
import javax.inject.Inject
import javax.inject.Singleton

/** All off paths use these per-user maps; remembered tile choices are separate. */
object GameGppChoices {
    fun read(context: Context, userId: Int, key: String): JSONObject {
        val raw = Settings.Secure.getStringForUser(context.contentResolver, key, userId)
        return if (raw.isNullOrEmpty()) JSONObject() else JSONObject(raw)
    }
    fun write(context: Context, userId: Int, pkg: String, key: String, next: Int): Boolean {
        val map = read(context, userId, key)
        if (next == 0) map.remove(pkg) else map.put(pkg, next)
        return Settings.Secure.putStringForUser(context.contentResolver, key, map.toString(), userId)
    }
    fun userId(context: Context, packageName: String): Int {
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
            Log.w("GameGppTile", "Unable to query game task user", e)
        }
        return context.userId
    }

    /** Clears both choices for [forUser], or for the user running the game when it is null. */
    fun clear(context: Context, pkg: String, forUser: Int? = null): Boolean {
        val user = forUser ?: userId(context, pkg)
        for (key in listOf("game_frame_interp", "game_super_resolution")) {
            if (read(context, user, key).has(pkg) &&
                !write(context, user, pkg, key, 0)) return false
        }
        return true
    }
}

/**
 * A per-game choice for the game post-processing service. The dashboard stores it in a
 * Settings.Secure map that the tuning session service reads; the service applies and releases it.
 */
abstract class GameGppTile(
    protected val context: Context,
    private val setting: String,
    private val values: List<Int>,
) : TileAction {
    private var gamePackage: String? = null
    protected var gameUserId = context.userId
    private val mode = mutableStateOf(0)
    private val noticeState = mutableStateOf<String?>(null)
    private var available = false
    private var observer: ContentObserver? = null

    override val kind = TileKind.DIALOG
    override val category = TileCategory.DISPLAY
    override val togglesLastChoice = true
    override val valueLine: String get() = optionLabel(mode.value)
    override val isEnabled: Boolean get() = available && mode.value != 0
    override val isAvailable: Boolean get() = available
    override val notice: String? get() = noticeState.value
    override val options: List<TileOption>
        get() = if (!available) emptyList() else values.map {
            TileOption(it.toString(), optionLabel(it), it == mode.value)
        }
    @Composable
    override fun observeEnabled(): State<Boolean> = rememberUpdatedState(isEnabled)

    var onChanged: (() -> Unit)? = null
    var onFailureMessage: ((String) -> Unit)? = null

    protected abstract val failureRes: Int

    protected abstract fun optionLabel(value: Int): String

    /**
     * A short reason the stored choice cannot take effect, or null. The tile then shows it in the
     * attention colour and above its options.
     */
    protected open fun readNotice(pkg: String, level: Int): String? = null

    /** Keeps a stored choice selectable when the engine is missing, so it can be seen and turned off. */
    protected open fun availableWithoutSupport(pkg: String, level: Int): Boolean = false

    protected open fun gppSupported(): Boolean = SystemProperties.getBoolean(GPP_PROPERTY, false)

    fun onGameStart(packageName: String) {
        gamePackage = packageName
        gameUserId = GameGppChoices.userId(context, packageName)
        observe()
        refresh()
    }

    fun onGameStop() {
        unobserve()
        gamePackage = null
        gameUserId = context.userId
        available = false
        mode.value = 0
        noticeState.value = null
        onChanged?.invoke()
    }

    fun refresh() {
        reload()
        onChanged?.invoke()
    }

    override fun toggle() = refresh()

    override fun toggleLastChoice(): Boolean {
        reload()
        if (!available) {
            onChanged?.invoke()
            return true
        }
        if (mode.value != 0) {
            select(0)
            return true
        }
        select(lastChoice() ?: return false)
        return true
    }

    /** The choice used last, kept for every game; null before any was used. A tile with one
     * choice besides off always returns that one. */
    private fun lastChoice(): Int? = values.singleOrNull { it != 0 }
        ?: PreferenceManager.getDefaultSharedPreferences(context).getInt("${setting}_last", 0)
            .let(::normalize).takeIf { it != 0 && it in values }

    /** A stored value from an earlier set of choices, read as the nearest current one. */
    protected open fun normalize(value: Int): Int = value

    override fun selectOption(option: TileOption) {
        select(option.id.toIntOrNull() ?: 0)
    }

    fun select(next: Int) {
        reload()
        val pkg = gamePackage ?: return
        if (!available || next !in values) return
        try {
            val currentMode = SessionOptions.mode(context) ?: SessionOptions.savedMode(context, pkg)
                ?: ModePreferences.rawMode(context, pkg) ?: ModePreferences.defaultMode(context)
            val targetMode = GppModeRules.modeForChoice(currentMode, next)
            if (targetMode != currentMode && !SessionOptions.chooseMode(context, targetMode)) {
                showFailure()
                return
            }
            if (!GameGppChoices.write(context, gameUserId, pkg, setting, next)) {
                showFailure()
                return
            }
            mode.value = next
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val remembered = GppModeRules.rememberedChoice(prefs.getInt("${setting}_last", 0), next)
            if (remembered != prefs.getInt("${setting}_last", 0)) {
                prefs.edit().putInt("${setting}_last", remembered).apply()
            }
            onChanged?.invoke()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to change $setting", e)
            showFailure()
        }
    }

    private fun observe() {
        unobserve()
        val next = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refresh()
        }
        context.contentResolver.registerContentObserver(Settings.Secure.getUriFor(setting), false,
            next, gameUserId)
        observer = next
    }

    private fun unobserve() {
        val current = observer ?: return
        observer = null
        context.contentResolver.unregisterContentObserver(current)
    }

    private fun reload() {
        available = false
        noticeState.value = null
        val pkg = gamePackage ?: return
        try {
            val gameContext = context.createContextAsUser(UserHandle.of(gameUserId), 0)
            if (gameContext.packageManager.getLaunchIntentForPackage(pkg) == null) return
            val stored = normalize(readMap(gameUserId).optInt(pkg, 0)).takeIf { it in values } ?: 0
            mode.value = stored
            noticeState.value = readNotice(pkg, stored)
            available = gppSupported() || availableWithoutSupport(pkg, stored)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read $setting", e)
        }
    }

    private fun readMap(userId: Int): JSONObject = GameGppChoices.read(context, userId, setting)

    private fun showFailure() {
        val message = context.getString(failureRes)
        onFailureMessage?.invoke(message) ?: Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "GameGppTile"
        private const val GPP_PROPERTY = "vendor.gpp.create_frc_extension"
    }
}

@Singleton
class GameFrameInterpTile @Inject constructor(
    @ApplicationContext context: Context,
    private val superResolution: GameSuperResolutionTile,
) : GameGppTile(context, "game_frame_interp", listOf(0, 1, 2)) {
    override val id = "frame_interpolation"
    override val icon = R.drawable.materialsymbols_ic_auto_awesome_motion_rounded_filled
    override val label: String get() = context.getString(R.string.tile_frame_interp)
    override val shortLabel: String? get() = context.shortLabel(R.string.tile_frame_interp_short)
    override val failureRes = R.string.frame_interp_change_failed
    override val detailNote: String get() = context.getString(R.string.frame_interp_description)
    /** Shown only while super resolution actually holds interpolation back. */
    override val detailFooter: String?
        get() = if (superResolution.isEnabled) context.getString(R.string.frame_interp_super_resolution_note) else null

    /** Super resolution caps the source frames, which holds interpolation back. */
    override val limited: Boolean get() = superResolution.isEnabled

    override fun optionLabel(value: Int): String = context.getString(when (value) {
        1 -> R.string.frame_interp_2x
        2 -> R.string.frame_interp_3x
        else -> R.string.gpp_off
    })
}

@Singleton
class GameSuperResolutionTile @Inject constructor(
    @ApplicationContext context: Context,
) : GameGppTile(context, "game_super_resolution", listOf(0, 1)) {
    override val id = "super_resolution"
    override val icon = R.drawable.materialsymbols_ic_hd_rounded_filled
    override val label: String get() = context.getString(R.string.tile_super_resolution)
    override val shortLabel: String? get() = context.shortLabel(R.string.tile_super_resolution_short)
    override val failureRes = R.string.super_resolution_change_failed
    override val detailNote: String get() = context.getString(R.string.super_resolution_description)

    override fun optionLabel(value: Int): String = context.getString(when (value) {
        0 -> R.string.gpp_off
        else -> R.string.gpp_on
    })

    override fun normalize(value: Int): Int = if (value > 0) 1 else 0

    override fun availableWithoutSupport(pkg: String, level: Int): Boolean = level > 0

    override fun readNotice(pkg: String, level: Int): String? =
        if (level > 0 && !gppSupported()) context.getString(R.string.super_resolution_unsupported)
        else null
}
