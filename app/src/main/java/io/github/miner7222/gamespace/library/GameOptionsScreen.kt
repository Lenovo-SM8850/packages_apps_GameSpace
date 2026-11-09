// SPDX-FileCopyrightText: 2026 miner7222
// SPDX-License-Identifier: Apache-2.0

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.miner7222.gamespace.library

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import io.chaldeaprjkt.gamespace.R
import io.github.miner7222.gamespace.TouchSensitivitySettings

internal data class GameOptionsSnapshot(val effective: Map<GameOption, Int>, val changed: Set<GameOption>, val available: List<GameOption>, val runtimeMode: Int?, val touchChanged: Boolean)

@Composable
internal fun rememberGameOptions(store: InGameOptions, packageName: String): GameOptionsSnapshot {
    val context = LocalContext.current
    var revision by remember(store, packageName) { mutableIntStateOf(0) }
    DisposableEffect(store, packageName) {
        val observer = store.observe { revision++ }
        val gestureObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { revision++ }
        }
        context.contentResolver.registerContentObserver(Settings.Secure.getUriFor("ax_gaming_gesture_lock"),
            false, gestureObserver, context.userId)
        // Touch overrides and defaults change the per-game rows without touching the option store.
        listOf(TouchSensitivitySettings.OVERRIDES, TouchSensitivitySettings.DEFAULTS).forEach { key ->
            context.contentResolver.registerContentObserver(Settings.Secure.getUriFor(key), false,
                gestureObserver, context.userId)
        }
        onDispose {
            observer.close()
            context.contentResolver.unregisterContentObserver(gestureObserver)
        }
    }
    return remember(store, packageName, revision) {
        val overrides = store.overrides(packageName)
        val currentGame = SessionOptions.packageName(context) == packageName
        GameOptionsSnapshot(GameOption.entries.associateWith {
            if (it.globalOnly) store.global(it)
            else if (it == GameOption.GESTURES && currentGame) Settings.Secure.getIntForUser(
                context.contentResolver, "ax_gaming_gesture_lock", 0, context.userId)
            else overrides[it] ?: store.global(it)
        }, overrides.keys, store.availableOptions().filter { packageName.isEmpty() || !it.globalOnly },
            if (SessionOptions.packageName(context) == packageName) SessionOptions.mode(context) else null,
            packageName.isNotEmpty() && TouchSensitivitySettings.hasOverrides(context, packageName))
    }
}

@Composable
internal fun GameOptionsScreen(game: LibraryGame, expanded: Boolean, onBack: () -> Unit) {
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val lightBars = MaterialTheme.colorScheme.surfaceContainer.luminance() > 0.5f
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = lightBars
                    isAppearanceLightNavigationBars = lightBars
                }
            }
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                OptionsHeader(stringResource(R.string.in_game_preferences_title), game.label, onBack)
                GameOptionsContent(game.packageName, expanded, onBack = onBack)
            }
        }
    }
}

@Composable
internal fun OptionsHeader(title: String, subtitle: String? = null, onBack: () -> Unit) {
    TopAppBar(title = {
        Column {
            Text(title, fontSize = 22.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }, navigationIcon = {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.library_back))
        }
    }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer))
}

