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
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.hardware.SensorPrivacyManager
import android.hardware.display.ColorDisplayManager
import android.net.TetheringManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemProperties
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.service.dreams.DreamService
import android.telephony.TelephonyManager
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.preference.PreferenceManager
import com.android.axion.platform.AxPlatformClient
import com.android.internal.util.ScreenshotHelper
import io.chaldeaprjkt.gamespace.R
import io.chaldeaprjkt.gamespace.data.AppSettings
import io.chaldeaprjkt.gamespace.data.GameSession
import io.chaldeaprjkt.gamespace.data.SystemSettings
import javax.inject.Inject
import javax.inject.Singleton
import lineageos.hardware.LineageHardwareManager

interface TileAction {
    val id: String
    val label: String
    val description: String get() = label
    val icon: Int
    val isEnabled: Boolean
    @Composable fun observeEnabled(): State<Boolean>
    fun toggle()
}

class ToggleableTile(
    override val id: String,
    override var label: String,
    override val icon: Int,
    private val state: MutableState<Boolean>,
    private val setter: (Boolean) -> Unit,
) : TileAction {
    override val isEnabled: Boolean get() = state.value
    @Composable override fun observeEnabled(): State<Boolean> = rememberUpdatedState(state.value)
    override fun toggle() {
        state.value = !state.value
        setter(state.value)
    }
}

class FixedActionTile(
    override val id: String,
    override val label: String,
    override val icon: Int,
    private val action: () -> Unit,
    override val description: String = label,
) : TileAction {
    override val isEnabled: Boolean = false
    @Composable override fun observeEnabled(): State<Boolean> = rememberUpdatedState(false)
    override fun toggle() = action()
}

class PlatformTile(
    override val id: String,
    override val icon: Int,
    private val feature: String,
    private val platform: AxPlatformClient,
    private val usePlatformLabel: Boolean = true,
    private val readActive: (() -> Boolean)? = null,
    private val toggleAction: (() -> Unit)? = null,
) : TileAction {
    val activeState = mutableStateOf(false)
    val labelState = mutableStateOf("")
    val availableState = mutableStateOf(false)

    override val label: String get() = labelState.value
    override val isEnabled: Boolean get() = activeState.value
    @Composable override fun observeEnabled(): State<Boolean> = rememberUpdatedState(activeState.value)

    override fun toggle() {
        if (!availableState.value) return
        toggleAction?.invoke() ?: platform.toggle(feature)
    }

    fun updateFromState(state: Bundle) {
        activeState.value = readActive?.invoke() ?: when (feature) {
            // QS highlights access, whereas the hook reports whether access is blocked.
            AxPlatformClient.FEATURE_CAMERA_PRIVACY, AxPlatformClient.FEATURE_MIC_PRIVACY ->
                !state.getBoolean("active", false)
            AxPlatformClient.FEATURE_SCREEN_RECORD -> state.getBoolean("enabled", false)
            else -> state.getBoolean("active", false)
        }
        val newLabel = AxPlatformClient.getLabel(state)
        if (usePlatformLabel && !newLabel.isNullOrBlank()) labelState.value = newLabel
    }
}

private fun resolveTileOrder(
    savedOrder: List<String>?,
    defaultSnapshot: List<String>?,
    currentDefault: List<String>,
): Pair<List<String>, List<String>> {
    if (savedOrder == null) return currentDefault to currentDefault
    // An explicitly empty selection always means the user removed every tile.
    if (savedOrder.isEmpty()) return savedOrder to (defaultSnapshot ?: currentDefault)
    // Move legacy nonempty panels to the current default once.
    if (defaultSnapshot == null || savedOrder == defaultSnapshot) {
        return currentDefault to currentDefault
    }
    return savedOrder to defaultSnapshot
}

