/*
 * Copyright (C) 2025-2026 AxionOS
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
package io.chaldeaprjkt.gamespace.gamebar.tiles

import android.Manifest
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.hardware.display.ColorDisplayManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemProperties
import android.os.UserHandle
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.preference.PreferenceManager
import com.android.axion.platform.AxPlatformClient
import com.android.internal.util.ScreenshotHelper
import io.chaldeaprjkt.gamespace.R
import io.github.miner7222.gamespace.GameAspectRatioTile
import io.github.miner7222.gamespace.GameFrameInterpTile
import io.github.miner7222.gamespace.GameSuperResolutionTile
import io.github.miner7222.gamespace.gamebar.RecordingPhase
import io.github.miner7222.gamespace.gamebar.RecordingStateController
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import io.github.miner7222.gamespace.TouchSensitivitySettings
import io.github.miner7222.gamespace.gamebar.TouchSensitivityTile
import io.github.miner7222.gamespace.library.GameOption
import io.github.miner7222.gamespace.library.SessionOptions
import io.github.miner7222.gamespace.shortLabel
import io.chaldeaprjkt.gamespace.data.AppSettings
import io.chaldeaprjkt.gamespace.data.GameSession
import io.chaldeaprjkt.gamespace.data.SystemSettings
import javax.inject.Inject
import javax.inject.Singleton

/** One row of a tile's picker; [id] is only meaningful to the tile that produced it. */
data class TileOption(val id: String, val label: String, val selected: Boolean)

enum class TileKind { TOGGLE, ACTION, CYCLE, DIALOG }

enum class TileCategory(val labelRes: Int) {
    GAME_TOOLS(R.string.tile_category_game_tools),
    SOUND_NOTIFICATIONS(R.string.tile_category_sound_notifications),
    DISPLAY(R.string.tile_category_display),
    CONNECTIVITY(R.string.tile_category_connectivity),
}

/** A completed one-shot action; backends publish confirmed results, not button presses. */
data class TileActionResult(val message: String, val successful: Boolean,
    val cleanup: MemoryCleanupResult? = null)

data class TileActionCompletion(val tileId: String, val result: TileActionResult)

/** Counts kill requests returning without an exception, not processes or bytes reclaimed. */
data class MemoryCleanupResult(val count: Int, val failed: Int) {
    // A failed process-list query counts as one failed operation.
    val ok: Boolean get() = failed == 0
}

interface TileAction {
    val id: String
    val kind: TileKind
    val category: TileCategory
    val label: String
    /** A well-known shorter form of [label], used only where [label] does not fit. */
    val shortLabel: String? get() = null
    val description: String get() = label
    val icon: Int
    val isEnabled: Boolean
    val isAvailable: Boolean get() = true
    /** Current value for cycles/dialogs; toggles expose their state through [isEnabled]. */
    val valueLine: String? get() = null
    val actionResult: TileActionResult? get() = null
    val sessionOption: GameOption? get() = null
    val isSessionTile: Boolean get() = sessionOption != null
    val differsFromSaved: Boolean get() = false
    /** A short description shown at the top of the tile's detail page. */
    val detailNote: String? get() = null
    /** A short note shown below the options on the tile's detail page. */
    val detailFooter: String? get() = null
    /** A reason the tile is set but cannot take effect; the tile then uses the attention colour. */
    val notice: String? get() = null
    /** Non-empty when tapping the tile opens a picker after [toggle] ran. */
    val options: List<TileOption> get() = emptyList()
    @Composable fun observeEnabled(): State<Boolean>
    fun toggle()
    fun selectOption(option: TileOption) {}
    /** A setting that holds the tile back, shown in the attention colour without a text. */
    val limited: Boolean get() = false
}

class ToggleableTile(
    override val id: String,
    override var label: String,
    override val icon: Int,
    override val category: TileCategory,
    private val state: MutableState<Boolean>,
    private val setter: (Boolean) -> Unit,
    override val sessionOption: GameOption? = null,
    override val isSessionTile: Boolean = sessionOption != null,
    private val available: () -> Boolean = { true },
    private val readSavedDifference: () -> Boolean = { false },
    private val readValueLine: (() -> String?)? = null,
    override val shortLabel: String? = null,
) : TileAction {
    override val differsFromSaved: Boolean get() = readSavedDifference()
    override val kind = TileKind.TOGGLE
    override val isAvailable: Boolean get() = available()
    override val isEnabled: Boolean get() = state.value
    override val valueLine: String? get() = readValueLine?.invoke()
    @Composable override fun observeEnabled(): State<Boolean> = rememberUpdatedState(state.value)
    override fun toggle() {
        if (!isAvailable) return
        state.value = !state.value
        setter(state.value)
    }
}

class FixedActionTile(
    override val id: String,
    override val label: String,
    override val icon: Int,
    override val category: TileCategory,
    private val action: () -> Unit,
    override val description: String = label,
    private val available: () -> Boolean = { true },
    private val readResult: () -> TileActionResult? = { null },
    override val shortLabel: String? = null,
) : TileAction {
    override val actionResult: TileActionResult? get() = readResult()
    override val kind = TileKind.ACTION
    override val isAvailable: Boolean get() = available()
    override val isEnabled: Boolean = false
    @Composable override fun observeEnabled(): State<Boolean> = rememberUpdatedState(false)
    override fun toggle() { if (isAvailable) action() }
}

