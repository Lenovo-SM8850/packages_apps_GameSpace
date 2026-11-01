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