@Composable
internal fun OptionsCategories(titles: List<Int>, selected: Int, onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        titles.forEachIndexed { index, title ->
            Surface(shape = if (selected == index) RoundedCornerShape(50) else groupedShape(index, titles.size),
                color = if (selected == index) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).selectable(selected == index,
                    role = Role.Tab, onClick = { onSelect(index) }).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
internal fun GameOptionsContent(packageName: String?, expanded: Boolean, onBack: () -> Unit,
    overlay: Boolean = false, selectedGroup: GameOptionGroup? = null,
    editQuickApps: Boolean = false, onQuickAppsHandled: () -> Unit = {}) {
    val context = LocalContext.current
    val store = remember(context) { InGameOptions(context) }
    val snapshot = rememberGameOptions(store, packageName.orEmpty())
    var category by rememberSaveable(packageName) { mutableIntStateOf(0) }
    var choice by remember(packageName) { mutableStateOf<GameOption?>(null) }
    BackHandler { if (choice != null) choice = null else onBack() }
    // Global-only groups have no per-game values.
    val groups = if (selectedGroup != null) listOf(selectedGroup)
        else GameOptionGroup.entries.filter { packageName == null || !it.globalOnly }
    val detail: @Composable (List<GameOptionGroup>) -> Unit = { visibleGroups ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            visibleGroups.forEach { group ->
                val options = group.options.filter { it in snapshot.available }
                val screenRows = packageName == null && !overlay && group == GameOptionGroup.SCREEN
                // Touch rows edit for the viewed game on both screens; the overlay lists options only.
                val touchRows = group == GameOptionGroup.TOUCH && !overlay
                if (options.isNotEmpty() || screenRows || touchRows) {
                    Text(stringResource(group.title), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
                    if (options.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        options.forEachIndexed { index, option ->
                            val value = if (packageName == null) store.global(option) else snapshot.effective.getValue(option)
                            val summary = if (option.entries != 0) {
                                val values = context.resources.getStringArray(option.values)
                                context.resources.getStringArray(option.entries)[values.indexOf(value.toString()).coerceAtLeast(0)]
                            } else if (option.summary != 0) stringResource(option.summary) else null
                            OptionRow(stringResource(option.title), summary,
                                if (option.entries == 0) value == 1 else null, groupedShape(index, options.size),
                                changed = packageName != null && option in snapshot.changed,
                                onClick = {
                                    if (option.entries != 0) choice = option
                                    else if (packageName == null) store.setGlobal(option, 1 - value)
                                    else store.set(packageName, option, 1 - value)
                                })
                        }
                    }
                    if (touchRows) TouchRows(packageName)
                    if (screenRows) GlobalScreenRows(editQuickApps, onQuickAppsHandled)
                }
            }
            if (packageName != null) TextButton(onClick = {
                store.reset(packageName)
                TouchSensitivitySettings.clearOverrides(context, packageName,
                    applyLive = SessionOptions.packageName(context) == packageName &&
                        SessionOptions.isActive(context))
            }, enabled = snapshot.changed.isNotEmpty() || snapshot.touchChanged,
                modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.library_reset_defaults))
            }
        }
    }
    if (overlay) {
        Column(Modifier.fillMaxSize()) {
            val selected = choice
            OptionsHeader(stringResource(selected?.title ?: R.string.in_game_preferences_title),
                onBack = { if (selected != null) choice = null else onBack() })
            if (selected != null) {
                val labels = context.resources.getStringArray(selected.entries)
                val values = context.resources.getStringArray(selected.values)
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).selectableGroup().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    labels.forEachIndexed { index, label ->
                        val checked = snapshot.effective[selected].toString() == values[index]
                        Surface(shape = groupedShape(index, labels.size),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(checked,
                                role = Role.RadioButton, onClick = {
                                    store.set(requireNotNull(packageName), selected, values[index].toInt())
                                    choice = null
                                }).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(checked, onClick = null)
                                Spacer(Modifier.width(16.dp))
                                Text(label)
                            }
                        }
                    }
                }
            } else detail(groups)
        }
    } else if (expanded && selectedGroup == null) {
        Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OptionsCategories(groups.map { it.title }, category, { category = it }, Modifier.width(340.dp))
            Surface(Modifier.weight(1f).fillMaxHeight(), shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow) { detail(listOf(groups[category])) }
        }
    } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 640.dp).fillMaxSize()) { detail(groups) }
    }
    choice?.takeUnless { overlay }?.let { option ->
        val value = if (packageName == null) store.global(option) else snapshot.effective.getValue(option)
        val labels = context.resources.getStringArray(option.entries)
        val values = context.resources.getStringArray(option.values)
        AlertDialog(onDismissRequest = { choice = null }, title = { Text(stringResource(option.title)) },
            text = { Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                labels.forEachIndexed { index, label ->
                    val selected = value.toString() == values[index]
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected, role = Role.RadioButton,
                        onClick = {
                            if (packageName == null) store.setGlobal(option, values[index].toInt())
                            else store.set(packageName, option, values[index].toInt())
                            choice = null
                        }), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected, onClick = null)
                        Spacer(Modifier.width(16.dp)); Text(label)
                    }
                }
            } }, confirmButton = { TextButton(onClick = { choice = null }) { Text(stringResource(android.R.string.cancel)) } })
    }
}

@Composable
internal fun OptionRow(title: String, summary: String?, checked: Boolean?,
    shape: androidx.compose.ui.graphics.Shape, changed: Boolean = false, onClick: () -> Unit) {
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        val action = if (checked == null) Modifier.clickable(onClick = onClick)
            else Modifier.toggleable(checked, role = Role.Switch, onValueChange = { onClick() })
        Row(Modifier.fillMaxWidth().then(action).heightIn(min = 64.dp).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (summary != null) Text(summary, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (changed) Text(stringResource(R.string.library_option_changed),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            if (checked != null) {
                Spacer(Modifier.width(16.dp))
                Switch(checked, onCheckedChange = null,
                    thumbContent = { if (checked) Icon(Icons.Rounded.Check, null, Modifier.size(SwitchDefaults.IconSize)) })
            }
        }
    }
}