class CycleTile(
    override val id: String,
    override val label: String,
    private val defaultIcon: Int,
    override val category: TileCategory,
    override val sessionOption: GameOption,
    private val readValue: () -> String,
    private val cycle: () -> Unit,
    private val readSavedDifference: () -> Boolean = { false },
    private val readIcon: () -> Int = { defaultIcon },
    /** Whether the tile reads as on; a cycle with no off position leaves it false. */
    private val readEnabled: () -> Boolean = { false },
    override val shortLabel: String? = null,
) : TileAction {
    override val icon: Int get() = readIcon()
    override val differsFromSaved: Boolean get() = readSavedDifference()
    override val kind = TileKind.CYCLE
    override val isEnabled: Boolean get() = readEnabled()
    override val valueLine: String get() = readValue()
    @Composable override fun observeEnabled(): State<Boolean> = rememberUpdatedState(readEnabled())
    override fun toggle() = cycle()
}

class PlatformTile(
    override val id: String,
    override val icon: Int,
    override val kind: TileKind,
    override val category: TileCategory,
    private val feature: String,
    private val platform: AxPlatformClient,
    override val sessionOption: GameOption? = null,
    private val usePlatformLabel: Boolean = true,
    private val readActive: (() -> Boolean)? = null,
    private val toggleAction: (() -> Unit)? = null,
    private val readSavedDifference: () -> Boolean = { false },
    private val readResult: () -> TileActionResult? = { null },
    override val shortLabel: String? = null,
) : TileAction {
    override val differsFromSaved: Boolean get() = readSavedDifference()
    override val actionResult: TileActionResult? get() = readResult()
    val activeState = mutableStateOf(false)
    val labelState = mutableStateOf("")
    val availableState = mutableStateOf(false)
    private val secondaryLabelState = mutableStateOf<String?>(null)

    override val label: String get() = labelState.value
    override val isEnabled: Boolean get() = kind == TileKind.TOGGLE && activeState.value
    override val isAvailable: Boolean get() = availableState.value
    override val valueLine: String? get() = secondaryLabelState.value
    @Composable override fun observeEnabled(): State<Boolean> = rememberUpdatedState(isEnabled)

    override fun toggle() {
        if (!isAvailable) return
        toggleAction?.invoke() ?: platform.toggle(feature)
    }

    fun updateFromState(state: Bundle) {
        activeState.value = readActive?.invoke() ?: when (feature) {
            // QS highlights access, whereas the hook reports whether access is blocked.
            AxPlatformClient.FEATURE_CAMERA_PRIVACY, AxPlatformClient.FEATURE_MIC_PRIVACY ->
                !state.getBoolean("active", false)
            AxPlatformClient.FEATURE_SCREEN_RECORD -> state.getBoolean("active", false)
            else -> state.getBoolean("active", false)
        }
        secondaryLabelState.value = state.getString("secondaryLabel")?.takeIf { it.isNotBlank() }
        val newLabel = AxPlatformClient.getLabel(state)
        if (usePlatformLabel && !newLabel.isNullOrBlank()) labelState.value = newLabel
    }
}