@Singleton
class TileRepository @Inject constructor(
    private val context: Context,
    private val appSettings: AppSettings,
    private val systemSettings: SystemSettings,
    private val gameSession: GameSession,
) {
    private lateinit var platform: AxPlatformClient
    private var listening = false

    private lateinit var defaultTiles: List<TileAction>
    private val platformTiles = mutableListOf<PlatformTile>()
    var onBeforePlatformAction: ((String) -> Unit)? = null
    var onScreenshotComplete: (() -> Unit)? = null
    var onScreenOff: (() -> Unit)? = null

    private val platformListener = object : AxPlatformClient.Listener() {
        override fun onStateChanged(key: String, state: Bundle) {
            if (!listening || !::defaultTiles.isInitialized) return
            if (supportedFeatures.isEmpty() || key in setOf(
                    AxPlatformClient.KEY_BATTERY, AxPlatformClient.KEY_DOZE,
                    AxPlatformClient.KEY_CONFIG, AxPlatformClient.FEATURE_DATA_SAVER,
                    AxPlatformClient.FEATURE_AIRPLANE_MODE
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

    private val _tileOrder = mutableStateListOf<String>()
    private val preferredTileOrder = mutableListOf<String>()
    private var supportedFeatures = emptySet<String>()
    private val _availableTiles = mutableStateListOf<TileAction>()
    private val haloState = mutableStateOf(false)
    private val haloEnabledSetting = "halo_reactive_enabled"
    private val haloAvailableSetting = "halo_ring_available"
    private val notificationState = mutableStateOf(appSettings.danmakuNotification)
    private val touchBoostState = mutableStateOf(false)
    private val bypassChargeState = mutableStateOf(systemSettings.bypassChargeEnabled)
    private val preferences by lazy { PreferenceManager.getDefaultSharedPreferences(context) }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            AppSettings.KEY_DANMAKU_NOTIFICATION_MODE ->
                notificationState.value = appSettings.danmakuNotification
            "show_fps" -> isFpsInfoVisible.value = appSettings.showFps
        }
    }
    private val stateHandler = Handler(Looper.getMainLooper())
    private val screenshotHelper by lazy { ScreenshotHelper(context) }
    private val lineageHardware by lazy { LineageHardwareManager.getInstance(context) }
    // Hardware/properties have no removable listener, and the sync listener is
    // scoped to the caller's user. Refresh their live values for the current user
    // while the game service is running, without refreshing the entire cache.
    private val hardwareStateRefresh = object : Runnable {
        override fun run() {
            platformTiles.filter {
                (it.id == AxPlatformClient.FEATURE_READING_MODE ||
                    it.id == AxPlatformClient.FEATURE_AUTO_SYNC) && it.availableState.value
            }.forEach { it.updateFromState(platform.getState(it.id)) }
            touchBoostState.value = SystemProperties.getInt("persist.sys.touchboost_enable", 0) == 1
            scheduleHardwareRefresh()
        }
    }

    private fun scheduleHardwareRefresh() {
        stateHandler.removeCallbacks(hardwareStateRefresh)
        if (!listening) return
        if (platformTiles.any {
                (it.id == AxPlatformClient.FEATURE_READING_MODE ||
                    it.id == AxPlatformClient.FEATURE_AUTO_SYNC) && it.availableState.value
            } || hasDeviceCapability("touch_boost")) {
            stateHandler.postDelayed(hardwareStateRefresh, 1000)
        }
    }

    private val settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            if (listening) refreshPlatformStates()
        }
    }

    val allAvailableTiles: List<TileAction>
        get() = _availableTiles

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

        defaultTiles = buildDefaultTiles()

        preferredTileOrder.clear()
        preferredTileOrder.addAll(loadTileOrder())
        isFpsInfoVisible.value = appSettings.showFps
        notificationState.value = appSettings.danmakuNotification
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        platform.addListener(platformListener)
        listOf(
            haloEnabledSetting, haloAvailableSetting, Settings.Secure.SCREENSAVER_ENABLED, Settings.Secure.SMART_PIXEL_FILTER_ENABLED,
            Settings.Secure.FLASHLIGHT_AVAILABLE, Settings.Secure.FLASHLIGHT_ENABLED,
            Settings.Secure.DOZE_ALWAYS_ON, Settings.Secure.NIGHT_DISPLAY_ACTIVATED,
            Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED,
            Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED,
            Settings.Secure.REDUCE_BRIGHT_COLORS_ACTIVATED, Settings.Secure.ONE_HANDED_MODE_ENABLED
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

    fun refreshPlatformStates() {
        if (!listening || !::platform.isInitialized) return
        supportedFeatures = platform.supportedFeatures.toSet()
        platformTiles.forEach { tile ->
            updatePlatformTile(tile, platform.getState(tile.id))
        }
        notificationState.value = appSettings.danmakuNotification
        haloState.value = Settings.Secure.getIntForUser(
            context.contentResolver, haloEnabledSetting, 0, UserHandle.USER_SYSTEM
        ) == 1
        bypassChargeState.value = systemSettings.bypassChargeEnabled
        isFpsInfoVisible.value = appSettings.showFps
        touchBoostState.value = SystemProperties.getInt("persist.sys.touchboost_enable", 0) == 1
        rebuildAvailableTiles()
        scheduleHardwareRefresh()
    }

    private fun updatePlatformTile(tile: PlatformTile, state: Bundle) {
        // The hook drops availability=true callbacks. The flashlight controller
        // publishes the real hardware state even when the torch is still off.
        val currentState = if (tile.id == AxPlatformClient.FEATURE_FLASHLIGHT) {
            Bundle(state).apply {
                val available = Settings.Secure.getIntForUser(
                    context.contentResolver, Settings.Secure.FLASHLIGHT_AVAILABLE,
                    if (state.getBoolean("available", true)) 1 else 0, UserHandle.USER_SYSTEM
                ) == 1
                putBoolean("available", available)
                putInt("tileState", if (available) AxPlatformClient.TILE_STATE_INACTIVE
                    else AxPlatformClient.TILE_STATE_UNAVAILABLE)
            }
        } else state
        tile.availableState.value = tile.id in supportedFeatures &&
            currentState.getBoolean("available", true) &&
            (!currentState.containsKey("tileState") ||
                AxPlatformClient.getTileState(currentState) != AxPlatformClient.TILE_STATE_UNAVAILABLE) &&
            hasDeviceCapability(tile.id) &&
            (tile.id != AxPlatformClient.FEATURE_USB_TETHER ||
                currentState.getBoolean("usbConnected", false))
        tile.updateFromState(currentState)
    }

    private fun rebuildAvailableTiles() {
        val available = defaultTiles.filter { tile ->
            if (tile is PlatformTile) tile.availableState.value else hasDeviceCapability(tile.id)
        }
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
        val powerManager = context.getSystemService(PowerManager::class.java)
        return when (id) {
            AxPlatformClient.FEATURE_WIFI -> pm.hasSystemFeature(PackageManager.FEATURE_WIFI)
            AxPlatformClient.FEATURE_BLUETOOTH ->
                pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
            AxPlatformClient.FEATURE_MOBILE_DATA ->
                pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_DATA) &&
                    Settings.Global.getInt(
                        context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0
                    ) == 0
            AxPlatformClient.FEATURE_DATA_SAVER ->
                context.getSystemService(TelephonyManager::class.java)?.isDataCapable == true
            AxPlatformClient.FEATURE_NFC -> pm.hasSystemFeature(PackageManager.FEATURE_NFC)
            AxPlatformClient.FEATURE_FLASHLIGHT ->
                pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
            AxPlatformClient.FEATURE_BATTERY_SAVER ->
                (context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) == 0
            AxPlatformClient.FEATURE_DARK_MODE -> !powerManager.isPowerSaveMode
            AxPlatformClient.FEATURE_HOTSPOT ->
                context.getSystemService(UserManager::class.java)
                    .isUserAdmin(ActivityManager.getCurrentUser()) &&
                !platform.getState(AxPlatformClient.FEATURE_DATA_SAVER).getBoolean("active", false) &&
                context.getSystemService(TetheringManager::class.java)?.let {
                    it.isTetheringSupported && it.tetherableWifiRegexs.isNotEmpty()
                } == true
            AxPlatformClient.FEATURE_USB_TETHER ->
                context.getSystemService(TetheringManager::class.java)?.let {
                    it.isTetheringSupported && it.tetherableUsbRegexs.isNotEmpty()
                } == true
            AxPlatformClient.FEATURE_AOD -> context.resources.getBoolean(
                com.android.internal.R.bool.config_dozeAlwaysOnDisplayAvailable
            ) && !powerManager.getPowerSaveState(PowerManager.ServiceType.AOD).batterySaverEnabled
            AxPlatformClient.FEATURE_NIGHT_LIGHT ->
                ColorDisplayManager.isNightDisplayAvailable(context)
            AxPlatformClient.FEATURE_REDUCE_BRIGHTNESS ->
                ColorDisplayManager.isReduceBrightColorsAvailable(context)
            AxPlatformClient.FEATURE_ONE_HANDED_MODE ->
                SystemProperties.getBoolean("ro.support_one_handed_mode", false)
            AxPlatformClient.FEATURE_CAMERA_PRIVACY ->
                context.getSystemService(SensorPrivacyManager::class.java)
                    ?.supportsSensorToggle(SensorPrivacyManager.Sensors.CAMERA) == true
            AxPlatformClient.FEATURE_MIC_PRIVACY ->
                context.getSystemService(SensorPrivacyManager::class.java)
                    ?.supportsSensorToggle(SensorPrivacyManager.Sensors.MICROPHONE) == true
            AxPlatformClient.FEATURE_DREAM ->
                context.resources.getBoolean(com.android.internal.R.bool.config_dreamsSupported) &&
                    pm.queryIntentServices(Intent(DreamService.SERVICE_INTERFACE), 0)
                        .any { it.serviceInfo.permission == Manifest.permission.BIND_DREAM_SERVICE }
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
            "touch_boost" -> SystemProperties.getBoolean("persist.sys.ax_touch_boost", false)
            "boost_memory" ->
                context.checkSelfPermission(Manifest.permission.KILL_BACKGROUND_PROCESSES) ==
                    PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.KILL_ALL_BACKGROUND_PROCESSES) ==
                    PackageManager.PERMISSION_GRANTED
            "settings" -> Intent(Settings.ACTION_SETTINGS).resolveActivity(pm) != null
            else -> true
        }
    }

    fun dispose() {
        listening = false
        platform.removeListener(platformListener)
        context.contentResolver.unregisterContentObserver(settingsObserver)
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        stateHandler.removeCallbacks(hardwareStateRefresh)
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

    // Rarely used in a game session: left out of a new panel, still available in the editor
    private val TILES_OFF_BY_DEFAULT = setOf(
        AxPlatformClient.FEATURE_AUTO_SYNC,
        AxPlatformClient.FEATURE_COLOR_INVERSION,
        AxPlatformClient.FEATURE_COLOR_CORRECTION,
        AxPlatformClient.FEATURE_CAMERA_PRIVACY,
        AxPlatformClient.FEATURE_MIC_PRIVACY,
        AxPlatformClient.FEATURE_READING_MODE,
        AxPlatformClient.FEATURE_CAFFEINE,
        "stay_awake",
        AxPlatformClient.FEATURE_DREAM,
        AxPlatformClient.FEATURE_CAST,
        AxPlatformClient.FEATURE_PROFILES,
    )

    private fun loadTileOrder(): List<String> {
        val catalogIds = defaultTiles.map { it.id }
        val currentDefault = catalogIds.filterNot { it in TILES_OFF_BY_DEFAULT }
        val savedOrder = appSettings.preferredTileOrder ?: appSettings.tileOrder.takeIf { it.isNotEmpty() }
        val (selection, snapshot) = resolveTileOrder(
            savedOrder, appSettings.defaultTileSnapshot, currentDefault
        )
        // Add the new control to existing nonempty panels once, preserving later deselection.
        val order = if (!preferences.getBoolean("bypass_tile_added", false)) {
            preferences.edit().putBoolean("bypass_tile_added", true).apply()
            if (selection.isNotEmpty()) selection + "bypass_charge_enabled" else selection
        } else selection
        val haloOrder = if (!preferences.getBoolean("halo_tile_added", false)) {
            preferences.edit().putBoolean("halo_tile_added", true).apply()
            if (order.isNotEmpty()) order + "halo" else order
        } else order
        val filteredOrder = haloOrder.filter { it in catalogIds }.distinct()
        if (appSettings.preferredTileOrder != filteredOrder) {
            appSettings.preferredTileOrder = filteredOrder
        }
        if (appSettings.defaultTileSnapshot != snapshot) appSettings.defaultTileSnapshot = snapshot
        return filteredOrder
    }

    private fun clearBackgroundProcesses() {
        val activityManager: ActivityManager = context.getSystemService(ActivityManager::class.java)
        val runningApps = activityManager.runningAppProcesses ?: return
        var memoryBoosted = false
        // A process name may contain ":service", and shared processes may host
        // multiple packages. The ActivityManager API expects a package name.
        val protectedPackages = runningApps
            .filter { it.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND }
            .flatMap { it.pkgList?.toList().orEmpty() }
            .toSet() + context.packageName
        runningApps
            .filter { it.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND }
            .flatMap { it.pkgList?.toList().orEmpty() }
            .distinct()
            .filterNot { it in protectedPackages }
            .forEach { packageName ->
                activityManager.killBackgroundProcesses(packageName)
                memoryBoosted = true
            }
        if (memoryBoosted)
            Toast.makeText(context, context.getString(R.string.game_memory_boosted), Toast.LENGTH_SHORT).show()
    }

    fun updateTileSelection(selectedIds: List<String>) {
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

    private fun toggleSecure(key: String) {
        Settings.Secure.putIntForUser(
            context.contentResolver, key, if (secureEnabled(key)) 0 else 1, UserHandle.USER_CURRENT
        )
        refreshPlatformStates()
    }

    private fun openSettings(action: String) {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) != null) context.startActivity(intent)
    }

    private fun platformTile(feature: String, iconRes: Int, fallbackLabel: String): PlatformTile {
        // These hooks describe different controls/settings on this ROM. Read and write
        // the actual settings, and observe them so changes from Settings/QS stay in sync.
        val setting = when (feature) {
            AxPlatformClient.FEATURE_DREAM -> Settings.Secure.SCREENSAVER_ENABLED
            AxPlatformClient.FEATURE_SMART_PIXELS -> Settings.Secure.SMART_PIXEL_FILTER_ENABLED
            else -> null
        }
        val observedSetting = when (feature) {
            AxPlatformClient.FEATURE_AOD -> Settings.Secure.DOZE_ALWAYS_ON
            AxPlatformClient.FEATURE_NIGHT_LIGHT -> Settings.Secure.NIGHT_DISPLAY_ACTIVATED
            AxPlatformClient.FEATURE_COLOR_INVERSION ->
                Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED
            AxPlatformClient.FEATURE_COLOR_CORRECTION ->
                Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED
            AxPlatformClient.FEATURE_REDUCE_BRIGHTNESS -> Settings.Secure.REDUCE_BRIGHT_COLORS_ACTIVATED
            AxPlatformClient.FEATURE_ONE_HANDED_MODE -> Settings.Secure.ONE_HANDED_MODE_ENABLED
            else -> setting
        }
        val tile = PlatformTile(
            id = feature,
            icon = iconRes,
            feature = feature,
            platform = platform,
            usePlatformLabel = feature != AxPlatformClient.FEATURE_MOBILE_DATA &&
                feature != AxPlatformClient.FEATURE_CAFFEINE &&
                feature != AxPlatformClient.FEATURE_DREAM,
            readActive = when (feature) {
                AxPlatformClient.FEATURE_AUTO_SYNC -> { {
                    ContentResolver.getMasterSyncAutomaticallyAsUser(ActivityManager.getCurrentUser())
                } }
                AxPlatformClient.FEATURE_ZEN -> { {
                    Settings.Global.getInt(context.contentResolver, Settings.Global.ZEN_MODE, 0) != 0
                } }
                AxPlatformClient.FEATURE_FLASHLIGHT -> { {
                    Settings.Secure.getIntForUser(
                        context.contentResolver, Settings.Secure.FLASHLIGHT_ENABLED,
                        if (platform.getState(feature).getBoolean("active", false)) 1 else 0,
                        UserHandle.USER_SYSTEM
                    ) == 1
                } }
                AxPlatformClient.FEATURE_DARK_MODE -> { {
                    context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                        Configuration.UI_MODE_NIGHT_YES
                } }
                AxPlatformClient.FEATURE_READING_MODE -> { {
                    feature in supportedFeatures &&
                        lineageHardware.get(LineageHardwareManager.FEATURE_READING_ENHANCEMENT)
                } }
                else -> observedSetting?.let { { secureEnabled(it) } }
            },
            toggleAction = {
                onBeforePlatformAction?.invoke(feature)
                when (feature) {
                    AxPlatformClient.FEATURE_VPN, AxPlatformClient.FEATURE_CAST -> {
                        if (platform.getState(feature).getBoolean("enabled", false)) {
                            platform.toggle(feature)
                        } else {
                            openSettings(if (feature == AxPlatformClient.FEATURE_VPN)
                                Settings.ACTION_VPN_SETTINGS else Settings.ACTION_CAST_SETTINGS)
                        }
                    }
                    AxPlatformClient.FEATURE_SCREENSHOT -> {
                        stateHandler.postDelayed({
                            if (!listening) return@postDelayed
                            screenshotHelper.takeScreenshot(
                                WindowManager.TAKE_SCREENSHOT_FULLSCREEN,
                                WindowManager.ScreenshotSource.SCREENSHOT_OTHER,
                                stateHandler
                            ) { onScreenshotComplete?.invoke() }
                        }, 500)
                    }
                    AxPlatformClient.FEATURE_AUTO_SYNC -> {
                        val userId = ActivityManager.getCurrentUser()
                        ContentResolver.setMasterSyncAutomaticallyAsUser(
                            !ContentResolver.getMasterSyncAutomaticallyAsUser(userId), userId
                        )
                        refreshPlatformStates()
                    }
                    else -> if (setting != null) toggleSecure(setting) else platform.toggle(feature)
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
            AxPlatformClient.FEATURE_AIRPLANE_MODE,
            R.drawable.materialsymbols_ic_flight_rounded_filled,
            context.getString(R.string.tile_airplane_mode),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_FLASHLIGHT,
            R.drawable.materialsymbols_ic_flashlight_on_rounded_filled,
            context.getString(R.string.tile_flashlight),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_DARK_MODE,
            R.drawable.materialsymbols_ic_dark_mode_rounded_filled,
            context.getString(R.string.tile_dark_mode),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_LOCATION,
            R.drawable.materialsymbols_ic_location_on_rounded_filled,
            context.getString(R.string.tile_location),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_BATTERY_SAVER,
            R.drawable.materialsymbols_ic_battery_saver_rounded_filled,
            context.getString(R.string.tile_battery_saver),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_HOTSPOT,
            R.drawable.materialsymbols_ic_wifi_tethering_rounded_filled,
            context.getString(R.string.tile_hotspot),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_NFC,
            R.drawable.materialsymbols_ic_nfc_rounded_filled,
            context.getString(R.string.tile_nfc),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_NIGHT_LIGHT,
            R.drawable.materialsymbols_ic_nights_stay_rounded_filled,
            context.getString(R.string.tile_night_light),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_AOD,
            R.drawable.materialsymbols_ic_aod_rounded_filled,
            context.getString(R.string.tile_aod),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_DATA_SAVER,
            R.drawable.materialsymbols_ic_data_saver_on_rounded_filled,
            context.getString(R.string.tile_data_saver),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_COLOR_INVERSION,
            R.drawable.materialsymbols_ic_invert_colors_rounded_filled,
            context.getString(R.string.tile_color_inversion),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_COLOR_CORRECTION,
            R.drawable.materialsymbols_ic_palette_rounded_filled,
            context.getString(R.string.tile_color_correction),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_REDUCE_BRIGHTNESS,
            R.drawable.materialsymbols_ic_brightness_low_rounded_filled,
            context.getString(R.string.tile_reduce_brightness),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_ONE_HANDED_MODE,
            R.drawable.materialsymbols_ic_phone_android_rounded_filled,
            context.getString(R.string.tile_one_handed),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_HEADS_UP,
            R.drawable.materialsymbols_ic_notifications_active_rounded_filled,
            context.getString(R.string.tile_heads_up),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_AUTO_SYNC,
            R.drawable.materialsymbols_ic_sync_rounded_filled,
            context.getString(R.string.tile_auto_sync),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_CAMERA_PRIVACY,
            R.drawable.materialsymbols_ic_photo_camera_rounded_filled,
            context.getString(R.string.tile_camera_privacy),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_MIC_PRIVACY,
            R.drawable.materialsymbols_ic_mic_rounded_filled,
            context.getString(R.string.tile_mic_privacy),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_WORK_PROFILE,
            R.drawable.materialsymbols_ic_work_rounded_filled,
            context.getString(R.string.tile_work_profile),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_USB_TETHER,
            R.drawable.materialsymbols_ic_usb_rounded_filled,
            context.getString(R.string.tile_usb_tether),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_DREAM,
            R.drawable.materialsymbols_ic_bedtime_rounded_filled,
            context.getString(R.string.tile_dream),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_READING_MODE,
            R.drawable.materialsymbols_ic_menu_book_rounded_filled,
            context.getString(R.string.tile_reading_mode),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_POWER_SHARE,
            R.drawable.materialsymbols_ic_battery_charging_full_rounded_filled,
            context.getString(R.string.tile_power_share),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_CAFFEINE,
            R.drawable.materialsymbols_ic_local_cafe_rounded_filled,
            context.getString(R.string.tile_caffeine),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_VPN,
            R.drawable.materialsymbols_ic_vpn_key_rounded_filled,
            context.getString(R.string.tile_vpn),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_CAST,
            R.drawable.materialsymbols_ic_cast_rounded_filled,
            context.getString(R.string.tile_cast),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_PROFILES,
            R.drawable.materialsymbols_ic_manage_accounts_rounded_filled,
            context.getString(R.string.tile_profiles),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_SMART_PIXELS,
            R.drawable.materialsymbols_ic_grid_on_rounded_filled,
            context.getString(R.string.tile_smart_pixels),
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
                label = context.getString(R.string.tile_danmaku),
                icon = R.drawable.materialsymbols_ic_notifications_rounded_filled,
                state = notificationState,
                setter = { gameSession.setDanmakuNotification(it) }
            )
        )

        add(
            ToggleableTile(
                id = "bypass_charge_enabled",
                label = context.getString(R.string.bypass_charge_enabled_title),
                icon = R.drawable.materialsymbols_ic_battery_charging_full_rounded_filled,
                state = bypassChargeState,
                setter = { gameSession.setBypassChargeEnabled(it) }
            )
        )

        add(
            ToggleableTile(
                id = "stay_awake",
                label = context.getString(R.string.tile_stay_awake),
                icon = R.drawable.materialsymbols_ic_bedtime_rounded_filled,
                state = systemSettings.stayAwakeState,
                setter = {
                    appSettings.stayAwake = it
                    systemSettings.stayAwake = it
                }
            )
        )

        add(
            ToggleableTile(
                id = "fps_info",
                label = context.getString(R.string.tile_fps_info),
                icon = R.drawable.materialsymbols_ic_bar_chart_rounded_filled,
                state = isFpsInfoVisible,
                setter = { setFpsInfoEnabled(it) }
            )
        )

        add(
            FixedActionTile(
                id = "screen_off",
                label = context.getString(R.string.tile_screen_off),
                icon = R.drawable.materialsymbols_ic_screen_off_rounded_filled,
                description = context.getString(R.string.tile_screen_off_description),
                action = { onScreenOff?.invoke() },
            )
        )

        add(
            FixedActionTile(
                id = "boost_memory",
                label = context.getString(R.string.tile_boost_memory),
                icon = R.drawable.materialsymbols_ic_speed_rounded_filled,
                action = {
                    clearBackgroundProcesses()
                }
            )
        )

        add(
            FixedActionTile(
                id = "settings",
                label = context.getString(R.string.tile_settings),
                icon = R.drawable.materialsymbols_ic_settings_rounded_filled,
                action = {
                    val intent = Intent(Settings.ACTION_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                }
            )
        )

        if (SystemProperties.getBoolean("persist.sys.ax_touch_boost", false)) {
            add(
                ToggleableTile(
                    id = "touch_boost",
                    label = context.getString(R.string.tile_touch_boost),
                    icon = R.drawable.materialsymbols_ic_touch_app_rounded_filled,
                    state = touchBoostState,
                    setter = {
                        val newVal = if (it) 1 else 0
                        SystemProperties.set("persist.sys.touchboost_enable", "$newVal")
                        touchBoostState.value = it
                    }
                )
            )
        }
    }
}
