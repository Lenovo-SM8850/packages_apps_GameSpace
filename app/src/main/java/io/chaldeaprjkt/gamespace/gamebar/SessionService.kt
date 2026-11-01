/*
 * Copyright (C) 2021 Chaldeaprjkt
 * Copyright (C) 2022-2024 crDroid Android Project
 * Copyright (C) 2025 AxionOS
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
package io.chaldeaprjkt.gamespace.gamebar

import io.github.miner7222.gamespace.ScreenOffSessionController

import android.annotation.SuppressLint
import android.app.GameManager
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.WorkSource
import android.content.res.Configuration
import android.database.ContentObserver
import android.provider.Settings
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import com.android.axion.platform.AxPlatformClient
import dagger.hilt.android.AndroidEntryPoint
import com.google.gson.Gson
import io.chaldeaprjkt.gamespace.R
import io.chaldeaprjkt.gamespace.data.AppSettings
import io.chaldeaprjkt.gamespace.data.GameSession
import io.chaldeaprjkt.gamespace.data.SystemSettings
import io.chaldeaprjkt.gamespace.gamebar.brightness.BrightnessInteractor
import io.chaldeaprjkt.gamespace.gamebar.fps.FpsInteractor
import io.chaldeaprjkt.gamespace.gamebar.mapper.MapperController
import io.chaldeaprjkt.gamespace.gamebar.tiles.TileRepository
import io.chaldeaprjkt.gamespace.utils.GameModeUtils
import io.chaldeaprjkt.gamespace.utils.ScreenUtils
import io.github.miner7222.gamespace.GameAspectRatioGesture
import io.github.miner7222.gamespace.GameAspectRatioTile
import io.github.miner7222.gamespace.gamebar.MediaVolumeController
import io.github.miner7222.gamespace.library.GameOption
import io.github.miner7222.gamespace.library.SessionOptions
import io.github.miner7222.gamespace.library.SidebarVisibility
import javax.inject.Inject

@AndroidEntryPoint(Service::class)
class SessionService : Hilt_SessionService() {
    @Inject lateinit var appSettings: AppSettings
    @Inject lateinit var settings: SystemSettings
    @Inject lateinit var session: GameSession
    @Inject lateinit var screenUtils: ScreenUtils
    @Inject lateinit var gameModeUtils: GameModeUtils
    @Inject lateinit var callListener: CallListener
    @Inject lateinit var danmakuService: DanmakuService
    @Inject lateinit var brightnessInteractor: BrightnessInteractor
    @Inject lateinit var fpsInteractor: FpsInteractor
    @Inject lateinit var tileRepository: TileRepository
    @Inject lateinit var mediaVolume: MediaVolumeController
    @Inject lateinit var aspectRatioTile: GameAspectRatioTile
    @Inject lateinit var gson: Gson

    private var currentPackage: String? = null
    private var sessionUserId = android.os.UserHandle.myUserId()
    private val sessionBinder = Binder()
    private var bypassChargeActive = false
    private lateinit var gameManager: GameManager
    private lateinit var sidebar: GameSidebar
    private lateinit var aspectRatioGesture: GameAspectRatioGesture
    private lateinit var screenOffSession: ScreenOffSessionController
    private lateinit var mapperController: MapperController
    private lateinit var platform: AxPlatformClient

    private val mainHandler = Handler(Looper.getMainLooper())
    private val wifiManager by lazy { applicationContext.getSystemService(WifiManager::class.java) }
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val wifiNetworks = mutableSetOf<Network>()
    private var wifiLock: WifiManager.WifiLock? = null
    private var wifiPackage: String? = null
    private var wifiMonitoring = false
    private var optionsObservation: AutoCloseable? = null

    private val wifiPreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == AppSettings.KEY_WIFI_LOW_LATENCY) updateWifiLock()
    }

    private val wifiNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (!wifiMonitoring) return
            wifiNetworks.add(network)
            updateWifiLock()
        }

        override fun onLost(network: Network) {
            if (!wifiMonitoring) return
            wifiNetworks.remove(network)
            updateWifiLock()
        }
    }

    private val wifiStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) releaseWifiLock() else updateWifiLock()
        }
    }

    private var dndEnabledByUs = false
    private var lastDndRequest: Boolean? = null
    private var dndPanelOwns = false

    private val bypassObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            session.rememberBypassChargeEnabled()
        }
    }

    private val dndReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val nm = getSystemService(NotificationManager::class.java)
            if (dndEnabledByUs && !dndPanelOwns && nm.currentInterruptionFilter !=
                NotificationManager.INTERRUPTION_FILTER_PRIORITY) {
                dndEnabledByUs = false
                session.releaseDndOwnership()
            }
        }
    }

    @SuppressLint("WrongConstant")
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SessionService created")
        SidebarVisibility.setGame(this, null)
        io.github.miner7222.gamespace.library.SessionRecovery.recoverIfIdle(this)
        contentResolver.registerContentObserver(
            Settings.System.getUriFor("bypass_charge_enabled"), false, bypassObserver)
        registerReceiver(dndReceiver, IntentFilter(
            NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED), RECEIVER_EXPORTED)

        platform = AxPlatformClient.getInstance()
        platform.init(this)

        gameManager = getSystemService(Context.GAME_SERVICE) as GameManager
        gameModeUtils.bind(gameManager)

        tileRepository.init(platform)
        mediaVolume.start()
        brightnessInteractor.onToggleAutoMode = { session.setAutoBrightness(it) }

        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val mainHandler = Handler(Looper.getMainLooper())

        mapperController = MapperController(
            context = this,
            wm = windowManager,
            handler = mainHandler,
            gson = gson,
        )

        sidebar = GameSidebar(
            context = this,
            wm = windowManager,
            handler = mainHandler,
            appSettings = appSettings,
            screenUtils = screenUtils,
            danmakuService = danmakuService,
            brightnessInteractor = brightnessInteractor,
            fpsInteractor = fpsInteractor,
            gameModeUtils = gameModeUtils,
            settings = settings,
            tileRepository = tileRepository,
            platform = platform,
            mapperController = mapperController,
        )
        sidebar.onCreate()
        aspectRatioGesture = GameAspectRatioGesture(this, windowManager, mainHandler,
            aspectRatioTile)
        sidebar.onUiBlockingChanged = { aspectRatioGesture.setUiBlocked(it) }
        screenOffSession = ScreenOffSessionController(this)
        tileRepository.onScreenOff = {
            sidebar.collapseForScreenOff()
            screenOffSession.request()
        }
        callListener.onIncomingCall = { screenOffSession.end() }

        wifiMonitoring = true
        appSettings.registerPreferenceListener(wifiPreferenceListener)
        connectivity.registerNetworkCallback(
            NetworkRequest.Builder().clearCapabilities()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            wifiNetworkCallback, mainHandler)
        registerReceiver(wifiStateReceiver, IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION).apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }, null, mainHandler, RECEIVER_EXPORTED)
        optionsObservation = appSettings.inGameOptions.observe { refreshInGameOptions() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
            if (packageName != null) {
                startGameSession(packageName)
                setGameUiVisible(intent.getBooleanExtra(EXTRA_GAME_VISIBLE, true))
                val bypassActive = intent.getBooleanExtra("bypass_charge_active", false)
                if (bypassActive && !bypassChargeActive) {
                    Toast.makeText(this, R.string.bypass_charging_on, Toast.LENGTH_SHORT).show()
                }
                bypassChargeActive = bypassActive
            } else {
                Log.e(TAG, "No package name provided, stopping")
                stopSelf()
            }
        } else if (intent?.action == ACTION_VISIBILITY) {
            if (currentPackage == null) {
                // A visibility update must never resurrect a stopped session.
                stopSelf(startId)
            } else if (currentPackage == intent.getStringExtra(EXTRA_PACKAGE_NAME)) {
                setGameUiVisible(intent.getBooleanExtra(EXTRA_GAME_VISIBLE, false))
            }
        }
        return START_NOT_STICKY
    }

    private fun setGameUiVisible(visible: Boolean) {
        sidebar.setGameVisible(visible)
        aspectRatioGesture.setGameVisible(visible)
        danmakuService.setGameVisible(visible)
        callListener.setGameVisible(visible)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        sidebar.onConfigurationChanged(newConfig)
        aspectRatioGesture.onConfigurationChanged()
    }

    private fun startGameSession(packageName: String) {
        if (currentPackage == packageName) {
            Log.d(TAG, "Session already active for $packageName")
            return
        }
        
        if (currentPackage != null) {
            stopGameSession()
        }
        
        Log.i(TAG, "Starting game session for $packageName")
        sessionUserId = userId
        currentPackage = packageName
        
        session.unregister()
        session.register(packageName)
        SidebarVisibility.setGame(this, packageName)
        
        applyGameModeConfig(packageName)
        applyAutoDnd()

        tileRepository.onGameStart(packageName)
        sidebar.onGameStart(packageName)
        screenOffSession.onGameStart(packageName)

        callListener.init()
        refreshInGameOptions()
        updateWifiLock()
    }

    private fun stopGameSession() {
        Log.i(TAG, "Stopping game session")
        SidebarVisibility.setGame(this, null)
        releaseWifiLock()

        setGameUiVisible(false)
        tileRepository.onGameStop()
        screenOffSession.onGameStop()
        sidebar.onGameLeave()
        restoreAutoDnd()
        session.unregister()
        currentPackage?.let { applyGameModeConfig(it) }
        gameModeUtils.activeGame = null
        callListener.destroy()

        currentPackage = null
        bypassChargeActive = false
    }

    private fun updateWifiLock() {
        val packageName = currentPackage
        try {
            val wifi = wifiManager
            if (!wifiMonitoring || packageName == null || !appSettings.wifiLowLatency ||
                !appSettings.wifiLowLatencySupported || wifi?.isWifiEnabled != true ||
                !getSystemService(android.os.PowerManager::class.java).isInteractive || wifiNetworks.isEmpty()) {
                releaseWifiLock()
                return
            }
            if (wifiPackage == packageName && wifiLock?.isHeld == true) return
            releaseWifiLock()
            val uid = packageManager.getPackageUid(packageName, PackageManager.PackageInfoFlags.of(0))
            val lock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, TAG)
            lock.setReferenceCounted(false)
            lock.setWorkSource(WorkSource(uid))
            wifiLock = lock
            lock.acquire()
            wifiPackage = packageName
        } catch (e: PackageManager.NameNotFoundException) {
            releaseWifiLock()
            Log.w(TAG, "No such game: $packageName", e)
        } catch (e: RuntimeException) {
            releaseWifiLock()
            Log.w(TAG, "Cannot hold the low-latency Wi-Fi lock", e)
        } finally {
            publishWifiLockState()
        }
    }

    private fun releaseWifiLock() {
        val lock = wifiLock
        wifiLock = null
        wifiPackage = null
        try {
            if (lock?.isHeld == true) lock.release()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot release the low-latency Wi-Fi lock", e)
        } finally {
            publishWifiLockState()
        }
    }

    private fun publishWifiLockState() {
        tileRepository.setWifiLowLatencyActive(
            currentPackage != null && wifiPackage == currentPackage && wifiLock?.isHeld == true)
    }

    private fun refreshInGameOptions() {
        SidebarVisibility.refresh(this)
        if (currentPackage == null) return
        session.applyOptions()
        currentPackage?.let { applyGameModeConfig(it) }
        updateWifiLock()
        applyAutoDnd()
        sidebar.refreshInGameOptions()
        callListener.refreshInGameOptions()
        danmakuService.refreshInGameOptions()
        tileRepository.refreshPlatformStates()
    }

    private fun applyAutoDnd() {
        val requested = (appSettings.inGameOptions.current(GameOption.DND) == 1)
        if (SessionOptions.hasChoice(this, GameOption.DND)) {
            if (dndPanelOwns && lastDndRequest == requested) return
            session.captureDndOwnership()
            dndPanelOwns = true
            lastDndRequest = requested
            val nm = getSystemService(NotificationManager::class.java)
            val filter = if (requested) NotificationManager.INTERRUPTION_FILTER_PRIORITY
                else NotificationManager.INTERRUPTION_FILTER_ALL
            if (nm.currentInterruptionFilter != filter) nm.setInterruptionFilter(filter)
            return
        }
        if (lastDndRequest == requested) return
        if (!requested) {
            restoreAutoDnd()
            lastDndRequest = false
            return
        }
        lastDndRequest = true
        if (dndEnabledByUs) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val currentFilter = nm.currentInterruptionFilter
        if (currentFilter == NotificationManager.INTERRUPTION_FILTER_ALL ||
            currentFilter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
            session.captureDndOwnership()
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            dndEnabledByUs = true
        }
    }

    private fun restoreAutoDnd() {
        lastDndRequest = null
        dndPanelOwns = false
        dndEnabledByUs = false
        session.restoreDnd()
    }

    private fun applyGameModeConfig(app: String) {
        GameModeUtils.withModeLock {
            val userGame = settings.userGames.firstOrNull { it.packageName == app }
            val preferred = SessionOptions.mode(this) ?: userGame?.mode ?: settings.libraryDefaultMode
            gameModeUtils.activeGame = io.chaldeaprjkt.gamespace.data.UserGame(app, preferred)
            // Always reconcile the real service state; a cached choice can outlive an external change.
            gameModeUtils.applyRuntimeModeForUser(app, preferred, sessionUserId)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "SessionService destroyed")
        SidebarVisibility.setGame(this, null)
        optionsObservation?.close()
        optionsObservation = null
        wifiMonitoring = false
        releaseWifiLock()
        appSettings.unregisterPreferenceListener(wifiPreferenceListener)
        connectivity.unregisterNetworkCallback(wifiNetworkCallback)
        unregisterReceiver(wifiStateReceiver)
        wifiNetworks.clear()
        contentResolver.unregisterContentObserver(bypassObserver)
        stopGameSession()
        aspectRatioGesture.release()
        unregisterReceiver(dndReceiver)
        brightnessInteractor.onToggleAutoMode = null
        tileRepository.onScreenOff = null
        callListener.onIncomingCall = null
        tileRepository.dispose()
        mediaVolume.dispose()
        gameModeUtils.unbind()
        danmakuService.destroy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = sessionBinder

    companion object {
        const val TAG = "SessionService"
        const val ACTION_START = "game_start"
        const val ACTION_VISIBILITY = "game_visibility"
        const val EXTRA_GAME_VISIBLE = "game_visible"
        const val EXTRA_PACKAGE_NAME = "package_name"
    }
}
