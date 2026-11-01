// SPDX-FileCopyrightText: 2026 miner7222
// SPDX-License-Identifier: Apache-2.0

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package io.github.miner7222.gamespace.library

import android.graphics.Matrix
import android.text.format.DateUtils
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.chaldeaprjkt.gamespace.R
import io.chaldeaprjkt.gamespace.ui.viewmodel.PerAppSettingsViewModel
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(state: LibraryState, library: LibraryViewModel,
    gameSettings: PerAppSettingsViewModel, resumeGeneration: Int,
    onAdd: () -> Unit, onSettings: () -> Unit) {
    var selectedPackage by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val openSettings: (LibraryGame) -> Unit = {
        library.dismissHint()
        selectedPackage = it.packageName
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.settings_title), fontSize = 22.sp) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Rounded.Settings, stringResource(R.string.tile_settings))
                    }
                })
        }, snackbarHost = { SnackbarHost(snackbar) }) { insets ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(insets).padding(16.dp)) {
            val expanded = maxWidth >= 840.dp
            val recent = remember(state.games) { state.games.filter { it.lastPlayed > 0 }.sortedByDescending { it.lastPlayed } }
            if (state.loading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else if (expanded && state.games.isNotEmpty()) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        recent.firstOrNull()?.let { ContinuePlaying(it, true, library.repository::launch) }
                        if (recent.size > 1) {
                            Text(stringResource(R.string.library_recent), fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 8.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                recent.drop(1).take(3).forEachIndexed { index, game ->
                                    Surface(onClick = { library.repository.launch(game.packageName) },
                                        shape = groupedShape(index, minOf(recent.size - 1, 3)),
                                        color = MaterialTheme.colorScheme.surfaceBright) {
                                        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(16.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            GameIcon(game, 40.dp)
                                            Column(Modifier.weight(1f)) {
                                                Text(game.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(playedText(game.lastPlayed), style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            Icon(Icons.Rounded.PlayArrow, stringResource(R.string.library_play))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    LibraryFolder(state, library, onAdd, openSettings, selectedPackage, Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    recent.firstOrNull()?.let { ContinuePlaying(it, false, library.repository::launch) }
                    LibraryFolder(state, library, onAdd, openSettings, selectedPackage, Modifier.weight(1f).fillMaxWidth())
                }
            }
            state.games.firstOrNull { it.packageName == selectedPackage }?.let { game ->
                GameSheet(game, expanded, state, library, gameSettings, resumeGeneration,
                    onDismiss = { selectedPackage = null },
                    onRemove = {
                        val removed = library.repository.unregister(game.packageName)
                        selectedPackage = null
                        library.refresh()
                        if (removed != null) scope.launch {
                            snackbar.currentSnackbarData?.dismiss()
                            val result = snackbar.showSnackbar(context.getString(R.string.library_removed, game.label),
                                context.getString(R.string.library_undo), duration = SnackbarDuration.Long)
                            if (result == SnackbarResult.ActionPerformed) {
                                library.repository.restore(removed)
                                library.refresh()
                            }
                        }
                    })
            }
        }
    }
}

@Composable
private fun ContinuePlaying(game: LibraryGame, expanded: Boolean, launch: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val accent = if (game.accent == 0) scheme.primary else lerp(Color(game.accent), scheme.primary, 0.75f)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.library_continue), fontSize = 14.sp, color = scheme.primary,
            modifier = Modifier.padding(horizontal = 8.dp))
        Surface(shape = RoundedCornerShape(28.dp), color = lerp(scheme.surfaceBright, accent, 0.035f)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    GameIcon(game, 66.dp)
                    Column(Modifier.weight(1f)) {
                        Text(game.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (expanded) {
                            Text(playedText(game.lastPlayed), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                            Text(modeLabel(game.mode), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        } else {
                            Text(stringResource(R.string.per_app_performance_active, playedText(game.lastPlayed), modeLabel(game.mode)),
                                style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                        }
                    }
                    if (!expanded) Button(onClick = { launch(game.packageName) }) {
                        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.library_play))
                    }
                }
                if (expanded) Button(onClick = { launch(game.packageName) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.library_play))
                }
            }
        }
    }
}

@Composable
private fun LibraryFolder(state: LibraryState, library: LibraryViewModel, onAdd: () -> Unit,
    openSettings: (LibraryGame) -> Unit, selectedPackage: String?, modifier: Modifier) {
    var sortMenu by remember { mutableStateOf(false) }
    val labelGap = if (LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
        10.dp else 14.dp
    Surface(modifier, shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.game_list_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(12.dp))
                Text(state.games.size.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { sortMenu = true }) {
                        Icon(Icons.AutoMirrored.Rounded.Sort, stringResource(R.string.library_sort))
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        listOf(R.string.library_recent, R.string.library_sort_name).forEachIndexed { index, label ->
                            DropdownMenuItem(text = { Text(stringResource(label)) },
                                leadingIcon = { if (state.sort == index) Icon(Icons.Rounded.Check, null) },
                                onClick = { library.setSort(index); sortMenu = false })
                        }
                    }
                }
            }
            if (state.games.isEmpty()) {
                EmptyLibrary(state.autoDetect, onAdd, library::enableAutoDetection, Modifier.weight(1f))
            } else {
                if (!state.hintDismissed) {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceBright) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Info, null, Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(16.dp))
                            Text(stringResource(R.string.library_hold_hint), Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            IconButton(onClick = library::dismissHint) {
                                Icon(Icons.Rounded.Close, stringResource(R.string.cd_dismiss))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                BoxWithConstraints(Modifier.weight(1f)) {
                    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
                    val columns = (maxWidth / (104.dp * fontScale)).toInt().coerceIn(2, 6)
                    LazyVerticalGrid(columns = GridCells.Fixed(columns), modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(state.games, key = { it.packageName }) { game ->
                            GameTile(game, selectedPackage == game.packageName, labelGap,
                                onLaunch = { library.repository.launch(game.packageName) },
                                onSettings = { openSettings(game) })
                        }
                        item(key = "add_game") {
                            Column(Modifier.padding(horizontal = 4.dp).fillMaxWidth().clickable(role = Role.Button, onClick = onAdd),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(66.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                                    contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.Add, null, Modifier.size(28.dp), MaterialTheme.colorScheme.onSecondaryContainer)
                                }
                                Spacer(Modifier.height(labelGap))
                                Text(stringResource(R.string.add_game), fontSize = 14.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(autoDetect: Boolean, onAdd: () -> Unit, onEnable: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(96.dp).background(MaterialTheme.colorScheme.secondaryContainer,
            MaterialShapes.Cookie9Sided.toShape()), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.SportsEsports, null, Modifier.size(40.dp), MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.library_empty_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(if (autoDetect) R.string.library_empty_auto else R.string.library_empty_manual),
            Modifier.widthIn(max = 360.dp), textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAdd) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.add_game)) }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(if (autoDetect) R.string.library_auto_on else R.string.library_auto_off),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!autoDetect) TextButton(onClick = onEnable) { Text(stringResource(R.string.library_auto_enable)) }
    }
}

@Composable
private fun GameTile(game: LibraryGame, selected: Boolean, labelGap: Dp, onLaunch: () -> Unit, onSettings: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current
    val corner by animateDpAsState(if (pressed || selected) 16.dp else 33.dp,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(), label = "gameIconCorner")
    val scale by animateFloatAsState(if (selected) 1.05f else if (pressed) 0.95f else 1f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(), label = "gameIconScale")
    val actionLabel = stringResource(R.string.library_game_settings)
    Column(Modifier.padding(horizontal = 4.dp).fillMaxWidth().semantics {
        customActions = listOf(CustomAccessibilityAction(actionLabel) { onSettings(); true })
    }.combinedClickable(interactionSource = interaction, indication = null, role = Role.Button,
        onClickLabel = stringResource(R.string.library_launch),
        onLongClickLabel = stringResource(R.string.library_open_game_settings),
        hapticFeedbackEnabled = false, onClick = onLaunch, onLongClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onSettings()
        }), horizontalAlignment = Alignment.CenterHorizontally) {
        GameIcon(game, 66.dp, Modifier.scale(scale), if (corner < 32.5.dp) RoundedCornerShape(corner) else null)
        Spacer(Modifier.height(labelGap))
        Text(game.label, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun GameIcon(game: LibraryGame, size: Dp, modifier: Modifier = Modifier, shape: Shape? = null) {
    val mask = remember(game.iconMask) {
        game.iconMask?.let { path ->
            object : Shape {
                override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
                    val scaled = android.graphics.Path(path)
                    scaled.transform(Matrix().apply { setScale(size.width / game.icon.width, size.height / game.icon.height) })
                    return Outline.Generic(scaled.asComposePath())
                }
            }
        } ?: CircleShape
    }
    Image(game.icon, contentDescription = null, modifier = modifier.size(size).clip(shape ?: mask))
}

internal fun groupedShape(index: Int, count: Int) = RoundedCornerShape(
    topStart = if (index == 0) 20.dp else 4.dp, topEnd = if (index == 0) 20.dp else 4.dp,
    bottomStart = if (index == count - 1) 20.dp else 4.dp, bottomEnd = if (index == count - 1) 20.dp else 4.dp)

@Composable
internal fun modeLabel(mode: Int): String = stringResource(when (mode) {
    0 -> R.string.game_mode_none
    2 -> R.string.game_mode_performance
    3 -> R.string.game_mode_battery
    else -> R.string.game_mode_balanced
})

@Composable
internal fun playedText(time: Long, lastPlayed: Boolean = false): String {
    if (time == 0L) return stringResource(R.string.library_never_played)
    val relative = DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    return stringResource(if (lastPlayed) R.string.library_last_played else R.string.library_played, relative)
}