@Singleton
class TileRepository @Inject constructor(
    private val context: Context,
    private val appSettings: AppSettings,
    private val systemSettings: SystemSettings,
    private val gameSession: GameSession,
    private val aspectRatioTile: GameAspectRatioTile,
    private val frameInterpTile: GameFrameInterpTile,
    private val superResolutionTile: GameSuperResolutionTile,
) {
    private val recording = RecordingStateController()
    private lateinit var platform: AxPlatformClient
    private var listening = false

    private lateinit var defaultTiles: List<TileAction>
    private val platformTiles = mutableListOf<PlatformTile>()
    var onBeforePlatformAction: ((String) -> Unit)? = null
    var onScreenshotComplete: (() -> Unit)? = null
    var onScreenOff: (() -> Unit)? = null
    private var lifecycleGeneration = 0
    private val latestResults = mutableStateMapOf<String, TileActionResult>()
    private val mutableActionResults = MutableSharedFlow<TileActionCompletion>(extraBufferCapacity = 16)
    val actionResults = mutableActionResults.asSharedFlow()
    private val mutableScreenshotSuccess = MutableStateFlow<Boolean?>(null)
    val screenshotSuccess = mutableScreenshotSuccess.asStateFlow()
    private val mutableStartupCleanup = MutableStateFlow<MemoryCleanupResult?>(null)
    val startupMemoryCleanup = mutableStartupCleanup.asStateFlow()
    private val mutableWifiLowLatencyActive = MutableStateFlow(false)
    val wifiLowLatencyActive = mutableWifiLowLatencyActive.asStateFlow()
    val recordingState = recording.state

    fun setWifiLowLatencyActive(active: Boolean) { mutableWifiLowLatencyActive.value = active }

    fun stopRecording() {
        if (listening && recordingState.value.phase == RecordingPhase.ACTIVE) {
            platform.setEnabled(AxPlatformClient.FEATURE_SCREEN_RECORD, false)
        }
    }

    fun reportActionMessage(id: String, message: String) {
        publishResult(id, TileActionResult(message, successful = true))
    }

    private fun publishResult(id: String, result: TileActionResult) {
        latestResults[id] = result
        mutableActionResults.tryEmit(TileActionCompletion(id, result))
    }

    private fun finishScreenshot(generation: Int, successful: Boolean) {
        if (!listening || generation != lifecycleGeneration) return
        mutableScreenshotSuccess.value = successful
        publishResult(AxPlatformClient.FEATURE_SCREENSHOT, TileActionResult(
            context.getString(if (successful) R.string.game_screenshot_saved
                else R.string.game_screenshot_failed), successful))
        // The sidebar must be restored on failure as well as success.
        onScreenshotComplete?.invoke()
    }

    private val platformListener = object : AxPlatformClient.Listener() {
        override fun onStateChanged(key: String, state: Bundle) {
            if (!listening || !::defaultTiles.isInitialized) return
            if (supportedFeatures.isEmpty() || key in setOf(
                    AxPlatformClient.KEY_BATTERY, AxPlatformClient.KEY_DOZE,
                    AxPlatformClient.KEY_CONFIG, AxPlatformClient.FEATURE_AIRPLANE_MODE
                )) {
                refreshPlatformStates()
            } else {
                platformTiles.find { it.id == key }?.let {
                    updatePlatformTile(it, state)
                    rebuildAvailableTiles()
                }
            }
        }
    }

    private val sessionRevision = mutableIntStateOf(0)
    private var optionsObservation: AutoCloseable? = null

    private fun differsFromSaved(option: GameOption): Boolean {
        // Reading the revision makes this getter observable to every Compose consumer.
        sessionRevision.intValue
        return SessionOptions.differsFromSaved(context, option)
    }

    private val _tileOrder = mutableStateListOf<String>()
    private val preferredTileOrder = mutableListOf<String>()
    private var supportedFeatures = emptySet<String>()
    private var pendingDefaultOrder = false
    private val _availableTiles = mutableStateListOf<TileAction>()
    private val haloState = mutableStateOf(false)
    private val haloEnabledSetting = "halo_reactive_enabled"
    private val haloAvailableSetting = "halo_ring_available"
    private val notificationState = mutableStateOf((appSettings.inGameOptions.current(GameOption.DANMAKU) == 1))
    private val gestureState = mutableStateOf(false)
    private val penInputState = mutableStateOf(false)
    private val touchSensitivityRevision = mutableIntStateOf(0)
    private val wifiLowLatencyState = mutableStateOf(false)
    private val ringerState = mutableStateOf(AudioManager.RINGER_MODE_NORMAL)
    private val bypassChargeState = mutableStateOf(systemSettings.bypassChargeEnabled)
    private val audioManager by lazy { context.getSystemService(AudioManager::class.java) }
    private val ringerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Read AudioManager rather than trusting broadcast extras.
            ringerState.value = audioManager.ringerModeInternal
            sessionRevision.intValue++
        }
    }
    private val preferences by lazy { PreferenceManager.getDefaultSharedPreferences(context) }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            AppSettings.KEY_DANMAKU_NOTIFICATION_MODE ->
                notificationState.value = (appSettings.inGameOptions.current(GameOption.DANMAKU) == 1)
            "show_fps" -> isFpsInfoVisible.value = appSettings.showFps
        }
    }
    private val stateHandler = Handler(Looper.getMainLooper())
    private val screenshotHelper by lazy { ScreenshotHelper(context) }

    private val settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            if (listening) refreshPlatformStates()
        }
    }

    /** Complete catalogue, including temporarily unavailable selected controls. */
    val catalogue: List<TileAction> get() = defaultTiles
    val defaultMainTileCount: Int
        get() = if (context.resources.configuration.smallestScreenWidthDp >= 600) 12 else 8

    val allAvailableTiles: List<TileAction>
        get() = _availableTiles

    /** Controls the editor offers; the timed screen-on control repeats the game-long one. */
    val addableTiles: List<TileAction>
        get() = _availableTiles.filter { it.id != AxPlatformClient.FEATURE_CAFFEINE }

    private val _tiles = mutableStateListOf<TileAction>()
    val tiles: SnapshotStateList<TileAction> get() = _tiles

    val isBrightnessVisible: MutableState<Boolean> = mutableStateOf(appSettings.brightnessEnabled)
    val isFpsGraphVisible: MutableState<Boolean> = mutableStateOf(appSettings.fpsGraphEnabled)
    val isFpsInfoVisible: MutableState<Boolean> = mutableStateOf(appSettings.showFps)

    fun setFpsInfoEnabled(enabled: Boolean) {
        isFpsInfoVisible.value = enabled
        appSettings.showFps = enabled
    }

    fun init(platform: AxPlatformClient) {
        this.platform = platform
        listening = true
        lifecycleGeneration++
        recording.start()
        latestResults.clear()
        mutableScreenshotSuccess.value = null
        mutableStartupCleanup.value = null
        mutableWifiLowLatencyActive.value = false
        context.registerReceiver(ringerReceiver,
            IntentFilter(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION).apply {
                addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            }, null, stateHandler, Context.RECEIVER_EXPORTED)

        defaultTiles = buildDefaultTiles()
        // Seed availability before choosing defaults; the platform may already be connected.
        supportedFeatures = platform.supportedFeatures.toSet()
        platformTiles.forEach { updatePlatformTile(it, platform.getState(it.id)) }
        optionsObservation?.close()
        optionsObservation = appSettings.inGameOptions.observe { sessionRevision.intValue++ }
        sessionRevision.intValue++

        preferredTileOrder.clear()
        preferredTileOrder.addAll(loadTileOrder())
        isFpsInfoVisible.value = appSettings.showFps
        notificationState.value = (appSettings.inGameOptions.current(GameOption.DANMAKU) == 1)
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        platform.addListener(platformListener)
        listOf(
            haloEnabledSetting, haloAvailableSetting, Settings.Secure.NIGHT_DISPLAY_ACTIVATED,
            "ax_gaming_gesture_lock",
            TouchSensitivitySettings.KEY_HIGH_POLLING, TouchSensitivitySettings.KEY_TAP,
            TouchSensitivitySettings.KEY_MOVE, TouchSensitivitySettings.KEY_EDGE,
            TouchSensitivitySettings.KEY_PEN_INPUT
        ).forEach { key ->
            context.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(key), false, settingsObserver, UserHandle.USER_ALL
            )
        }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ZEN_MODE), false, settingsObserver
        )
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor("bypass_charge_enabled"), false,
            settingsObserver, UserHandle.USER_ALL
        )
        refreshPlatformStates()
    }

    fun onGameStart(packageName: String) {
        aspectRatioTile.onGameStart(packageName)
        frameInterpTile.onGameStart(packageName)
        superResolutionTile.onGameStart(packageName)
        mutableStartupCleanup.value = null
        if (appSettings.inGameOptions.enabled(packageName, GameOption.MEMORY)) {
            val result = clearBackgroundProcesses()
            mutableStartupCleanup.value = result
        }
        refreshPlatformStates()
    }

    fun onGameStop() {
        aspectRatioTile.onGameStop()
        frameInterpTile.onGameStop()
        superResolutionTile.onGameStop()
        mutableStartupCleanup.value = null
    }

    fun refreshPlatformStates() {
        if (!listening || !::platform.isInitialized) return
        aspectRatioTile.refresh()
        frameInterpTile.refresh()
        superResolutionTile.refresh()
        supportedFeatures = platform.supportedFeatures.toSet()
        platformTiles.forEach { tile ->
            updatePlatformTile(tile, platform.getState(tile.id))
        }
        notificationState.value = (appSettings.inGameOptions.current(GameOption.DANMAKU) == 1)
        haloState.value = Settings.Secure.getIntForUser(
            context.contentResolver, haloEnabledSetting, 0, UserHandle.USER_SYSTEM
        ) == 1
        bypassChargeState.value = systemSettings.bypassChargeEnabled
        isFpsInfoVisible.value = appSettings.showFps
        gestureState.value = systemSettings.gestureLock
        penInputState.value = TouchSensitivitySettings.penInput(context)
        touchSensitivityRevision.intValue++
        wifiLowLatencyState.value = appSettings.wifiLowLatency
        ringerState.value = audioManager.ringerModeInternal
        if (pendingDefaultOrder && supportedFeatures.isNotEmpty()) {
            // Binding is asynchronous. Complete a fresh/reset default once states arrive,
            // without reselecting a saved custom list or following later availability changes.
            pendingDefaultOrder = false
            val order = defaultTileOrder()
            appSettings.saveTileConfiguration(order, order, TILE_ORDER_VERSION)
            preferredTileOrder.clear()
            preferredTileOrder.addAll(order)
        }
        rebuildAvailableTiles()
    }

    private fun updatePlatformTile(tile: PlatformTile, state: Bundle) {
        if (tile.id == AxPlatformClient.FEATURE_SCREEN_RECORD) recording.update(state)
        tile.availableState.value = tile.id in supportedFeatures &&
            state.getBoolean("available", true) &&
            (!state.containsKey("tileState") ||
                AxPlatformClient.getTileState(state) != AxPlatformClient.TILE_STATE_UNAVAILABLE) &&
            hasDeviceCapability(tile.id)
        tile.updateFromState(state)
    }

    private fun rebuildAvailableTiles() {
        val available = defaultTiles.filter { it.isAvailable }
        if (_availableTiles.toList() != available) {
            _availableTiles.clear()
            _availableTiles.addAll(available)
        }
        val order = preferredTileOrder.filter { id -> available.any { it.id == id } }
        _tileOrder.clear()
        _tileOrder.addAll(order)
        val selected = order.mapNotNull { id -> available.find { it.id == id } }
        if (_tiles.toList() != selected) {
            _tiles.clear()
            _tiles.addAll(selected)
        }
        saveTileOrder()
    }

    // The service omits capability flags for some features. Match the underlying
    // Android/QS checks rather than treating a writable setting as hardware support.
    private fun hasDeviceCapability(id: String): Boolean {
        val pm = context.packageManager
        return when (id) {
            AxPlatformClient.FEATURE_WIFI -> pm.hasSystemFeature(PackageManager.FEATURE_WIFI)
            AxPlatformClient.FEATURE_BLUETOOTH ->
                pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
            AxPlatformClient.FEATURE_MOBILE_DATA ->
                pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_DATA) &&
                    Settings.Global.getInt(
                        context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0
                    ) == 0
            AxPlatformClient.FEATURE_NIGHT_LIGHT ->
                ColorDisplayManager.isNightDisplayAvailable(context)
            "halo" -> UserHandle.myUserId() == UserHandle.USER_SYSTEM &&
                ActivityManager.getCurrentUser() == UserHandle.USER_SYSTEM &&
                Settings.Secure.getIntForUser(context.contentResolver,
                    haloAvailableSetting, 0, UserHandle.USER_SYSTEM) == 1
            "fps_info" -> context.checkSelfPermission(Manifest.permission.ACCESS_FPS_COUNTER) ==
                PackageManager.PERMISSION_GRANTED
            "stay_awake" -> context.checkSelfPermission(Manifest.permission.WAKE_LOCK) ==
                PackageManager.PERMISSION_GRANTED
            "bypass_charge_enabled" -> Build.MANUFACTURER.equals("Google", ignoreCase = true) ||
                SystemProperties.getBoolean("persist.sys.ax_chg_bypass", false) ||
                SystemProperties.getBoolean("persist.sys.battery_bypass_supported", false)
            "screen_aspect_ratio" -> aspectRatioTile.isAvailable
            "touch_sensitivity", "pen_input" -> true
            "frame_interpolation" -> frameInterpTile.isAvailable
            "super_resolution" -> superResolutionTile.isAvailable
            "wifi_low_latency" -> appSettings.wifiLowLatencySupported
            "boost_memory" ->
                context.checkSelfPermission(Manifest.permission.KILL_BACKGROUND_PROCESSES) ==
                    PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.KILL_ALL_BACKGROUND_PROCESSES) ==
                    PackageManager.PERMISSION_GRANTED
            else -> true
        }
    }

    fun dispose() {
        listening = false
        lifecycleGeneration++
        recording.dispose()
        context.unregisterReceiver(ringerReceiver)
        mutableStartupCleanup.value = null
        mutableWifiLowLatencyActive.value = false
        platform.removeListener(platformListener)
        context.contentResolver.unregisterContentObserver(settingsObserver)
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        optionsObservation?.close()
        optionsObservation = null
        onBeforePlatformAction = null
        onScreenshotComplete = null
    }

    fun setBrightnessEnabled(enabled: Boolean) {
        isBrightnessVisible.value = enabled
        appSettings.brightnessEnabled = enabled
    }

    fun setFpsGraphEnabled(enabled: Boolean) {
        isFpsGraphVisible.value = enabled
        appSettings.fpsGraphEnabled = enabled
    }

    private fun saveTileOrder() {
        if (appSettings.tileOrder != _tileOrder.toList()) appSettings.tileOrder = _tileOrder.toList()
        if (appSettings.preferredTileOrder != preferredTileOrder) {
            appSettings.preferredTileOrder = preferredTileOrder.toList()
        }
    }

    private fun defaultTileOrder(): List<String> {
        val availableIds = defaultTiles.filter { tile ->
            if (tile is PlatformTile && supportedFeatures.isEmpty()) hasDeviceCapability(tile.id)
            else tile.isAvailable
        }.map { it.id }.toSet()
        return DEFAULT_MAIN_ORDER.filter { it in availableIds }.take(defaultMainTileCount) + DEFAULT_MORE_ORDER
    }

    private fun loadTileOrder(): List<String> {
        val currentDefault = defaultTileOrder()
        val savedOrder = appSettings.preferredTileOrder ?: appSettings.savedTileOrder
        val snapshot = appSettings.defaultTileSnapshot
        val needsMigration = appSettings.tileOrderVersion < TILE_ORDER_VERSION
        // Only a matching snapshot proves that an old panel was untouched. No snapshot
        // means unknown history: preserve that user's selection, including an empty list.
        val useDefault = savedOrder == null ||
            (needsMigration && savedOrder.isNotEmpty() && snapshot != null && savedOrder == snapshot)
        pendingDefaultOrder = useDefault && supportedFeatures.isEmpty()
        val selection = if (useDefault) currentDefault else savedOrder.orEmpty()
        val migrated = if (needsMigration) migrateSavedOrder(selection) else selection
        // Filter the catalogue, not availability: unplugging a device or hiding a
        // capability must never remove a user's selection from its saved position.
        val catalogIds = defaultTiles.map { it.id }.toSet()
        val order = migrated.filter { it in catalogIds }.distinct()
        val newSnapshot = if (useDefault) currentDefault
            else (snapshot ?: currentDefault).filter { it in catalogIds }.distinct()
        appSettings.saveTileConfiguration(order, newSnapshot, TILE_ORDER_VERSION)
        return order
    }

    /** Existing installs keep their order but gain the new touch controls once. */
    private fun migrateSavedOrder(order: List<String>): List<String> {
        val migrated = order.toMutableList()
        val touchIndex = migrated.indexOf("touch_response")
        if (touchIndex >= 0) {
            migrated[touchIndex] = "touch_sensitivity"
        } else if ("touch_sensitivity" !in migrated) {
            val anchor = migrated.indexOf("bypass_charge_enabled")
            migrated.add(if (anchor >= 0) anchor + 1 else migrated.size, "touch_sensitivity")
        }
        if ("pen_input" !in migrated) migrated.add("pen_input")
        return migrated
    }

    fun resetToDefault() {
        pendingDefaultOrder = supportedFeatures.isEmpty()
        val order = defaultTileOrder()
        appSettings.saveTileConfiguration(order, order, TILE_ORDER_VERSION)
        preferredTileOrder.clear()
        preferredTileOrder.addAll(order)
        rebuildAvailableTiles()
    }

    companion object {
        private const val TILE_ORDER_VERSION = 2
        private const val RINGER_NO_CHANGE = 3
        private val DEFAULT_MAIN_ORDER = listOf(
            AxPlatformClient.FEATURE_SCREENSHOT, AxPlatformClient.FEATURE_SCREEN_RECORD,
            "fps_info", AxPlatformClient.FEATURE_ZEN, "gesture_lock", "bypass_charge_enabled",
            "touch_sensitivity", "screen_off", "ringer", "notification", "screen_aspect_ratio",
            "boost_memory",
        )
        private val DEFAULT_MORE_ORDER = listOf(
            "pen_input", AxPlatformClient.FEATURE_WIFI, AxPlatformClient.FEATURE_BLUETOOTH,
            "frame_interpolation", "super_resolution",
        )
    }

    private fun clearBackgroundProcesses(): MemoryCleanupResult {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val runningApps = try {
            activityManager.runningAppProcesses ?: return MemoryCleanupResult(0, 1)
        } catch (_: RuntimeException) {
            return MemoryCleanupResult(0, 1)
        }
        // A process may host multiple packages; protect foreground packages and the game
        // even when a system dialog has temporarily moved that game to the background.
        val protectedPackages = runningApps
            .filter { it.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND }
            .flatMap { it.pkgList?.toList().orEmpty() }
            .toSet() + context.packageName + listOfNotNull(SessionOptions.packageName(context))
        var count = 0
        var failed = 0
        runningApps
            .filter { it.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND }
            .flatMap { it.pkgList?.toList().orEmpty() }
            .distinct()
            .filterNot { it in protectedPackages }
            .forEach { packageName ->
                try {
                    activityManager.killBackgroundProcesses(packageName)
                    count++
                } catch (_: RuntimeException) {
                    failed++
                }
            }
        return MemoryCleanupResult(count, failed)
    }

    private fun memoryCleanupMessage(result: MemoryCleanupResult): String = context.getString(when {
        !result.ok -> R.string.game_memory_cleanup_failed
        result.count > 0 -> R.string.game_memory_cleanup_ran
        else -> R.string.game_memory_nothing_to_boost
    })

    fun updateTileSelection(selectedIds: List<String>) {
        pendingDefaultOrder = false
        val selected = selectedIds.filter { id -> _availableTiles.any { it.id == id } }
            .distinct().toMutableList()
        // Keep absent selections in their former slots, even when the visible order is edited.
        val order = buildList {
            preferredTileOrder.forEach { id ->
                if (_availableTiles.none { it.id == id }) add(id)
                else if (selected.isNotEmpty()) add(selected.removeAt(0))
            }
            addAll(selected)
        }
        preferredTileOrder.clear()
        preferredTileOrder.addAll(order)
        rebuildAvailableTiles()
    }

    private fun secureEnabled(key: String): Boolean =
        Settings.Secure.getIntForUser(context.contentResolver, key, 0, UserHandle.USER_CURRENT) == 1

    private fun openSettings(action: String) {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) != null) context.startActivity(intent)
    }

    private fun platformTile(feature: String, iconRes: Int, fallbackLabel: String,
        shortLabel: String? = null): PlatformTile {
        // These hooks describe different controls/settings on this ROM. Observe the actual
        // settings so changes from Settings/QS stay in sync.
        val observedSetting = when (feature) {
            AxPlatformClient.FEATURE_NIGHT_LIGHT -> Settings.Secure.NIGHT_DISPLAY_ACTIVATED
            else -> null
        }
        val tile = PlatformTile(
            id = feature,
            icon = iconRes,
            feature = feature,
            platform = platform,
            shortLabel = shortLabel,
            readResult = { latestResults[feature] },
            kind = if (feature == AxPlatformClient.FEATURE_SCREENSHOT) TileKind.ACTION else TileKind.TOGGLE,
            category = when (feature) {
                AxPlatformClient.FEATURE_ZEN, AxPlatformClient.FEATURE_HEADS_UP ->
                    TileCategory.SOUND_NOTIFICATIONS
                AxPlatformClient.FEATURE_ROTATION, AxPlatformClient.FEATURE_NIGHT_LIGHT ->
                    TileCategory.DISPLAY
                AxPlatformClient.FEATURE_CAFFEINE, AxPlatformClient.FEATURE_SCREENSHOT,
                AxPlatformClient.FEATURE_SCREEN_RECORD -> TileCategory.GAME_TOOLS
                else -> TileCategory.CONNECTIVITY
            },
            sessionOption = if (feature == AxPlatformClient.FEATURE_ZEN) GameOption.DND else null,
            usePlatformLabel = feature != AxPlatformClient.FEATURE_MOBILE_DATA &&
                feature != AxPlatformClient.FEATURE_CAFFEINE &&
                feature != AxPlatformClient.FEATURE_ZEN &&
                feature != AxPlatformClient.FEATURE_SCREEN_RECORD &&
                feature != AxPlatformClient.FEATURE_HEADS_UP,
            readActive = when (feature) {
                AxPlatformClient.FEATURE_ZEN -> { {
                    Settings.Global.getInt(context.contentResolver, Settings.Global.ZEN_MODE, 0) != 0
                } }
                else -> observedSetting?.let { { secureEnabled(it) } }
            },
            toggleAction = {
                onBeforePlatformAction?.invoke(feature)
                when (feature) {
                    AxPlatformClient.FEATURE_ZEN -> {
                        val enabled = Settings.Global.getInt(context.contentResolver, Settings.Global.ZEN_MODE, 0) != 0
                        gameSession.saveGlobalOption(GameOption.DND, if (enabled) 0 else 1)
                    }
                    AxPlatformClient.FEATURE_VPN -> {
                        if (platform.getState(feature).getBoolean("enabled", false)) {
                            platform.toggle(feature)
                        } else {
                            openSettings(Settings.ACTION_VPN_SETTINGS)
                        }
                    }
                    AxPlatformClient.FEATURE_SCREENSHOT -> {
                        val generation = lifecycleGeneration
                        mutableScreenshotSuccess.value = null
                        stateHandler.postDelayed({
                            if (!listening || generation != lifecycleGeneration) return@postDelayed
                            try {
                                screenshotHelper.takeScreenshot(
                                    WindowManager.TAKE_SCREENSHOT_FULLSCREEN,
                                    WindowManager.ScreenshotSource.SCREENSHOT_OTHER,
                                    stateHandler
                                ) { uri -> finishScreenshot(generation, uri != null) }
                            } catch (_: RuntimeException) {
                                finishScreenshot(generation, false)
                            }
                        }, 500)
                    }
                    else -> platform.toggle(feature)
                }
            },
        )
        tile.labelState.value = fallbackLabel
        platformTiles.add(tile)
        return tile
    }

    private fun buildDefaultTiles(): List<TileAction> = buildList {
        platformTiles.clear()

        add(ToggleableTile(
            id = "halo",
            category = TileCategory.GAME_TOOLS,
            available = { hasDeviceCapability("halo") },
            label = context.getString(R.string.tile_halo),
            icon = R.drawable.materialsymbols_ic_palette_rounded_filled,
            state = haloState,
            setter = { enabled ->
                if (hasDeviceCapability("halo")) {
                    Settings.Secure.putIntForUser(context.contentResolver, haloEnabledSetting,
                        if (enabled) 1 else 0, UserHandle.USER_SYSTEM)
                }
                haloState.value = Settings.Secure.getIntForUser(context.contentResolver,
                    haloEnabledSetting, 0, UserHandle.USER_SYSTEM) == 1
            },
        ))

        add(platformTile(
            AxPlatformClient.FEATURE_WIFI,
            R.drawable.materialsymbols_ic_wifi_rounded_filled,
            context.getString(R.string.tile_wifi),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_BLUETOOTH,
            R.drawable.materialsymbols_ic_bluetooth_rounded_filled,
            context.getString(R.string.tile_bluetooth),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_ZEN,
            R.drawable.materialsymbols_ic_do_not_disturb_on_rounded_filled,
            context.getString(R.string.tile_dnd),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_ROTATION,
            R.drawable.materialsymbols_ic_screen_rotation_up_rounded_filled,
            context.getString(R.string.tile_auto_rotate),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_MOBILE_DATA,
            R.drawable.materialsymbols_ic_android_cell_4_bar_rounded_filled,
            context.getString(R.string.tile_mobile_data),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_NIGHT_LIGHT,
            R.drawable.materialsymbols_ic_nights_stay_rounded_filled,
            context.getString(R.string.tile_night_light),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_HEADS_UP,
            R.drawable.materialsymbols_ic_notifications_active_rounded_filled,
            context.getString(R.string.tile_heads_up),
            context.shortLabel(R.string.tile_heads_up_short),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_CAFFEINE,
            R.drawable.materialsymbols_ic_local_cafe_rounded_filled,
            context.getString(R.string.tile_caffeine),
            context.shortLabel(R.string.tile_caffeine_short),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_VPN,
            R.drawable.materialsymbols_ic_vpn_key_rounded_filled,
            context.getString(R.string.tile_vpn),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_SCREEN_RECORD,
            R.drawable.materialsymbols_ic_videocam_rounded_filled,
            context.getString(R.string.tile_screen_record),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_SCREENSHOT,
            R.drawable.materialsymbols_ic_screenshot_rounded_filled,
            context.getString(R.string.tile_screenshot),
        ))

        add(
            ToggleableTile(
                id = "notification",
                category = TileCategory.SOUND_NOTIFICATIONS,
                available = { hasDeviceCapability("notification") },
                sessionOption = GameOption.DANMAKU,
                readSavedDifference = { differsFromSaved(GameOption.DANMAKU) },
                label = context.getString(R.string.tile_danmaku),
                shortLabel = context.shortLabel(R.string.tile_danmaku_short),
                icon = R.drawable.materialsymbols_ic_forum_rounded_filled,
                state = notificationState,
                setter = { gameSession.saveOption(GameOption.DANMAKU, if (it) 1 else 0) }
            )
        )

        add(
            ToggleableTile(
                id = "bypass_charge_enabled",
                category = TileCategory.GAME_TOOLS,
                available = { hasDeviceCapability("bypass_charge_enabled") },
                sessionOption = GameOption.BYPASS,
                readSavedDifference = { differsFromSaved(GameOption.BYPASS) },
                label = context.getString(R.string.bypass_charge_enabled_title),
                shortLabel = context.shortLabel(R.string.bypass_charge_enabled_title_short),
                icon = R.drawable.materialsymbols_ic_battery_charging_full_rounded_filled,
                state = bypassChargeState,
                setter = { gameSession.saveOption(GameOption.BYPASS, if (it) 1 else 0) }
            )
        )

        add(ToggleableTile(
            id = "gesture_lock",
            label = context.getString(R.string.tile_gesture_lock),
            shortLabel = context.shortLabel(R.string.tile_gesture_lock_short),
            icon = R.drawable.materialsymbols_ic_lock_rounded_filled,
            category = TileCategory.GAME_TOOLS,
            sessionOption = GameOption.GESTURES,
            readSavedDifference = { differsFromSaved(GameOption.GESTURES) },
            state = gestureState,
            setter = { gameSession.saveOption(GameOption.GESTURES, if (it) 1 else 0) },
        ))
        add(TouchSensitivityTile(context) { touchSensitivityRevision.intValue })
        add(ToggleableTile(
            id = "pen_input",
            label = context.getString(R.string.tile_pen_input),
            shortLabel = context.shortLabel(R.string.tile_pen_input_short),
            icon = R.drawable.materialsymbols_ic_stylus_rounded_filled,
            category = TileCategory.GAME_TOOLS,
            state = penInputState,
            available = { hasDeviceCapability("pen_input") },
            setter = {
                TouchSensitivitySettings.setValue(context, SessionOptions.packageName(context),
                    TouchSensitivitySettings.KEY_PEN_INPUT, if (it) 1 else 0)
                penInputState.value = it
            },
        ))
        add(CycleTile(
            id = "ringer",
            label = context.getString(R.string.tile_ringer),
            defaultIcon = R.drawable.materialsymbols_ic_volume_up_rounded_filled,
            category = TileCategory.SOUND_NOTIFICATIONS,
            sessionOption = GameOption.RINGER,
            // Vibrate also silences the ringer, so only sound reads as on.
            readEnabled = { ringerState.value == AudioManager.RINGER_MODE_NORMAL },
            readIcon = {
                when (ringerState.value) {
                    AudioManager.RINGER_MODE_SILENT -> R.drawable.materialsymbols_ic_volume_off_rounded_filled
                    AudioManager.RINGER_MODE_VIBRATE -> R.drawable.materialsymbols_ic_vibration_rounded_filled
                    else -> R.drawable.materialsymbols_ic_volume_up_rounded_filled
                }
            },
            readValue = {
                context.getString(when (ringerState.value) {
                    AudioManager.RINGER_MODE_SILENT -> R.string.tile_ringer_mute
                    AudioManager.RINGER_MODE_VIBRATE -> R.string.tile_ringer_vibrate
                    else -> R.string.tile_ringer_sound
                })
            },
            cycle = {
                val current = audioManager.ringerModeInternal
                val next = when (current) {
                    AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
                    AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
                    else -> AudioManager.RINGER_MODE_NORMAL
                }
                // Only vibrate and mute carry over to later games; sound lasts until this one ends.
                gameSession.saveGlobalOption(GameOption.RINGER,
                    if (next == AudioManager.RINGER_MODE_NORMAL) RINGER_NO_CHANGE else next)
                if (audioManager.ringerModeInternal != next) audioManager.ringerModeInternal = next
                ringerState.value = audioManager.ringerModeInternal
            },
        ))
        add(ToggleableTile(
            id = "wifi_low_latency",
            label = context.getString(R.string.wifi_low_latency_title),
            shortLabel = context.shortLabel(R.string.wifi_low_latency_title_short),
            icon = R.drawable.materialsymbols_ic_wifi_rounded_filled,
            category = TileCategory.CONNECTIVITY,
            state = wifiLowLatencyState,
            available = { hasDeviceCapability("wifi_low_latency") },
            setter = {
                gameSession.saveOption(GameOption.WIFI_LOW_LATENCY, if (it) 1 else 0)
                refreshPlatformStates()
            },
        ))

        add(aspectRatioTile)
        add(frameInterpTile)
        add(superResolutionTile)

        add(
            ToggleableTile(
                id = "stay_awake",
                category = TileCategory.GAME_TOOLS,
                available = { hasDeviceCapability("stay_awake") },
                sessionOption = GameOption.AWAKE,
                label = context.getString(R.string.tile_stay_awake),
                shortLabel = context.shortLabel(R.string.tile_stay_awake_short),
                icon = R.drawable.materialsymbols_ic_bedtime_rounded_filled,
                state = systemSettings.stayAwakeState,
                setter = {
                    gameSession.saveGlobalOption(GameOption.AWAKE, if (it) 1 else 0)
                }
            )
        )

        add(
            ToggleableTile(
                id = "fps_info",
                category = TileCategory.GAME_TOOLS,
                available = { hasDeviceCapability("fps_info") },
                label = context.getString(R.string.tile_fps_info),
                shortLabel = context.shortLabel(R.string.tile_fps_info_short),
                icon = R.drawable.materialsymbols_ic_bar_chart_rounded_filled,
                state = isFpsInfoVisible,
                setter = { setFpsInfoEnabled(it) }
            )
        )

        add(
            FixedActionTile(
                id = "screen_off",
                category = TileCategory.GAME_TOOLS,
                available = { hasDeviceCapability("screen_off") },
                label = context.getString(R.string.tile_screen_off),
                icon = R.drawable.materialsymbols_ic_screen_off_rounded_filled,
                description = context.getString(R.string.tile_screen_off_description),
                readResult = { latestResults["screen_off"] },
                action = { onScreenOff?.invoke() },
            )
        )

        add(
            FixedActionTile(
                id = "boost_memory",
                category = TileCategory.GAME_TOOLS,
                available = { hasDeviceCapability("boost_memory") },
                label = context.getString(R.string.tile_boost_memory),
                shortLabel = context.shortLabel(R.string.tile_boost_memory_short),
                icon = R.drawable.materialsymbols_ic_memory_rounded_filled,
                readResult = { latestResults["boost_memory"] },
                action = {
                    val result = clearBackgroundProcesses()
                    publishResult("boost_memory", TileActionResult(memoryCleanupMessage(result), result.ok, result))
                }
            )
        )
    }
}
