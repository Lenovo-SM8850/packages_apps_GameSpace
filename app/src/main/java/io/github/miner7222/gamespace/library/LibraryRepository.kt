// SPDX-FileCopyrightText: 2026 miner7222
// SPDX-License-Identifier: Apache-2.0

package io.github.miner7222.gamespace.library

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.UserHandle
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import io.chaldeaprjkt.gamespace.data.GameConfig
import io.chaldeaprjkt.gamespace.data.SystemSettings
import io.chaldeaprjkt.gamespace.data.UserGame
import io.chaldeaprjkt.gamespace.utils.GameModeUtils
import io.github.miner7222.gamespace.GameSplitAspectRatio
import javax.inject.Inject

/** Library registration mirrors the legacy preference rules. */
class LibraryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SystemSettings,
    private val modes: GameModeUtils,
) {
    private fun readDenied() = Settings.System.getStringForUser(
        context.contentResolver, DENIED_LIST, UserHandle.USER_CURRENT
    ).orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()

    private fun writeDenied(packages: Set<String>) {
        Settings.System.putStringForUser(context.contentResolver, DENIED_LIST,
            packages.joinToString(";"), UserHandle.USER_CURRENT)
    }

    fun register(packageName: String) {
        val denied = readDenied()
        if (denied.remove(packageName)) writeDenied(denied)
        val games = settings.userGames.toMutableList()
        if (games.none { it.packageName == packageName }) games.add(UserGame(packageName, settings.libraryDefaultMode))
        settings.userGames = games
        modes.applyRuntimeMode(packageName,
            games.first { it.packageName == packageName }.mode)
        LauncherVisibility.sync(context, context.userId)
    }

    fun unregister(packageName: String): RemovedGame? {
        val game = settings.userGames.firstOrNull { it.packageName == packageName } ?: UserGame(packageName)
        val denied = readDenied()
        val removed = RemovedGame(game, GameSplitAspectRatio.isEnabled(context.contentResolver, packageName),
            packageName in settings.libraryHomeExceptions, packageName in denied)
        val isGame = try {
            context.packageManager.getApplicationInfo(packageName,
                PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
                .category == ApplicationInfo.CATEGORY_GAME
        } catch (_: PackageManager.NameNotFoundException) { false }
        if (isGame && denied.add(packageName)) writeDenied(denied)
        GameSplitAspectRatio.setEnabled(context.contentResolver, packageName, false)
        settings.userGames = settings.userGames.filter { it.packageName != packageName }
        modes.setIntervention(packageName, null)
        settings.libraryHomeExceptions = settings.libraryHomeExceptions - packageName
        LauncherVisibility.sync(context, context.userId)
        return removed
    }

    fun restore(removed: RemovedGame) {
        val pkg = removed.game.packageName
        if (removed.showOnHome) settings.libraryHomeExceptions = settings.libraryHomeExceptions + pkg
        register(pkg)
        modes.setGameModeFor(pkg, settings, removed.game.mode)
        GameSplitAspectRatio.setEnabled(context.contentResolver, pkg, removed.keepAspectRatio)
        if (removed.wasDenied) writeDenied(readDenied().apply { add(pkg) })
        LauncherVisibility.sync(context, context.userId)
    }

    fun launch(packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try { context.startActivity(intent) } catch (_: Exception) { }
    }

    data class RemovedGame(val game: UserGame, val keepAspectRatio: Boolean,
        val showOnHome: Boolean, val wasDenied: Boolean)

    companion object { private const val DENIED_LIST = "gamespace_denied_list" }
}
