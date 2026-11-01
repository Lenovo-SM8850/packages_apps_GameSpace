// SPDX-FileCopyrightText: 2026 miner7222
// SPDX-License-Identifier: Apache-2.0

package io.github.miner7222.gamespace.library

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings

object LibraryKeys {
    const val GAMES = "gamespace_game_list"
    const val HIDE = "gamespace_hide_games"
    const val EXCEPTIONS = "gamespace_home_exceptions"
    const val HIDDEN = "gamespace_launcher_hidden_packages"
    const val HINT = "gamespace_library_hint_dismissed"
    const val SORT = "gamespace_library_sort"
}

/** Materializes the per-user launcher contract even when no activity is open. */
object LauncherVisibility {
    @Synchronized
    fun sync(context: Context, userId: Int) {
        if (userId < 0) return
        val resolver = context.contentResolver
        val enabled = Settings.Secure.getIntForUser(resolver, LibraryKeys.HIDE, 0, userId) == 1
        val exceptions = Settings.Secure.getStringForUser(resolver, LibraryKeys.EXCEPTIONS, userId)
            .orEmpty().split(',').toSet()
        val hidden = if (enabled) Settings.System.getStringForUser(resolver, LibraryKeys.GAMES, userId)
            .orEmpty().split(';').map { it.substringBefore('=') }
            .filter { it.isNotEmpty() && it != context.packageName && it !in exceptions }
            .distinct().sorted().joinToString(",") else ""
        if (Settings.Secure.getStringForUser(resolver, LibraryKeys.HIDDEN, userId).orEmpty() != hidden) {
            Settings.Secure.putStringForUser(resolver, LibraryKeys.HIDDEN, hidden, userId)
        }
    }
}

class LibraryInitializer : ContentProvider() {
    private lateinit var observer: ContentObserver

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        SidebarVisibility.setGame(ctx, null)
        SessionRecovery.recoverIfIdle(ctx)
        observer = object : ContentObserver(Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uris: Collection<Uri>, flags: Int, userId: Int) {
                LauncherVisibility.sync(ctx, userId)
            }
        }
        val resolver = ctx.contentResolver
        listOf(Settings.System.getUriFor(LibraryKeys.GAMES),
            Settings.Secure.getUriFor(LibraryKeys.HIDE),
            Settings.Secure.getUriFor(LibraryKeys.EXCEPTIONS)).forEach {
            resolver.registerContentObserver(it, false, observer, UserHandle.USER_ALL)
        }
        ctx.getSystemService(UserManager::class.java).users.forEach {
            LauncherVisibility.sync(ctx, it.id)
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?): Int = 0
}
