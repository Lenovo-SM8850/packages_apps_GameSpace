// SPDX-FileCopyrightText: 2026 miner7222
// SPDX-License-Identifier: Apache-2.0

package io.github.miner7222.gamespace

import android.content.Context
import android.provider.Settings

/** Publishes the fullscreen game session; the touch policy service decides the node values. */
object TouchPolicySession {
    private const val KEY_SESSION = "touch_policy_session"

    fun publish(context: Context, packageName: String?) {
        TouchSensitivitySettings.migrate(context)
        val resolver = context.contentResolver
        val value = packageName ?: ""
        if (Settings.Secure.getStringForUser(resolver, KEY_SESSION, context.userId) != value) {
            Settings.Secure.putStringForUser(resolver, KEY_SESSION, value, context.userId)
        }
    }
}
