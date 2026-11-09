// SPDX-FileCopyrightText: 2026 miner7222
// SPDX-License-Identifier: Apache-2.0

package io.github.miner7222.gamespace.library

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/** Publishes per-user game requests from live sessions, with shared startup and shutdown cleanup. */
object SidebarVisibility {
    const val PACKAGE = "com.libremobileos.sidebar"
    const val HIDDEN = "gamespace_sidebar_hidden"
    private var activePackage: String? = null

    fun isAvailable(context: Context): Boolean = try {
        context.packageManager.getApplicationInfo(PACKAGE,
            PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS.toLong())).enabled
    } catch (_: PackageManager.NameNotFoundException) { false }

    fun setGame(context: Context, packageName: String?) {
        activePackage = packageName
        refresh(context)
    }

    fun refresh(context: Context) {
        if (activePackage == null) {
            publish(context, HIDDEN, false)
            return
        }
        val options = InGameOptions(context)
        publish(context, HIDDEN, activePackage != null && isAvailable(context) &&
            options.current(GameOption.SIDEBAR) == 1)
    }

    private fun publish(context: Context, key: String, enabled: Boolean) {
        val requested = if (enabled) 1 else 0
        val resolver = context.contentResolver
        if (Settings.Secure.getIntForUser(resolver, key, 0, context.userId) != requested) {
            Settings.Secure.putIntForUser(resolver, key, requested, context.userId)
        }
    }

}

class SidebarStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // A cold process has no live session, including after a crash, reboot or update.
        SidebarVisibility.refresh(context)
        SessionRecovery.recoverIfIdle(context)
    }
}
