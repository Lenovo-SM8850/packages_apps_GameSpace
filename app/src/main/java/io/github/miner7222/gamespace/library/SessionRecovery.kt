// SPDX-FileCopyrightText: 2026 miner7222
// SPDX-License-Identifier: Apache-2.0

package io.github.miner7222.gamespace.library

import android.content.Context
import android.os.UserManager
import android.util.Log
import com.google.gson.Gson
import io.chaldeaprjkt.gamespace.data.AppSettings
import io.chaldeaprjkt.gamespace.data.GameSession
import io.chaldeaprjkt.gamespace.data.SystemSettings
import io.chaldeaprjkt.gamespace.utils.GameModeUtils
import io.github.miner7222.gamespace.TouchPolicySession

/** Uses normal teardown for a dead session, once its user's credential storage is available. */
object SessionRecovery {
    fun recoverIfIdle(context: Context) {
        if (SessionOptions.packageName(context) != null ||
            !context.getSystemService(UserManager::class.java).isUserUnlocked) return
        TouchPolicySession.publish(context, null)
        try {
            val modes = GameModeUtils(context)
            GameSession(context, AppSettings(context), SystemSettings(context, modes), Gson()).unregister()
        } catch (e: android.os.RemoteException) {
            Log.w("SessionRecovery", "Game service unavailable during recovery", e)
        } catch (e: RuntimeException) {
            // Keep the recovery journal for the next startup or unlock.
            Log.w("SessionRecovery", "Session recovery deferred", e)
        }
    }
}
