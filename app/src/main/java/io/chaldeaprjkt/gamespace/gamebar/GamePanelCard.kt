/*
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
@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalAnimationApi::class, ExperimentalFoundationApi::class)

package io.chaldeaprjkt.gamespace.gamebar

import android.app.*
import android.content.*
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.android.internal.policy.DesktopModeCompatPolicy
import com.android.settingslib.display.BrightnessUtils.*
import androidx.annotation.StringRes
import androidx.collection.LruCache
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import io.chaldeaprjkt.gamespace.R
import io.chaldeaprjkt.gamespace.data.SystemSettings
import io.chaldeaprjkt.gamespace.gamebar.brightness.BrightnessInteractor
import io.chaldeaprjkt.gamespace.gamebar.fps.FpsInteractor
import io.chaldeaprjkt.gamespace.gamebar.tiles.*
import io.chaldeaprjkt.gamespace.settings.SettingsActivity
import io.chaldeaprjkt.gamespace.utils.GameModeUtils
import io.github.miner7222.gamespace.GameAspectRatioTile
import io.github.miner7222.gamespace.gamebar.MediaVolumeController
import io.github.miner7222.gamespace.gamebar.RecordingPhase
import io.github.miner7222.gamespace.gamebar.RecordingState
import io.github.miner7222.gamespace.library.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

private const val TAG = "GamePanelCard"

@Composable
fun GamePanelCard(
    interactor: BrightnessInteractor,
    fpsInteractor: FpsInteractor,
    apps: List<AppInfo>,
    gameModeUtils: GameModeUtils,
    systemSettings: SystemSettings,
    tileRepository: TileRepository,
    mediaVolume: MediaVolumeController,
    gamePackage: String,
    onDismiss: () -> Unit,
    panelWidth: Dp,
    maxHeight: Dp,
    initialGraphExpanded: Boolean = false,
    onEditingChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val tablet = config.smallestScreenWidthDp >= 600
    val compact = !tablet && config.orientation == Configuration.ORIENTATION_LANDSCAPE
    val mainCount = if (tablet) 12 else 8
    val columns = if (tablet) 3 else 4
    val px = if (compact) 12.dp else 16.dp
    val tileHeight = if (tablet) 64.dp else if (compact)
        ((maxHeight - 202.dp) / 2).coerceIn(48.dp, 60.dp) else 72.dp
    var editing by remember { mutableStateOf(false) }
    val editingChanged by rememberUpdatedState(onEditingChanged)
    LaunchedEffect(editing) { editingChanged(editing) }
    var graphExpanded by remember { mutableStateOf(initialGraphExpanded) }
    var moreExpanded by remember { mutableStateOf(false) }
    var dialogTile by remember { mutableStateOf<TileAction?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val store = remember(context) { InGameOptions(context) }
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(store) {
        val observation = store.observe { revision++ }
        onDispose { observation.close() }
    }
    val selectedMode = remember(revision, gamePackage) {
        fromSystemGameMode(SessionOptions.mode(context) ?: gameModeUtils.activeGame?.mode
            ?: systemSettings.userGames.firstOrNull { it.packageName == gamePackage }?.mode ?: 1)
    }
    val openSettings: (GameSettingsDestination) -> Unit = { target ->
        onDismiss()
        context.startActivity(LibraryActivity.gameSettingsIntent(context, gamePackage, target))
    }
    val message: (String) -> Unit = { text -> scope.launch {
        snackbar.currentSnackbarData?.dismiss()
        snackbar.showSnackbar(text)
    } }
    val aspect = tileRepository.catalogue.filterIsInstance<GameAspectRatioTile>().firstOrNull()
    DisposableEffect(aspect) {
        val listener: (String) -> Unit = { message(it) }
        aspect?.onFailureMessage = listener
        onDispose { aspect?.let { if (it.onFailureMessage === listener) it.onFailureMessage = null } }
    }
    val noneMessage = stringResource(R.string.dashboard_modes_off)
    val settingsLabel = stringResource(R.string.tile_settings)
    val noneAction = { scope.launch {
        snackbar.currentSnackbarData?.dismiss()
        if (snackbar.showSnackbar(noneMessage, settingsLabel, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
            openSettings(GameSettingsDestination.MODE)
        }
    }; Unit }
    LaunchedEffect(tileRepository) {
        tileRepository.actionResults.collect { completion -> message(completion.result.message) }
    }
    val activateTile: (TileAction) -> Unit = { tile ->
        val wasEnabled = tile.isEnabled
        if (tile.id == "screen_record" && tileRepository.recordingState.value.phase == RecordingPhase.ACTIVE) {
            tileRepository.stopRecording()
        } else tile.toggle()
        if (tile.options.isNotEmpty()) dialogTile = tile
        if (tile.id == "gesture_lock" && !wasEnabled && tile.isEnabled) {
            message(context.getString(R.string.dashboard_gesture_on))
        }
    }
    val recording by tileRepository.recordingState.collectAsState()
    val tiles = tileRepository.tiles
    val scroll = rememberScrollState()
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Surface(Modifier.width(panelWidth).height(maxHeight), shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 2.dp) {
            Box(Modifier.fillMaxSize()) {
                if (editing) {
                    TileEditPanel(tileRepository, apps, mainCount, columns, tablet, compact,
                        onClose = { editing = false }, onMessage = message,
                        onEditApps = {
                            onDismiss()
                            context.startActivity(Intent(context, SettingsActivity::class.java)
                                .putExtra(SettingsActivity.EXTRA_EDIT_QUICK_APPS, true)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        })
                } else Column(Modifier.fillMaxSize()) {
                    if (!tablet && !compact) SheetGrip()
                    DashboardHeader(gamePackage, fpsInteractor, graphExpanded, compact,
                        onGraph = { graphExpanded = !graphExpanded }, onEdit = { editing = true },
                        onSettings = { openSettings(GameSettingsDestination.IN_GAME_OPTIONS) })
                    DashboardModes(selectedMode, compact, Modifier.padding(horizontal = px).padding(top = if (compact) 4.dp else 8.dp),
                        onNone = noneAction, onMode = { SessionOptions.chooseMode(context, it.toSystemGameMode()) })
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)
                        .padding(horizontal = px).padding(top = if (compact) 6.dp else 10.dp, bottom = 12.dp)) {
                        if (graphExpanded) FpsGraph(Modifier.padding(bottom = 10.dp), fpsInteractor)
                        DashboardSliders(interactor, mediaVolume, compact, onOutput = {
                            onDismiss()
                            mediaVolume.openOutputSwitcher()
                        })
                        Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
                        DashboardGrid(tiles.take(mainCount), columns, tablet, compact, tileHeight, recording,
                            onTile = activateTile,
                            onDefault = { openSettings(GameSettingsDestination.IN_GAME_OPTIONS) })
                        if (tiles.size > mainCount) {
                            HorizontalDivider(Modifier.padding(top = 6.dp))
                            Row(Modifier.fillMaxWidth().height(48.dp).clickable { moreExpanded = !moreExpanded }
                                .semantics { stateDescription = context.getString(if (moreExpanded) R.string.dashboard_expanded else R.string.dashboard_collapsed) }
                                .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.dashboard_more), style = MaterialTheme.typography.labelLarge)
                                Spacer(Modifier.width(8.dp))
                                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                                    Text((tiles.size - mainCount).toString(), Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall)
                                }
                                Spacer(Modifier.weight(1f))
                                Icon(if (moreExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                            }
                            if (moreExpanded) {
                                Spacer(Modifier.height(if (compact) 6.dp else 8.dp))
                                DashboardGrid(tiles.drop(mainCount), columns, tablet, compact, tileHeight, recording,
                                    onTile = activateTile,
                                    onDefault = { openSettings(GameSettingsDestination.IN_GAME_OPTIONS) })
                            }
                        }
                        if (apps.isNotEmpty()) QuickStartAppSidebar(apps, compact, message, onDismiss)
                    }
                }
                dialogTile?.let { tile -> DashboardOptionSheet(tile, onDismiss = { dialogTile = null }) }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(8.dp))
            }
        }
    }
}

@Composable
private fun DashboardHeader(packageName: String, fps: FpsInteractor, expanded: Boolean, compact: Boolean,
    onGraph: () -> Unit, onEdit: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val app = remember(packageName) { runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        info.loadLabel(context.packageManager).toString() to info.loadIcon(context.packageManager)
    }.getOrElse { packageName to null } }
    val history by fps.fpsHistory.collectAsState()
    val battery = rememberBatteryInfo()
    val time = rememberCurrentTime()
    val network = rememberNetworkTransport()
    Row(Modifier.fillMaxWidth().height(if (compact) 48.dp else 56.dp)
        .padding(start = if (compact) 12.dp else 16.dp, end = 4.dp, top = if (compact) 6.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 12.dp)) {
        Image(rememberDrawablePainter(app.second), null, Modifier.size(if (compact) 32.dp else 40.dp).clip(RoundedCornerShape(10.dp)))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(app.first, Modifier.weight(1f), fontSize = if (compact) 14.sp else 16.sp,
                    lineHeight = if (compact) 18.sp else 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.width(58.dp).height(if (compact) 22.dp else 24.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (expanded) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable(onClick = onGraph).semantics {
                        contentDescription = context.getString(R.string.dashboard_fps_graph, history.lastOrNull()?.roundToInt() ?: 0)
                        stateDescription = context.getString(if (expanded) R.string.dashboard_expanded else R.string.dashboard_collapsed)
                    }, contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.dashboard_fps, history.lastOrNull()?.roundToInt() ?: 0),
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            color = if (expanded) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"))
                        Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.size(16.dp),
                            tint = if (expanded) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)) {
                Text(time, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
                Icon(when (network) { 1 -> Icons.Rounded.Wifi; 2 -> Icons.Rounded.SignalCellularAlt; else -> Icons.Rounded.SignalWifiOff },
                    stringResource(when (network) { 1 -> R.string.dashboard_wifi; 2 -> R.string.dashboard_mobile; else -> R.string.dashboard_offline }),
                    Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    Icon(Icons.Rounded.BatteryFull, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (battery.level >= 0) "${battery.level}%" else "–", fontSize = 12.sp, lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    Icon(Icons.Rounded.Thermostat, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.dashboard_temperature, battery.temperatureC.roundToInt()), fontSize = 12.sp,
                        lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 4.dp)) {
            IconButton(onClick = onEdit, modifier = Modifier.size(if (compact) 40.dp else 44.dp)) {
                Icon(Icons.Rounded.Edit, stringResource(R.string.dashboard_edit), Modifier.size(20.dp))
            }
            IconButton(onClick = onSettings, modifier = Modifier.size(if (compact) 40.dp else 44.dp)) {
                Icon(Icons.Rounded.Settings, stringResource(R.string.library_game_settings), Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun rememberNetworkTransport(): Int {
    val context = LocalContext.current
    val connectivity = remember(context) { context.getSystemService(ConnectivityManager::class.java) }
    var transport by remember { mutableIntStateOf(0) }
    DisposableEffect(connectivity) {
        fun refresh() {
            val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            transport = when {
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> 1
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> 2
                else -> 0
            }
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refresh()
            override fun onLost(network: Network) = refresh()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh()
        }
        refresh()
        connectivity.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
        onDispose { connectivity.unregisterNetworkCallback(callback) }
    }
    return transport
}

@Composable
private fun DashboardModes(selected: GameMode, compact: Boolean, modifier: Modifier,
    onNone: () -> Unit, onMode: (GameMode) -> Unit) {
    val modes = listOf(GameMode.PowerSave, GameMode.Balanced, GameMode.Performance)
    val none = selected == GameMode.None
    val context = LocalContext.current
    val explanation = stringResource(R.string.dashboard_modes_off)
    Row(modifier.fillMaxWidth().height(if (compact) 40.dp else 48.dp)
        .then(if (none) Modifier.semantics(mergeDescendants = true) { stateDescription = explanation }
            .clickable(role = Role.Button, onClickLabel = explanation, onClick = onNone) else Modifier.selectableGroup()),
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        modes.forEachIndexed { index, mode ->
            val checked = !none && selected == mode
            val shape = if (checked) RoundedCornerShape(24.dp) else when (index) {
                0 -> RoundedCornerShape(24.dp, 8.dp, 8.dp, 24.dp)
                2 -> RoundedCornerShape(8.dp, 24.dp, 24.dp, 8.dp)
                else -> RoundedCornerShape(8.dp)
            }
            Surface(Modifier.weight(1f).fillMaxHeight().then(if (!none)
                Modifier.selectable(checked, role = Role.RadioButton, onClick = { onMode(mode) }) else Modifier), shape = shape,
                color = when { none -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                    checked -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.secondaryContainer },
                contentColor = when { none -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    checked -> MaterialTheme.colorScheme.onPrimary; else -> MaterialTheme.colorScheme.onSecondaryContainer }) {
                Box(contentAlignment = Alignment.Center) { Text(context.getString(mode.displayNameRes), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun DashboardAuxButton(compact: Boolean, secondRow: Boolean, selected: Boolean?, enabled: Boolean,
    label: String, icon: ImageVector, onClick: () -> Unit) {
    val context = LocalContext.current
    val outerOffset = if (!compact) 0.dp else if (secondRow) 2.dp else (-2).dp
    val innerOffset = -outerOffset
    val shape = RoundedCornerShape(if (selected == true) 22.dp else if (compact) 14.dp else 16.dp)
    Box(Modifier.size(if (compact) 40.dp else 48.dp)) {
        Box(Modifier.requiredSize(48.dp).offset(y = outerOffset)
            .then(if (selected != null) Modifier.toggleable(selected, enabled, Role.Switch) { onClick() }
                else Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick))
            .semantics {
                contentDescription = label
                if (selected != null) stateDescription = context.getString(if (selected) R.string.dashboard_on else R.string.dashboard_off)
            }, contentAlignment = Alignment.Center) {
            Box(Modifier.offset(y = innerOffset).size(if (compact) 40.dp else 48.dp).clip(shape)
                .background(if (selected == true) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(22.dp),
                    tint = (if (selected == true) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                        .copy(alpha = if (enabled) 1f else 0.38f))
            }
        }
    }
}

@Composable
private fun DashboardSliders(brightness: BrightnessInteractor, media: MediaVolumeController, compact: Boolean,
    onOutput: () -> Unit) {
    val info by brightness.brightnessInfo.collectAsState()
    val auto by brightness.isAuto.collectAsState()
    val volume by media.state.collectAsState()
    val context = LocalContext.current
    val value = (info?.let { convertLinearToGammaFloat(it.brightness, it.brightnessMinimum, it.brightnessMaximum)
        .toFloat() / GAMMA_SPACE_MAX } ?: 0f).coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 2.dp)) {
        Row(Modifier.height(if (compact) 40.dp else 48.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)) {
            DashboardSlider(value, info != null, stringResource(R.string.dashboard_brightness), Icons.Rounded.BrightnessMedium,
                compact, Modifier.weight(1f)) { brightness.onUserInteracted(); brightness.setBrightness(it) }
            DashboardAuxButton(compact, secondRow = false, selected = auto == true, enabled = info != null,
                label = stringResource(R.string.dashboard_auto_brightness), icon = Icons.Rounded.BrightnessAuto,
                onClick = { brightness.toggleAutoMode() })
        }
        Row(Modifier.height(if (compact) 40.dp else 48.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)) {
            val span = (volume.maximum - volume.minimum).coerceAtLeast(1)
            DashboardSlider((volume.current - volume.minimum).toFloat() / span, volume.available && !volume.fixed,
                stringResource(R.string.dashboard_media_volume), Icons.Rounded.VolumeUp, compact, Modifier.weight(1f)) {
                media.setVolume(volume.minimum + (it * span).roundToInt())
            }
            DashboardAuxButton(compact, secondRow = true, selected = null, enabled = true,
                label = stringResource(R.string.dashboard_media_output), icon = Icons.Rounded.Speaker, onClick = onOutput)
        }
    }
}

@Composable
private fun DashboardSlider(value: Float, enabled: Boolean, label: String, icon: ImageVector,
    compact: Boolean, modifier: Modifier, onValue: (Float) -> Unit) {
    val color = MaterialTheme.colorScheme
    val currentValue by rememberUpdatedState(onValue)
    val density = LocalDensity.current
    val leading = with(density) { (if (compact) 34.dp else 40.dp).toPx() }
    val end = with(density) { 8.dp.toPx() }
    var width by remember { mutableFloatStateOf(1f) }
    fun updateValue(x: Float) {
        if (enabled) currentValue(((x - leading) / (width - leading - end).coerceAtLeast(1f)).coerceIn(0f, 1f))
    }
    Box(modifier.height(if (compact) 40.dp else 48.dp).onSizeChanged { width = it.width.toFloat() }
        .semantics {
            contentDescription = label
            progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
            if (!enabled) disabled()
            setProgress { if (enabled) currentValue(it.coerceIn(0f, 1f)); enabled }
        }
        .focusable(enabled).onKeyEvent {
            if (enabled && it.type == KeyEventType.KeyDown && it.key in listOf(Key.DirectionLeft, Key.DirectionRight)) {
                currentValue((value + if (it.key == Key.DirectionRight) 0.05f else -0.05f).coerceIn(0f, 1f)); true
            } else false
        }
        .pointerInput(enabled, leading) { detectTapGestures { updateValue(it.x) } }
        .pointerInput(enabled, leading) { detectHorizontalDragGestures { event, _ -> event.consume(); updateValue(event.position.x) } }) {
        Canvas(Modifier.fillMaxSize()) {
            val knob = leading + value * (size.width - leading - end).coerceAtLeast(0f)
            val track = (if (compact) 28.dp else 40.dp).toPx()
            val top = (size.height - track) / 2
            val active = if (enabled) color.primary else color.onSurface.copy(alpha = 0.38f)
            drawRoundRect(color.surfaceContainerHighest, Offset(0f, top), Size(size.width, track), CornerRadius(track / 2))
            drawRoundRect(active, Offset(0f, top), Size((knob - 6.dp.toPx()).coerceAtLeast(0f), track), CornerRadius(track / 2))
            drawRoundRect(active, Offset(knob - 2.dp.toPx(), 0f), Size(4.dp.toPx(), size.height), CornerRadius(2.dp.toPx()))
        }
        Icon(icon, null, Modifier.align(Alignment.CenterStart).padding(start = if (compact) 8.dp else 11.dp)
            .size(if (compact) 18.dp else 20.dp), tint = color.onPrimary.copy(alpha = if (enabled) 1f else 0.6f))
    }
}

@Composable
private fun DashboardGrid(tiles: List<TileAction>, columns: Int, tablet: Boolean, compact: Boolean,
    height: Dp, recording: RecordingState, onTile: (TileAction) -> Unit, onDefault: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)) {
        tiles.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)) {
                row.forEach { tile -> key(tile.id) {
                    TileButton(tile, Modifier.weight(1f), tablet, compact, height, recording,
                        onClick = { onTile(tile) }, onDefault = onDefault)
                } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun TileButton(tile: TileAction, modifier: Modifier = Modifier, tablet: Boolean = false,
    compact: Boolean = false, height: Dp = 72.dp, recording: RecordingState = RecordingState(),
    onClick: () -> Unit = { tile.toggle() }, onDefault: () -> Unit = {}) {
    val enabled by tile.observeEnabled()
    val changed = tile.differsFromSaved
    // A set aspect ratio reads as on and names the ratio alone; full screen reads as off.
    val ratio = tile.id == "screen_aspect_ratio"
    val active = (tile.kind == TileKind.TOGGLE || ratio) && enabled
    val record = tile.id == "screen_record" && recording.phase == RecordingPhase.ACTIVE
    val color = MaterialTheme.colorScheme
    val background by animateColorAsState(if (record) color.errorContainer else if (active) color.primaryContainer else color.surfaceContainerHighest)
    val foreground = if (record) color.onErrorContainer else if (active) color.onPrimaryContainer else color.onSurfaceVariant
    val radius by animateDpAsState(if (active || record) 22.dp else 16.dp)
    val value = if (tile.id == "screen_record") when (recording.phase) {
        RecordingPhase.ACTIVE -> recording.elapsedMillis?.let { "%d:%02d".format(Locale.getDefault(), it / 60000, it / 1000 % 60) }
            ?: stringResource(R.string.dashboard_elapsed_unknown)
        RecordingPhase.STARTING -> stringResource(R.string.dashboard_record_starting)
        else -> null
    } else if (!ratio && (tile.kind == TileKind.CYCLE || tile.kind == TileKind.DIALOG)) tile.valueLine else null
    val label = if (record) stringResource(R.string.dashboard_recording)
        else tile.label
    val actionLabel = if (record) stringResource(R.string.dashboard_stop_recording) else null
    val context = LocalContext.current
    val defaultLabel = stringResource(R.string.dashboard_game_default)
    Box(modifier.height(height).clip(RoundedCornerShape(radius)).background(background)
        .combinedClickable(onClick = onClick, onClickLabel = actionLabel, onLongClickLabel = defaultLabel,
            onLongClick = if (tile.isSessionTile) onDefault else null)
        .semantics(mergeDescendants = true) {
            contentDescription = listOfNotNull(label, value).joinToString(", ")
            if (tile.kind == TileKind.TOGGLE) toggleableState = if (enabled) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off
            role = if (tile.kind == TileKind.TOGGLE) Role.Switch else Role.Button
            stateDescription = listOfNotNull(
                if (tile.kind == TileKind.TOGGLE) context.getString(if (enabled) R.string.dashboard_on else R.string.dashboard_off) else null,
                if (changed) context.getString(R.string.dashboard_session_changed) else null).joinToString(", ")
        }, contentAlignment = Alignment.Center) {
        val text: @Composable () -> Unit = {
            Column(horizontalAlignment = if (tablet) Alignment.Start else Alignment.CenterHorizontally) {
                // The name leads and the state line stays a step smaller and lighter.
                Text(label, fontSize = if (compact) 11.sp else if (tablet) 13.sp else 12.sp,
                    lineHeight = if (compact) 13.sp else if (tablet) 16.sp else 14.sp,
                    color = foreground, fontWeight = if (active || record) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    // Wrap Korean names between words, never inside one.
                    style = LocalTextStyle.current.copy(lineBreak = LineBreak.Heading),
                    textAlign = if (tablet) TextAlign.Start else TextAlign.Center)
                if (!value.isNullOrBlank()) Text(value, fontSize = if (compact && height < 55.dp) 9.sp
                    else if (compact || !tablet) 10.sp else 11.sp, fontWeight = FontWeight.Normal,
                    lineHeight = if (compact && height < 55.dp) 11.sp else if (compact || !tablet) 12.sp else 14.sp,
                    color = if (record) foreground else color.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = if (record) "tnum" else null),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (tablet) Row(Modifier.fillMaxSize().padding(start = 10.dp, end = if (tile.kind == TileKind.DIALOG) 22.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (record) Icon(Icons.Rounded.Stop, null, Modifier.size(24.dp), foreground)
            else Icon(painterResource(tile.icon), null, Modifier.size(24.dp), foreground)
            Box(Modifier.weight(1f)) { text() }
        } else Column(Modifier.padding(horizontal = 3.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (record) Icon(Icons.Rounded.Stop, null, Modifier.size(if (compact && height < 55.dp) 20.dp else 24.dp), foreground)
            else Icon(painterResource(tile.icon), null, Modifier.size(if (compact && height < 55.dp) 20.dp else 24.dp), foreground)
            text()
        }
        if (tile.kind == TileKind.DIALOG) Icon(Icons.Rounded.ChevronRight, null,
            Modifier.align(if (tablet) Alignment.CenterEnd else Alignment.TopEnd).padding(2.dp).size(16.dp), color.onSurfaceVariant)
        if (changed) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(8.dp).background(color.tertiary, CircleShape))
    }
}

@Composable
private fun BoxScope.DashboardOptionSheet(tile: TileAction, onDismiss: () -> Unit) {
    val title = stringResource(R.string.tile_aspect_ratio)
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.3f)).clickable(onClick = onDismiss))
    Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.8f).semantics { paneTitle = title },
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.tile_aspect_ratio), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dashboard_done)) }
            }
            tile.options.forEach { option ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(option.selected, role = Role.RadioButton,
                    onClick = { tile.selectOption(option); onDismiss() }), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(option.selected, onClick = null)
                    Text(option.label, Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
fun FpsGraph(modifier: Modifier = Modifier, interactor: FpsInteractor) {
    val history by interactor.fpsHistory.collectAsState()
    val maximum by interactor.dynamicMaxFps.collectAsState()
    val color = MaterialTheme.colorScheme.primary
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(20.dp)).padding(12.dp)) {
        val stats = listOf(R.string.dashboard_min to (history.minOrNull()?.roundToInt() ?: 0),
            R.string.dashboard_average to (if (history.isEmpty()) 0 else history.average().roundToInt()),
            R.string.dashboard_max to (history.maxOrNull()?.roundToInt() ?: 0))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            stats.forEach { (label, value) ->
                Column {
                    Text(stringResource(label), fontSize = 11.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value.toString(), fontSize = 18.sp, lineHeight = 22.sp,
                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"))
                }
            }
        }
        Canvas(Modifier.fillMaxWidth().height(52.dp).padding(top = 6.dp)) {
            if (history.size > 1) {
                val path = Path()
                history.forEachIndexed { i, value ->
                    val x = i.toFloat() / (history.size - 1) * size.width
                    val y = size.height * (1f - (value / maximum.coerceAtLeast(1f)).coerceIn(0f, 1f))
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                val fill = Path().apply {
                    addPath(path)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.34f), Color.Transparent)))
                drawPath(path, color, style = Stroke(2.5.dp.toPx()))
            }
        }
    }
}

@Composable
fun QuickStartAppSidebar(apps: List<AppInfo>, compact: Boolean = false,
    onMessage: (String) -> Unit = {}, onLaunched: () -> Unit = {}) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(top = 6.dp).height(56.dp)
        .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(28.dp))
        .horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!compact) Text(stringResource(R.string.dashboard_apps), Modifier.padding(horizontal = 8.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        apps.forEach { app ->
            Box(Modifier.size(52.dp).clip(CircleShape).clickable {
                launchAppInFreeformMode(context, app.packageName,
                    onFailure = { onMessage(context.getString(R.string.floating_window_unavailable)) }, onLaunched = onLaunched)
            }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                    Image(rememberDrawablePainter(app.icon), app.name, Modifier.size(30.dp).clip(CircleShape))
                }
            }
        }
    }
}

@Composable
fun TileEditPanel(repository: TileRepository, apps: List<AppInfo>, mainCount: Int, columns: Int,
    tablet: Boolean, compact: Boolean, onClose: () -> Unit, onMessage: (String) -> Unit, onEditApps: () -> Unit) {
    val context = LocalContext.current
    val drag = remember(repository, columns) { TileDragDropState(repository.tiles.toList(), columns) }
    var gridOrigin by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(Rect.Zero) }
    val editorScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val edge = with(density) { 32.dp.toPx() }
    val step = with(density) { 12.dp.toPx() }
    LaunchedEffect(repository.tiles.toList(), drag.dragInProgress) {
        if (!drag.dragInProgress && drag.tileIds() != repository.tiles.map { it.id }) {
            drag.replaceTiles(repository.tiles.toList())
        }
    }
    fun save() = repository.updateTileSelection(drag.tileIds())
    val px = if (compact) 12.dp else 16.dp
    val viewConfiguration = LocalViewConfiguration.current
    val removeConfiguration = remember(viewConfiguration) {
        object : ViewConfiguration by viewConfiguration {
            override val minimumTouchTargetSize = DpSize(32.dp, 32.dp)
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (!tablet && !compact) SheetGrip()
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = px), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.dashboard_edit), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.dashboard_first_tiles, mainCount), fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(modifier = Modifier.semantics { contentDescription = context.getString(R.string.tile_reset_to_default) }, onClick = {
                repository.resetToDefault()
                drag.replaceTiles(repository.tiles.toList())
                onMessage(context.getString(R.string.dashboard_reset))
            }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(stringResource(R.string.dashboard_reset_short), fontSize = 12.sp) }
            Button(onClick = { save(); onClose() }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                Text(stringResource(R.string.dashboard_done), fontSize = 12.sp)
            }
        }
        Column(Modifier.weight(1f).onGloballyPositioned { viewport = it.boundsInRoot() }
            .verticalScroll(editorScroll).padding(horizontal = px).padding(bottom = 16.dp)) {
            Text(stringResource(R.string.dashboard_in_dashboard), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.dashboard_drag), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.fillMaxWidth().padding(top = 8.dp).onGloballyPositioned { gridOrigin = it.positionInRoot() }
                .dragAndDropActiveGrid(contentOffset = { gridOrigin }, dragDropState = drag,
                    onPointerMoved = { position ->
                        val delta = when {
                            !drag.dragInProgress -> 0f
                            position.y < viewport.top + edge -> -step
                            position.y > viewport.bottom - edge -> step
                            else -> 0f
                        }
                        if (delta != 0f) scope.launch { editorScroll.scrollBy(delta) }
                    }, onDrop = { repository.updateTileSelection(it) })) {
                drag.tiles.chunked(columns).forEachIndexed { rowIndex, row ->
                    if (rowIndex * columns == mainCount) MoreDivider()
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEachIndexed { colIndex, tile -> key(tile.id) {
                            val index = rowIndex * columns + colIndex
                            val title = if (tile.id == "screen_aspect_ratio") stringResource(R.string.tile_aspect_ratio) else tile.label
                            val removeLabel = stringResource(R.string.dashboard_remove, title)
                            Box(Modifier.weight(1f).height(if (tablet) 72.dp else 80.dp)
                                .onGloballyPositioned { coords ->
                                    val offset = coords.positionInRoot() - gridOrigin
                                    drag.updateItemPosition(tile.id, index, IntOffset(offset.x.roundToInt(), offset.y.roundToInt()), coords.size)
                                }.semantics { customActions = buildList {
                                    if (index > 0) add(CustomAccessibilityAction(context.getString(R.string.dashboard_move_earlier)) {
                                        drag.onStarted(tile); drag.onTargeting(index - 1); repository.updateTileSelection(drag.onDrop()); true
                                    })
                                    if (index < drag.tiles.lastIndex) add(CustomAccessibilityAction(context.getString(R.string.dashboard_move_later)) {
                                        drag.onStarted(tile); drag.onTargeting(index + 1); repository.updateTileSelection(drag.onDrop()); true
                                    })
                                    add(CustomAccessibilityAction(removeLabel) { drag.removeTile(tile.id); save(); true })
                                } }) {
                                Box(Modifier.fillMaxSize().padding(top = 8.dp, end = 8.dp)
                                    .clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                    .dragAndDropTileSource(tile, drag), contentAlignment = Alignment.Center) {
                                    if (tablet) Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.DragIndicator, stringResource(R.string.dashboard_drag), Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(title, Modifier.weight(1f).padding(4.dp), fontSize = 12.sp,
                                            lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    } else Column(Modifier.padding(horizontal = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Rounded.DragIndicator, stringResource(R.string.dashboard_drag), Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(title, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 2,
                                            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                                    }
                                }
                                CompositionLocalProvider(LocalViewConfiguration provides removeConfiguration) {
                                    Box(Modifier.align(Alignment.TopEnd).offset(x = 7.dp, y = (-7).dp).size(32.dp).clip(CircleShape)
                                        .clickable(onClickLabel = removeLabel) { drag.removeTile(tile.id); save() }
                                        .semantics { contentDescription = removeLabel }, contentAlignment = Alignment.Center) {
                                        Box(Modifier.size(22.dp).background(MaterialTheme.colorScheme.inverseSurface, CircleShape), contentAlignment = Alignment.Center) {
                                            Icon(Icons.Rounded.Remove, null, Modifier.size(16.dp), MaterialTheme.colorScheme.inverseOnSurface)
                                        }
                                    }
                                }
                            }
                        } }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                if (drag.tiles.size == mainCount) MoreDivider()
                if (drag.tiles.isEmpty()) Text(stringResource(R.string.dashboard_empty), Modifier.padding(vertical = 16.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.dashboard_quick_apps), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.dashboard_floating), style = MaterialTheme.typography.bodySmall)
                }
                FilledTonalButton(onClick = onEditApps) { Text(stringResource(R.string.dashboard_edit_apps)) }
            }
            if (apps.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                apps.forEach { app -> Image(rememberDrawablePainter(app.icon), app.name, Modifier.padding(end = 6.dp).size(32.dp).clip(CircleShape)) }
            }
            Text(stringResource(R.string.dashboard_add), Modifier.padding(top = 20.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall)
            TileCategory.entries.forEach { category ->
                val available = repository.allAvailableTiles.filter { it.category == category }
                if (available.isNotEmpty()) {
                    Text(stringResource(category.labelRes), Modifier.padding(top = 12.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        available.forEach { tile ->
                            val added = drag.tileIds().contains(tile.id)
                            val title = if (tile.id == "screen_aspect_ratio") stringResource(R.string.tile_aspect_ratio) else tile.label
                            if (added) Surface(Modifier.padding(vertical = 4.dp).height(34.dp)
                                .semantics(mergeDescendants = true) { contentDescription = context.getString(R.string.dashboard_already_added, title) },
                                color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(10.dp)) {
                                Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Icon(Icons.Rounded.Check, null, Modifier.size(16.dp))
                                    Text(title, fontSize = 13.sp, maxLines = 1)
                                }
                            } else InputChip(selected = false, onClick = {
                                drag.addTile(DragTileData(tile.id, title, tile.icon)); save()
                                onMessage(context.getString(if (drag.tiles.size <= mainCount) R.string.dashboard_added_main
                                    else R.string.dashboard_added_more, title))
                            }, label = { Text(title, maxLines = 1) },
                                leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(16.dp)) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetGrip() {
    Box(Modifier.fillMaxWidth().height(12.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.width(32.dp).height(4.dp).background(MaterialTheme.colorScheme.outlineVariant, CircleShape))
    }
}

@Composable
private fun MoreDivider() {
    Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(Modifier.weight(1f))
        Text(stringResource(R.string.dashboard_more_starts), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.weight(1f))
    }
}

@Composable
fun rememberCurrentTime(): String {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val timeState = remember { mutableStateOf(timeFormat.format(Date())) }

    DisposableEffect(Unit) {
        val job = Job()
        val scope = CoroutineScope(Dispatchers.Main.immediate + job)

        scope.launch {
            while (isActive) {
                val now = Date()
                val newTime = timeFormat.format(now)
                if (newTime != timeState.value) {
                    timeState.value = newTime
                }
                val millisUntilNextMinute = 60000L - (now.time % 60000L)
                delay(millisUntilNextMinute)
            }
        }

        onDispose {
            job.cancel()
        }
    }

    return timeState.value
}

private val drawablePainterCache = LruCache<Int, Painter>(100)

@Composable
fun rememberDrawablePainter(drawable: Drawable?): Painter {
    return remember(drawable?.hashCode()) {
        drawable?.let {
            val key = it.hashCode()
            drawablePainterCache.get(key) ?: run {
                val painter = it.toPainter()
                drawablePainterCache.put(key, painter)
                painter
            }
        } ?: ColorPainter(Color.Gray)
    }
}

fun Drawable.toPainter(): Painter {
    return BitmapPainter(this.toBitmap().asImageBitmap())
}

@Composable
fun QuickStartAppIcon(
    appInfo: AppInfo,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val painter = rememberDrawablePainter(appInfo.icon)
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable {
                launchAppInFreeformMode(context, appInfo.packageName)
            },
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painter,
            contentDescription = appInfo.name,
            modifier = Modifier.size(40.dp)
        )
    }
}

fun launchAppInFreeformMode(context: Context, packageName: String,
    onFailure: (() -> Unit)? = null, onLaunched: (() -> Unit)? = null) {
    fun failed() {
        if (onFailure != null) onFailure()
        else Toast.makeText(context, R.string.floating_window_unavailable, Toast.LENGTH_SHORT).show()
    }
    try {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) { onFailure?.invoke(); return }
        val component = intent.component
        val userId = UserHandle.myUserId()
        if (component == null || !context.canLaunchInFloatingWindow(component, userId)) { failed(); return }
        val options = ActivityOptions.makeBasic().apply { setLaunchFloatingWindow(true) }
        context.startActivityAsUser(intent, options.toBundle(), UserHandle.of(userId))
        onLaunched?.invoke()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to launch in freeform", e)
        onFailure?.invoke()
    }
}

private fun Context.canLaunchInFloatingWindow(component: ComponentName, userId: Int): Boolean =
    runCatching {
        val userContext = createContextAsUser(UserHandle.of(userId), 0)
        val info = userContext.packageManager.getActivityInfo(component, 0)
        if (!userContext.isActivityResizeable(info)) return@runCatching false

        val resources = userContext.packageManager.getResourcesForApplication(info.applicationInfo)
        val theme = resources.newTheme().apply {
            val themeId = info.themeResource.takeIf { it != 0 }
                ?: if (info.applicationInfo.targetSdkVersion < android.os.Build.VERSION_CODES.HONEYCOMB) {
                    android.R.style.Theme
                } else {
                    android.R.style.Theme_Holo
                }
            applyStyle(themeId, true)
        }
        val attributes = theme.obtainStyledAttributes(com.android.internal.R.styleable.Window)
        val noDisplay: Boolean
        val transparent: Boolean
        try {
            noDisplay = attributes.getBoolean(
                com.android.internal.R.styleable.Window_windowNoDisplay, false
            )
            transparent = ActivityInfo.isTranslucentOrFloating(attributes) &&
                !attributes.getBoolean(
                    com.android.internal.R.styleable.Window_windowShowWallpaper, false
                )
        } finally {
            attributes.recycle()
        }
        val policyComponent = info.targetActivity?.let { ComponentName(info.packageName, it) }
            ?: component
        val policy = DesktopModeCompatPolicy(userContext)
        !noDisplay && !policy.isTopActivityExemptFromDesktopWindowing(
            policyComponent, noDisplay, transparent, 1, userId, info,
            WindowConfiguration.ACTIVITY_TYPE_STANDARD
        ) && !policy.isPackageLaunchInFullscreen(policyComponent)
    }.getOrDefault(false)

private fun Context.isActivityResizeable(info: ActivityInfo): Boolean =
    Settings.Global.getInt(
        contentResolver, Settings.Global.DEVELOPMENT_FORCE_RESIZABLE_ACTIVITIES, 0
    ) != 0 || ActivityInfo.isResizeableMode(info.resizeMode) || info.supportsPictureInPicture()

@Composable
fun rememberBatteryInfo(): BatteryInfo {
    val context = LocalContext.current
    val batteryInfo = remember { mutableStateOf(BatteryInfo()) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.let {
                    val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    val percentage = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
                    
                    val tempTenths = it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                    val tempCelsius = tempTenths / 10f
                    
                    batteryInfo.value = BatteryInfo(
                        level = percentage,
                        temperatureC = tempCelsius
                    )
                }
            }
        }
        
        val intentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
        }
        
        context.registerReceiver(receiver, intentFilter)
        
        val batteryStatus: Intent? = context.registerReceiver(null, intentFilter)
        batteryStatus?.let { intent ->
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val percentage = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
            val tempCelsius = tempTenths / 10f
            
            batteryInfo.value = BatteryInfo(
                level = percentage,
                temperatureC = tempCelsius
            )
        }
        
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }
    
    return batteryInfo.value
}

private fun getPercentage(value: Double, min: Float, max: Float): Double {
    return ((value - min) / (max - min)).coerceIn(0.0, 1.0)
}

enum class GameMode(@StringRes val displayNameRes: Int, val icon: ImageVector) {
    None(R.string.game_mode_none, Icons.Filled.Block),
    Balanced(R.string.game_mode_balanced, Icons.Filled.SportsEsports),
    PowerSave(R.string.game_mode_battery, Icons.Filled.EnergySavingsLeaf),
    Performance(R.string.game_mode_performance, Icons.Filled.ElectricBolt)
}

fun GameMode.toSystemGameMode(): Int = when (this) {
    GameMode.None -> GameManager.GAME_MODE_UNSUPPORTED
    GameMode.Balanced -> GameManager.GAME_MODE_STANDARD
    GameMode.PowerSave -> GameManager.GAME_MODE_BATTERY
    GameMode.Performance -> GameManager.GAME_MODE_PERFORMANCE
}

fun fromSystemGameMode(value: Int): GameMode = when (value) {
    GameManager.GAME_MODE_UNSUPPORTED -> GameMode.None
    GameManager.GAME_MODE_PERFORMANCE -> GameMode.Performance
    GameManager.GAME_MODE_BATTERY -> GameMode.PowerSave
    else -> GameMode.Balanced
}

data class AppInfo(
    val name: String,
    val icon: Drawable,
    val packageName: String
)

data class BatteryInfo(
    val level: Int = -1,
    val temperatureC: Float = 0f
)

