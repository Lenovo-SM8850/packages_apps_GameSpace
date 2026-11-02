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
import io.github.miner7222.gamespace.GameScreenSampler
import java.io.FileDescriptor
import java.io.PrintWriter

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityTaskManager
import android.app.GameManager
import android.app.NotificationManager
import android.app.Service
import android.app.TaskStackListener
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
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.SystemClock
import android.os.Looper
import android.util.Log
import android.view.WindowManager
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
import io.github.miner7222.gamespace.gamebar.SessionStartStatus
import io.github.miner7222.gamespace.gamebar.SessionStatusChip
import io.github.miner7222.gamespace.gamebar.SessionStatusOverlay
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
    @Inject lateinit var screenSampler: GameScreenSampler
    @Inject lateinit var brightnessInteractor: BrightnessInteractor
    @Inject lateinit var fpsInteractor: FpsInteractor
    @Inject lateinit var tileRepository: TileRepository
    @Inject lateinit var mediaVolume: MediaVolumeController
    @Inject lateinit var aspectRatioTile: GameAspectRatioTile
    @Inject lateinit var gson: Gson

    private var currentPackage: String? = null
    private var suspended = false
    private enum class SuspensionReason { FRAMEWORK, CALL, SCREEN }
    private val suspensionReasons = mutableSetOf<SuspensionReason>()
    private var frameworkSessionActive = false
    private var frameworkGameVisible = false
    private var deferredGameStart = false
    private var gameProcessId = 0
    private var gameTaskIds = emptySet<Int>()
    private val removingTaskIds = mutableSetOf<Int>()
    private var taskListenerRegistered = false
    private val taskManager by lazy { ActivityTaskManager.getService() }
    private val taskListener = object : TaskStackListener() {
        override fun onTaskRemovalStarted(taskInfo: ActivityManager.RunningTaskInfo) {
            mainHandler.post {
                if (pendingStart?.getIntExtra(EXTRA_GAME_TASK_ID, -1) == taskInfo.taskId) clearPendingStart()
                if (taskInfo.userId == sessionUserId && currentPackage != null &&
                    (taskInfo.baseActivity?.packageName == currentPackage ||
                        taskInfo.topActivity?.packageName == currentPackage)) {
                    removingTaskIds.add(taskInfo.taskId)
                    onGameTaskRemoved(taskInfo.taskId)
                }
            }
        }

        override fun onTaskRemoved(taskId: Int) {
            mainHandler.post {
                if (pendingStart?.getIntExtra(EXTRA_GAME_TASK_ID, -1) == taskId) clearPendingStart()
                onGameTaskRemoved(taskId)
                removingTaskIds.remove(taskId)
            }
        }
    }
    private var sessionUserId = android.os.UserHandle.myUserId()
    private val sessionBinder = Binder()
    private var bypassChargeActive = false
    private lateinit var gameManager: GameManager
    private lateinit var startStatus: SessionStatusOverlay
    private lateinit var sidebar: GameSidebar
    private lateinit var aspectRatioGesture: GameAspectRatioGesture
    private lateinit var screenOffSession: ScreenOffSessionController
    private lateinit var mapperController: MapperController
    private lateinit var platform: AxPlatformClient

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingStart: Intent? = null
    private var pendingStartDeadline = 0L
    private val retryStart = Runnable {
        val pending = pendingStart
        if (pending != null) onStartCommand(pending, 0, 0)
    }

    private fun matchesPending(intent: Intent): Boolean {
        val pending = pendingStart ?: return false
        fun sameInt(key: String, unknown: Int): Boolean {
            val first = pending.getIntExtra(key, unknown)
            val second = intent.getIntExtra(key, unknown)
            return first == unknown || second == unknown || first == second
        }
        return pending.getStringExtra(EXTRA_PACKAGE_NAME) == intent.getStringExtra(EXTRA_PACKAGE_NAME) &&
            sameInt(EXTRA_GAME_TASK_ID, -1) && sameInt(EXTRA_GAME_PROCESS_ID, 0)
    }

    private fun clearPendingStart() {
        mainHandler.removeCallbacks(retryStart)
        pendingStart = null
        pendingStartDeadline = 0L
    }

    private fun deferStart(intent: Intent) {
        if (!matchesPending(intent)) pendingStartDeadline = SystemClock.uptimeMillis() + 5000L
        if (SystemClock.uptimeMillis() >= pendingStartDeadline) {
            clearPendingStart()
            if (currentPackage == null) stopSelf()
            return
        }
        pendingStart = Intent(intent)
        mainHandler.removeCallbacks(retryStart)
        mainHandler.postDelayed(retryStart, 250L)
    }

    private val screenOffPanelCollapse = Runnable {
        if (currentPackage != null) sidebar.collapseForScreenOff()
    }
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
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    suspensionReasons.add(SuspensionReason.SCREEN)
                    suspendGameSession()
                }
                Intent.ACTION_SCREEN_ON -> {
                    suspensionReasons.remove(SuspensionReason.SCREEN)
                    resumeGameSession()
                    updateWifiLock()
                }
                Intent.ACTION_USER_SWITCHED, Intent.ACTION_USER_BACKGROUND -> {
                    clearPendingStart()
                    stopGameSession()
                }
                Intent.ACTION_BATTERY_CHANGED, AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION -> publishSessionStatus()
                else -> updateWifiLock()
            }
        }
    }

    private var dndEnabledByUs = false
    private var lastDndRequest: Boolean? = null
    private var dndPanelOwns = false

    private val bypassObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            session.rememberBypassChargeEnabled()
            publishSessionStatus()
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
            publishSessionStatus()
        }
    }

    private val gestureLockObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            // Suspension clears the request temporarily; retain the choice for resume.
            if (currentPackage == null || suspended) return
            val locked = settings.gestureLock
            if (locked != (appSettings.inGameOptions.current(GameOption.GESTURES) == 1)) {
                session.setOption(GameOption.GESTURES, if (locked) 1 else 0)
                sidebar.setGestureLock(locked)
            }
        }
    }

    private val statusObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) { publishSessionStatus() }
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
        brightnessInteractor.onSetBrightness = { percent ->
            if (currentPackage == null || suspended) false else {
                session.capturePanelBrightness()
                SessionOptions.chooseBrightness(this, percent)
                true
            }
        }

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
            mediaVolume = mediaVolume,
        )
        sidebar.onCreate()
        startStatus = SessionStatusOverlay(this, windowManager) { sidebar.openDashboard() }
        aspectRatioGesture = GameAspectRatioGesture(this, windowManager, mainHandler,
            aspectRatioTile)
        sidebar.onUiBlockingChanged = {
            aspectRatioGesture.setUiBlocked(it)
            if (it) startStatus.cancel()
        }
        screenOffSession = ScreenOffSessionController(this)
        screenOffSession.onNotice = { notice ->
            tileRepository.reportActionMessage("screen_off", notice)
            mainHandler.removeCallbacks(screenOffPanelCollapse)
            // Let the panel announce the accepted request before the one-second screen-off delay.
            mainHandler.postDelayed(screenOffPanelCollapse, 800)
        }
        tileRepository.onScreenOff = {
            if (!suspended && frameworkSessionActive && frameworkGameVisible) screenOffSession.request()
        }
        callListener.onIncomingCall = { screenOffSession.end() }
        callListener.onCallActiveChanged = { active ->
            if (active) {
                suspensionReasons.add(SuspensionReason.CALL)
                suspendGameSession()
            } else {
                suspensionReasons.remove(SuspensionReason.CALL)
                resumeGameSession()
            }
        }

        wifiMonitoring = true
        appSettings.registerPreferenceListener(wifiPreferenceListener)
        connectivity.registerNetworkCallback(
            NetworkRequest.Builder().clearCapabilities()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            wifiNetworkCallback, mainHandler)
        registerReceiver(wifiStateReceiver, IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION).apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_SWITCHED)
            addAction(Intent.ACTION_USER_BACKGROUND)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION)
        }, null, mainHandler, RECEIVER_EXPORTED)
        listOf(SidebarVisibility.HIGH_TOUCH, SidebarVisibility.HIDDEN, "ax_gaming_gesture_lock").forEach { key ->
            contentResolver.registerContentObserver(Settings.Secure.getUriFor(key), false, statusObserver, userId)
        }
        contentResolver.registerContentObserver(Settings.Secure.getUriFor("ax_gaming_gesture_lock"),
            false, gestureLockObserver, userId)
        optionsObservation = appSettings.inGameOptions.observe { refreshInGameOptions() }
        try {
            taskManager.registerTaskStackListener(taskListener)
            taskListenerRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Unable to observe game task removal", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && matchesPending(intent)) {
            if (intent.action == ACTION_END || intent.action == ACTION_SUSPEND) clearPendingStart()
            else if (intent.action == ACTION_VISIBILITY) {
                pendingStart?.putExtra(EXTRA_GAME_VISIBLE, intent.getBooleanExtra(EXTRA_GAME_VISIBLE, false))
                if (currentPackage == null) return START_NOT_STICKY
            }
        }
        // Framework keeps the binding across suspend: visibility hides UI only, suspend
        // releases effects, resume reacquires without a launch pill, end discards choices.
        // Task/process extras reject stale commands; the service's Context selects the user.
        if (intent != null && (intent.action == ACTION_SUSPEND || intent.action == ACTION_END)) {
            if (matchesSession(intent)) {
                if (intent.action == ACTION_SUSPEND) {
                    frameworkSessionActive = false
                    suspensionReasons.add(SuspensionReason.FRAMEWORK)
                    suspendGameSession()
                } else stopGameSession()
            }
        } else if (intent?.action == ACTION_GESTURE_LOCK_OFF) {
            if (matchesSession(intent)) {
                session.setOption(GameOption.GESTURES, 0)
                sidebar.setGestureLock(false)
            }
        } else if (intent?.action == ACTION_RESUME) {
            val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
            if (packageName != null) {
                if (matchesSession(intent)) {
                    frameworkSessionActive = true
                    suspensionReasons.remove(SuspensionReason.FRAMEWORK)
                    if (getSystemService(android.os.PowerManager::class.java).isInteractive) {
                        suspensionReasons.remove(SuspensionReason.SCREEN)
                    }
                } else if (currentPackage == null) {
                    if (!startGameSession(packageName, intent.getIntExtra(EXTRA_GAME_TASK_ID, -1),
                            intent.getIntExtra(EXTRA_GAME_PROCESS_ID, 0), announce = false,
                            visible = intent.getBooleanExtra(EXTRA_GAME_VISIBLE, true))) {
                        deferStart(intent)
                        return START_NOT_STICKY
                    }
                    clearPendingStart()
                } else return START_NOT_STICKY
                setGameUiVisible(intent.getBooleanExtra(EXTRA_GAME_VISIBLE, true))
            }
        } else if (intent?.action == ACTION_START) {
            val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
            if (packageName != null) {
                if (!startGameSession(packageName, intent.getIntExtra(EXTRA_GAME_TASK_ID, -1),
                        intent.getIntExtra(EXTRA_GAME_PROCESS_ID, 0),
                        visible = intent.getBooleanExtra(EXTRA_GAME_VISIBLE, true))) {
                    deferStart(intent)
                    return START_NOT_STICKY
                }
                clearPendingStart()
                // Framework acknowledgement reuses ACTION_START; it updates, never rearms, the pill.
                bypassChargeActive = intent.getBooleanExtra("bypass_charge_active", false)
                publishSessionStatus()
                setGameUiVisible(intent.getBooleanExtra(EXTRA_GAME_VISIBLE, true))
            } else {
                Log.e(TAG, "No package name provided, stopping")
                stopSelf()
            }
        } else if (intent?.action == ACTION_VISIBILITY) {
            if (currentPackage == null) {
                // A visibility update must never resurrect a stopped session.
                stopSelf(startId)
            } else if (matchesSession(intent)) {
                setGameUiVisible(intent.getBooleanExtra(EXTRA_GAME_VISIBLE, false))
            }
        }
        return START_NOT_STICKY
    }

    private fun matchesSession(intent: Intent): Boolean =
        currentPackage != null && currentPackage == intent.getStringExtra(EXTRA_PACKAGE_NAME) &&
            (intent.getIntExtra(EXTRA_GAME_TASK_ID, -1).let { it == -1 || it in gameTaskIds }) &&
            (intent.getIntExtra(EXTRA_GAME_PROCESS_ID, 0).let { it == 0 || it == gameProcessId })

    private fun setGameUiVisible(visible: Boolean) {
        frameworkGameVisible = visible
        if (visible) resumeGameSession()
        updateGameUiVisibility()
    }

    private fun updateGameUiVisibility() {
        val show = frameworkGameVisible && currentPackage != null && !suspended
        sidebar.setGameVisible(show)
        aspectRatioGesture.setGameVisible(show)
        danmakuService.setGameVisible(show)
        callListener.setGameVisible(show)
        publishSessionStatus()
        startStatus.setGameVisible(show)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        sidebar.onConfigurationChanged(newConfig)
        aspectRatioGesture.onConfigurationChanged()
        startStatus.onConfigurationChanged()
    }

    private fun gameTasks(packageName: String): Set<Int>? = try {
        ActivityTaskManager.getInstance().getTasks(Int.MAX_VALUE).filter {
            it.taskId !in removingTaskIds && it.userId == userId &&
                (it.baseActivity?.packageName == packageName ||
                    it.topActivity?.packageName == packageName)
        }.map { it.taskId }.toSet()
    } catch (e: Exception) {
        Log.w(TAG, "Unable to query game tasks", e)
        null
    }

    private fun onGameTaskRemoved(taskId: Int) {
        if (currentPackage == null || taskId !in gameTaskIds) return
        gameTaskIds = gameTaskIds - taskId
        if (gameTaskIds.isEmpty()) {
            // End choices and restore owned settings even while the framework still binds us.
            stopGameSession()
        }
    }

    private fun startGameSession(packageName: String, taskId: Int = -1,
        processId: Int = 0, announce: Boolean = true, visible: Boolean = true): Boolean {
        val allTasks = gameTasks(packageName)
        val tasks = if (taskId == -1) allTasks else allTasks?.filter { it == taskId }?.toSet()
        // A delayed framework acknowledgement must not restart a removed game's session.
        if (tasks != null && tasks.isEmpty()) return false
        if (currentPackage == packageName && (processId == 0 || processId == gameProcessId) &&
            (taskId == -1 || taskId in gameTaskIds) && (tasks == null || gameTaskIds.isEmpty() ||
                tasks.any { it in gameTaskIds })) {
            if (tasks != null) gameTaskIds = tasks
            Log.d(TAG, "Session already active for $packageName")
            return true
        }
        
        if (currentPackage != null) {
            stopGameSession()
        }
        
        Log.i(TAG, "Starting game session for $packageName")
        sessionUserId = userId
        currentPackage = packageName
        suspended = true
        frameworkSessionActive = true
        frameworkGameVisible = visible
        suspensionReasons.clear()
        callListener.init(active = false)
        if (callListener.isCallActive) suspensionReasons.add(SuspensionReason.CALL)
        if (!getSystemService(android.os.PowerManager::class.java).isInteractive) {
            suspensionReasons.add(SuspensionReason.SCREEN)
        }
        gameProcessId = processId
        gameTaskIds = tasks ?: if (taskId != -1) setOf(taskId) else emptySet()
        bypassChargeActive = false
        session.unregister()
        session.register(packageName, active = false)
        deferredGameStart = true
        sidebar.onGameStart(packageName, active = false)
        screenOffSession.onGameStart(packageName)
        resumeGameSession()
        // A session first encountered during a call also resumes silently.
        if (announce && !suspended) startStatus.beginSession(appSettings.showStatusAtGameStart)
        publishSessionStatus()
        return true
    }

    private fun suspendGameSession() {
        if (currentPackage == null || suspended) return
        suspended = true
        startStatus.cancel()
        mainHandler.removeCallbacks(screenOffPanelCollapse)
        updateGameUiVisibility()
        screenSampler.stop()
        screenOffSession.end()
        SidebarVisibility.setGame(this, null)
        releaseWifiLock()
        sidebar.setSessionActive(false)
        callListener.setSessionActive(false)
        danmakuService.setSessionActive(false)
        restoreAutoDnd()
        session.suspend()
    }

    private fun resumeGameSession() {
        val packageName = currentPackage ?: return
        if (!suspended || !frameworkSessionActive || !frameworkGameVisible ||
            suspensionReasons.isNotEmpty() || callListener.isCallActive ||
            !getSystemService(android.os.PowerManager::class.java).isInteractive) return
        suspended = false
        session.resume()
        applyGameModeConfig(packageName)
        screenSampler.start(packageName)
        if (deferredGameStart) {
            deferredGameStart = false
            tileRepository.onGameStart(packageName)
        }
        sidebar.setSessionActive(true)
        SidebarVisibility.setGame(this, packageName)
        danmakuService.setSessionActive(true)
        callListener.setSessionActive(true)
        brightnessInteractor.refresh()
        SessionOptions.brightness(this)?.let { brightnessInteractor.restoreSessionBrightness(it) }
        refreshInGameOptions()
        updateGameUiVisibility()
    }

    private fun stopGameSession() {
        frameworkSessionActive = false
        frameworkGameVisible = false
        suspensionReasons.clear()
        deferredGameStart = false
        startStatus.cancel()
        mainHandler.removeCallbacks(screenOffPanelCollapse)
        Log.i(TAG, "Stopping game session")
        SidebarVisibility.setGame(this, null)
        releaseWifiLock()

        setGameUiVisible(false)
        screenSampler.stop()
        tileRepository.onGameStop()
        screenOffSession.onGameStop()
        sidebar.onGameLeave()
        restoreAutoDnd()
        session.unregister()
        gameModeUtils.activeGame = null
        callListener.destroy()
        danmakuService.setSessionActive(false)

        currentPackage = null
        suspended = false
        gameProcessId = 0
        gameTaskIds = emptySet()
        bypassChargeActive = false
    }

    private fun updateWifiLock() {
        val packageName = currentPackage
        try {
            val wifi = wifiManager
            if (!wifiMonitoring || suspended || packageName == null || !appSettings.wifiLowLatency ||
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
        publishSessionStatus()
    }

    private fun refreshInGameOptions() {
        SidebarVisibility.refresh(this)
        if (currentPackage == null || suspended) return
        session.applyOptions()
        currentPackage?.let { applyGameModeConfig(it) }
        updateWifiLock()
        applyAutoDnd()
        sidebar.refreshInGameOptions()
        callListener.refreshInGameOptions()
        danmakuService.refreshInGameOptions()
        tileRepository.refreshPlatformStates()
        publishSessionStatus()
    }

    private fun publishSessionStatus() {
        if (currentPackage == null || !::startStatus.isInitialized) return
        if (!appSettings.showStatusAtGameStart) {
            startStatus.cancel()
            return
        }
        val options = appSettings.inGameOptions
        fun secureOn(key: String) = Settings.Secure.getIntForUser(contentResolver, key, 0, sessionUserId) == 1
        val plugged = (registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val filter = getSystemService(NotificationManager::class.java).currentInterruptionFilter
        val ringer = getSystemService(AudioManager::class.java).ringerModeInternal
        val requestedRinger = options.current(GameOption.RINGER)
        val chips = buildList {
            if (options.current(GameOption.DND) == 1 && filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
                filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN) add(SessionStatusChip.DND)
            // An enabled preference alone does not confirm framework charging control.
            if (bypassChargeActive && settings.bypassChargeEnabled) {
                add(if (plugged) SessionStatusChip.BYPASS_CHARGING else SessionStatusChip.BYPASS_READY)
            }
            if (secureOn(SidebarVisibility.HIGH_TOUCH)) add(SessionStatusChip.TOUCH)
            if (tileRepository.wifiLowLatencyActive.value) add(SessionStatusChip.WIFI)
            if (secureOn(SidebarVisibility.HIDDEN)) add(SessionStatusChip.SIDEBAR_HIDDEN)
            if (secureOn("ax_gaming_gesture_lock")) add(SessionStatusChip.GESTURES)
            // Follow system (3) is neutral; explicit choices count only after they are applied.
            if (requestedRinger != 3 && requestedRinger == ringer) {
                when (ringer) {
                    AudioManager.RINGER_MODE_NORMAL -> add(SessionStatusChip.RINGER_SOUND)
                    AudioManager.RINGER_MODE_VIBRATE -> add(SessionStatusChip.RINGER_VIBRATE)
                    AudioManager.RINGER_MODE_SILENT -> add(SessionStatusChip.RINGER_MUTE)
                }
            }
        }
        startStatus.update(SessionStartStatus(gameModeUtils.activeGame?.mode ?: GameManager.GAME_MODE_STANDARD,
            chips, tileRepository.startupMemoryCleanup.value?.ok == true))
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
            io.github.miner7222.gamespace.library.ModePreferences.publish(this, app, preferred)
            gameModeUtils.activeGame = io.chaldeaprjkt.gamespace.data.UserGame(app, preferred)
            // Always reconcile the real service state; a cached choice can outlive an external change.
            gameModeUtils.applyRuntimeModeForUser(app, preferred, sessionUserId)
        }
    }

    override fun onDestroy() {
        clearPendingStart()
        Log.d(TAG, "SessionService destroyed")
        if (taskListenerRegistered) {
            runCatching { taskManager.unregisterTaskStackListener(taskListener) }
            taskListenerRegistered = false
        }
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
        contentResolver.unregisterContentObserver(statusObserver)
        contentResolver.unregisterContentObserver(gestureLockObserver)
        stopGameSession()
        aspectRatioGesture.release()
        unregisterReceiver(dndReceiver)
        brightnessInteractor.onToggleAutoMode = null
        brightnessInteractor.onSetBrightness = null
        tileRepository.onScreenOff = null
        screenOffSession.onNotice = null
        callListener.onIncomingCall = null
        callListener.onCallActiveChanged = null
        tileRepository.dispose()
        mediaVolume.dispose()
        gameModeUtils.unbind()
        danmakuService.destroy()
        screenSampler.destroy()
        super.onDestroy()
    }

    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>) {
        screenSampler.dump(writer)
    }

    override fun onBind(intent: Intent?): IBinder {
        // A surviving framework binding can recreate this START_NOT_STICKY service
        // without redelivering its start command. The binding carries the same task
        // identity; startGameSession still rejects a task that has already gone away.
        if (intent?.action == ACTION_START && currentPackage == null && pendingStart == null) {
            onStartCommand(Intent(intent), 0, 0)
        }
        return sessionBinder
    }

    companion object {
        const val TAG = "SessionService"
        const val ACTION_START = "game_start"
        const val ACTION_SUSPEND = "game_suspend"
        const val ACTION_RESUME = "game_resume"
        const val ACTION_END = "game_end"
        const val ACTION_GESTURE_LOCK_OFF = "game_gesture_lock_off"
        const val ACTION_VISIBILITY = "game_visibility"
        const val EXTRA_GAME_TASK_ID = "game_task_id"
        const val EXTRA_GAME_PROCESS_ID = "game_process_id"
        const val EXTRA_GAME_VISIBLE = "game_visible"
        const val EXTRA_PACKAGE_NAME = "package_name"
    }
}
