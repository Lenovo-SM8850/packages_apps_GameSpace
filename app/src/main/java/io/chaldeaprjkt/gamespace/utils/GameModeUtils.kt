/*
 * Copyright (C) 2021 Chaldeaprjkt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.chaldeaprjkt.gamespace.utils

import android.app.IGameManagerService
import android.app.GameManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.IDeviceIdleController
import android.os.RemoteException
import android.os.ServiceManager
import android.provider.Settings
import io.chaldeaprjkt.gamespace.R
import io.chaldeaprjkt.gamespace.data.GameConfig
import io.chaldeaprjkt.gamespace.data.SystemSettings
import io.chaldeaprjkt.gamespace.data.UserGame
import javax.inject.Inject
import kotlin.math.abs

class GameModeUtils @Inject constructor(private val context: Context) {

    private var manager: GameManager? = null
    var activeGame: UserGame? = null

    fun bind(manager: GameManager) {
        this.manager = manager
    }

    fun unbind() {
        manager = null
    }

    fun setIntervention(packageName: String, modeData: List<GameConfig>? = null) {
        setInterventionForUser(packageName, modeData, context.userId)
    }

    internal fun setInterventionForUser(
        packageName: String, modeData: List<GameConfig>?, userId: Int
    ): Boolean = withModeLock {
        val service = IGameManagerService.Stub.asInterface(
            ServiceManager.getService(Context.GAME_SERVICE)
        ) ?: return@withModeLock false
        if (modeData == null) {
            service.resetGameModeConfigOverride(packageName, userId, -1)
            if (service.getGameMode(packageName, userId) != GameManager.GAME_MODE_STANDARD) {
                service.setGameMode(packageName, GameManager.GAME_MODE_STANDARD, userId)
            }
            setAngleDriverChoice(packageName, DRIVER_CHOICE_DEFAULT)
        } else {
            val previousMode = service.getGameMode(packageName, userId)
            var updated = false
            try {
                modeData.forEach { config ->
                    val currentFactor = service.getResolutionScalingFactor(
                        packageName, config.mode, userId)
                    if (abs(currentFactor - config.downscaleFactor) > 0.0001f) {
                        service.updateResolutionScalingFactor(packageName, config.mode,
                            config.downscaleFactor, userId)
                        updated = true
                    }
                }
            } finally {
                if (updated && service.getGameMode(packageName, userId) != previousMode) {
                    service.setGameMode(packageName,
                        previousMode.takeIf { it in 1..3 } ?: GameManager.GAME_MODE_STANDARD, userId)
                }
            }
        }
        true
    }

    fun setActiveGameMode(systemSettings: SystemSettings, mode: Int) {
        val packageName = activeGame?.packageName ?: return
        activeGame = setGameModeFor(packageName, systemSettings, mode)
    }

    fun setGameModeFor(packageName: String, systemSettings: SystemSettings, mode: Int): UserGame = withModeLock {
        require(mode in 0..3)
        io.github.miner7222.gamespace.library.SessionOptions.saveMode(context, packageName, mode)
        val runtimeMode = if (io.github.miner7222.gamespace.library.SessionOptions.packageName(context) == packageName)
            io.github.miner7222.gamespace.library.SessionOptions.mode(context) ?: mode else mode
        val data = UserGame(packageName, mode)
        systemSettings.userGames = systemSettings.userGames
            .filter { x -> x.packageName != packageName }
            .toMutableList()
            .apply { add(data) }
        applyRuntimeMode(packageName, runtimeMode)

        data
    }

    fun applyRuntimeMode(packageName: String, mode: Int): Boolean =
        applyRuntimeModeForUser(packageName, mode, context.userId)

    fun applyRuntimeModeForUser(packageName: String, mode: Int, userId: Int): Boolean = withModeLock {
        require(mode in 0..3)
        if (!setInterventionForUser(packageName,
                if (mode == 0) null else GameConfig.ModeBuilder.build(), userId)) return@withModeLock false
        val service = IGameManagerService.Stub.asInterface(
            ServiceManager.getService(Context.GAME_SERVICE)
        ) ?: return@withModeLock false
        val systemMode = if (mode == 0) GameManager.GAME_MODE_STANDARD else mode
        // GameManager is bound to its Context's user, never the foreground user.
        val localManager = manager.takeIf { userId == context.userId }
        if (localManager != null) {
            if (localManager.getGameMode(packageName) != systemMode) {
                localManager.setGameMode(packageName, systemMode)
            }
        } else if (service.getGameMode(packageName, userId) != systemMode) {
            service.setGameMode(packageName, systemMode, userId)
        }
        true
    }

    fun setupBatteryMode(enable: Boolean) {
        val svc = IDeviceIdleController.Stub.asInterface(
            ServiceManager.getService(Context.DEVICE_IDLE_CONTROLLER)
        )
        try {
            val isListed = svc?.isPowerSaveWhitelistApp(context.packageName) ?: false
            if (enable && !isListed) {
                svc?.addPowerSaveWhitelistApp(context.packageName)
            } else if (!enable && isListed) {
                svc?.removePowerSaveWhitelistApp(context.packageName)
            }
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
    }


    fun findAnglePackage(): ActivityInfo? {
        val intent = Intent(ACTION_ANGLE_FOR_ANDROID)
        val flags = PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_SYSTEM_ONLY.toLong())
        val info = context.packageManager.queryIntentActivities(intent, flags)
        return info.firstOrNull()?.activityInfo
    }

    fun getAngleDriverChoice(packageName: String): String {
        val resolver = context.contentResolver
        val pkgsStr = Settings.Global.getString(resolver, DRIVER_SELECTION_PACKAGES)
            ?: return DRIVER_CHOICE_DEFAULT
        val valsStr = Settings.Global.getString(resolver, DRIVER_SELECTION_VALUES)
            ?: return DRIVER_CHOICE_DEFAULT
        val pkgs = pkgsStr.split(",")
        val vals = valsStr.split(",")
        val index = pkgs.indexOf(packageName)
        if (index < 0 || index >= vals.size) return DRIVER_CHOICE_DEFAULT
        return vals[index]
    }

    fun setAngleDriverChoice(packageName: String, choice: String) {
        val resolver = context.contentResolver
        val pkgsStr = Settings.Global.getString(resolver, DRIVER_SELECTION_PACKAGES) ?: ""
        val valsStr = Settings.Global.getString(resolver, DRIVER_SELECTION_VALUES) ?: ""
        val pkgs = if (pkgsStr.isEmpty()) mutableListOf()
            else pkgsStr.split(",").toMutableList()
        val vals = if (valsStr.isEmpty()) mutableListOf()
            else valsStr.split(",").toMutableList()

        val index = pkgs.indexOf(packageName)
        if (choice == DRIVER_CHOICE_DEFAULT) {
            if (index >= 0) {
                pkgs.removeAt(index)
                vals.removeAt(index)
            }
        } else {
            if (index >= 0) {
                vals[index] = choice
            } else {
                pkgs.add(packageName)
                vals.add(choice)
            }
        }

        Settings.Global.putString(
            resolver, DRIVER_SELECTION_PACKAGES, pkgs.joinToString(",")
        )
        Settings.Global.putString(
            resolver, DRIVER_SELECTION_VALUES, vals.joinToString(",")
        )
    }

    fun isVulkanSupported(): Boolean =
        context.packageManager.hasSystemFeature(
            PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, VULKAN_1_0
        )

    companion object {
        private val modeLock = Any()
        internal fun <T> withModeLock(action: () -> T): T = synchronized(modeLock, action)

        const val defaultPreferredMode = GameManager.GAME_MODE_STANDARD
        const val ACTION_ANGLE_FOR_ANDROID = "android.app.action.ANGLE_FOR_ANDROID"
        private const val DRIVER_SELECTION_PACKAGES = "angle_gl_driver_selection_pkgs"
        private const val DRIVER_SELECTION_VALUES = "angle_gl_driver_selection_values"
        const val DRIVER_CHOICE_DEFAULT = "default"
        const val DRIVER_CHOICE_ANGLE = "angle"
        const val DRIVER_CHOICE_NATIVE = "native"
        private const val VULKAN_1_0 = 0x00400000

        fun Context.describeGameMode(mode: Int) =
            resources.getStringArray(R.array.game_mode_names)[mode] ?: resources.getString(R.string.game_mode_unsupported)
    }
}
