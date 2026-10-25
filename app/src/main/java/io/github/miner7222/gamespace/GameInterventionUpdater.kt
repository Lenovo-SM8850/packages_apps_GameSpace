// SPDX-License-Identifier: Apache-2.0
package io.github.miner7222.gamespace

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.HandlerThread
import android.os.RemoteException
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import io.chaldeaprjkt.gamespace.data.GameConfig
import io.chaldeaprjkt.gamespace.utils.GameModeUtils

/** Applies versioned interventions to registrations from both GameSpace and the framework. */
internal class GameInterventionUpdater(
    private val context: Context,
    private val gameModeUtils: GameModeUtils
) {
    private val handler = Handler(HandlerThread(TAG).apply { start() }.looper)
    private val update = Runnable { updateUsers() }

    fun start() {
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(GAME_LIST), false,
            object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) = scheduleUpdate()
            }, UserHandle.USER_ALL
        )
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = scheduleUpdate()
        }
        context.registerReceiverAsUser(receiver, UserHandle.ALL, IntentFilter().apply {
            addAction(Intent.ACTION_USER_STARTED)
            addAction(Intent.ACTION_USER_UNLOCKED)
            addAction(Intent.ACTION_USER_SWITCHED)
        }, null, handler)
        context.registerReceiverAsUser(receiver, UserHandle.ALL, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }, null, handler)
        handler.post(update)
    }

    private fun scheduleUpdate() {
        handler.removeCallbacks(update)
        handler.post(update)
    }

    private fun updateUsers() {
        val users = context.getSystemService(UserManager::class.java)
        var retry = false
        for (user in users.aliveUsers) {
            // GameManagerService only loads settings for started users. Unlock also retries.
            if (!users.isUserRunning(UserHandle.of(user.id))) continue
            try {
                if (!updateUser(user.id)) retry = true
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Game settings not ready for user ${user.id}", e)
                retry = true
            } catch (e: IllegalStateException) {
                Log.w(TAG, "User ${user.id} is not ready", e)
                retry = true
            } catch (e: RemoteException) {
                Log.w(TAG, "Game service unavailable for user ${user.id}", e)
                retry = true
            }
        }
        if (retry) handler.postDelayed(update, RETRY_DELAY_MS)
    }

    private fun updateUser(userId: Int): Boolean {
        val userContext = context.createContextAsUser(UserHandle.of(userId), 0)
        // Only the system-user process owns these files; namespace markers by target user.
        val prefs = context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val versionKey = "u$userId:$VERSION_KEY"
        val packagePrefix = "u$userId:$PACKAGE_PREFIX"
        val packages = Settings.System.getStringForUser(
            context.contentResolver, GAME_LIST, userId
        ).orEmpty().split(';').filter { it.isNotEmpty() }
            .map { it.substringBefore('=') }.toSet()
        val versionChanged = prefs.getInt(versionKey, 0) != CONFIG_VERSION
        val editor = prefs.edit()
        var complete = true
        for (packageName in packages) {
            val info = try {
                userContext.packageManager.getPackageInfo(packageName, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                // A stale registration will be retried when its package is installed again.
                editor.remove(packagePrefix + packageName)
                continue
            }
            // A reinstall can clear GameManager's overrides without changing the game list.
            val stamp = "${info.firstInstallTime}:${info.lastUpdateTime}"
            if (!versionChanged && prefs.getString(packagePrefix + packageName, null) == stamp) {
                continue
            }
            if (gameModeUtils.setInterventionForUser(
                    packageName, GameConfig.ModeBuilder.build(), userId)) {
                editor.putString(packagePrefix + packageName, stamp)
            } else {
                complete = false
            }
        }
        // Forget removed registrations so adding the same installed package applies defaults again.
        prefs.all.keys.filter { it.startsWith(packagePrefix) }
            .filter { it.removePrefix(packagePrefix) !in packages }
            .forEach { editor.remove(it) }
        if (complete) editor.putInt(versionKey, CONFIG_VERSION)
        return editor.commit() && complete
    }

    companion object {
        private const val TAG = "GameInterventionUpdater"
        private const val GAME_LIST = "gamespace_game_list"
        private const val PREFS = "game_interventions"
        private const val VERSION_KEY = "config_version"
        private const val CONFIG_VERSION = 1
        private const val PACKAGE_PREFIX = "package:"
        private const val RETRY_DELAY_MS = 30_000L
    }
}
