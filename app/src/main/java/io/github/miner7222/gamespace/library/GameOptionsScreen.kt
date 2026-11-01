// SPDX-FileCopyrightText: 2026 miner7222
// SPDX-License-Identifier: Apache-2.0

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.miner7222.gamespace.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.chaldeaprjkt.gamespace.R

internal data class GameOptionsSnapshot(val effective: Map<GameOption, Int>, val changed: Set<GameOption>, val available: List<GameOption>, val runtimeMode: Int?)

@Composable
internal fun rememberGameOptions(store: InGameOptions, packageName: String): GameOptionsSnapshot {
    val context = LocalContext.current
    var revision by remember(store, packageName) { mutableIntStateOf(0) }
    DisposableEffect(store, packageName) {
        val observer = store.observe { revision++ }
        onDispose { observer.close() }
    }
    return remember(store, packageName, revision) {
        val overrides = store.overrides(packageName)
        GameOptionsSnapshot(GameOption.entries.associateWith { overrides[it] ?: store.global(it) }, overrides.keys, store.availableOptions(),
            if (SessionOptions.packageName(context) == packageName) SessionOptions.mode(context) else null)
    }
}

@Composable
internal fun GameOptionsScreen(game: LibraryGame, expanded: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { InGameOptions(context) }
    val snapshot = rememberGameOptions(store, game.packageName)
    var choice by remember { mutableStateOf<GameOption?>(null) }
    val title = stringResource(R.string.in_game_preferences_title)
    val available = snapshot.available
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false)) {
        BackHandler(onBack = onBack)
        Box(Modifier.fillMaxSize()) {
            if (expanded) Box(Modifier.fillMaxSize().background(BottomSheetDefaults.ScrimColor)
                .clickable(onClick = onBack).clearAndSetSemantics { })
            Surface(Modifier.align(if (expanded) Alignment.CenterEnd else Alignment.Center)
                .then(if (expanded) Modifier.windowInsetsPadding(WindowInsets.safeDrawing).width(400.dp)
                    else Modifier.fillMaxWidth()).fillMaxHeight().semantics { paneTitle = title },
                shape = if (expanded) RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp) else RoundedCornerShape(0.dp),
                color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.then(if (expanded) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing))) {
                    TopAppBar(title = {
                        Column {
                            Text(title, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(game.label, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }, navigationIcon = {
                        FilledTonalIconButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp).size(40.dp)) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.library_back))
                        }
                    }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer))
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        available.forEachIndexed { index, option ->
                            val value = snapshot.effective.getValue(option)
                            val changed = option in snapshot.changed
                            val summary = if (option.entries != 0) {
                                val values = context.resources.getStringArray(option.values)
                                context.resources.getStringArray(option.entries)[values.indexOf(value.toString()).coerceAtLeast(0)]
                            } else if (option.summary != 0) stringResource(option.summary) else null
                            Surface(shape = groupedShape(index, available.size), color = if (isSystemInDarkTheme())
                                MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceBright) {
                                val modifier = if (option.entries == 0) Modifier.toggleable(value == 1, role = Role.Switch,
                                    onValueChange = { store.set(game.packageName, option, if (it) 1 else 0) })
                                    else Modifier.clickable(onClick = { choice = option })
                                Row(Modifier.fillMaxWidth().then(modifier).heightIn(min = 72.dp).padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(stringResource(option.title), style = MaterialTheme.typography.titleMedium)
                                        if (summary != null) Text(summary, style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (changed) Text(stringResource(R.string.library_option_changed),
                                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                    if (option.entries == 0) {
                                        Spacer(Modifier.width(16.dp))
                                        Switch(checked = value == 1, onCheckedChange = null,
                                            thumbContent = { if (value == 1) Icon(Icons.Rounded.Check, null, Modifier.size(SwitchDefaults.IconSize)) })
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        TextButton(onClick = { store.reset(game.packageName) }, enabled = snapshot.changed.isNotEmpty(),
                            modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.library_reset_defaults)) }
                    }
                }
            }
        }
        choice?.let { option ->
            val labels = context.resources.getStringArray(option.entries)
            val values = context.resources.getStringArray(option.values)
            AlertDialog(onDismissRequest = { choice = null }, title = { Text(stringResource(option.title)) },
                text = { Column(Modifier.selectableGroup()) {
                    labels.forEachIndexed { index, label ->
                        val selected = snapshot.effective[option].toString() == values[index]
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected, role = Role.RadioButton,
                            onClick = { store.set(game.packageName, option, values[index].toInt()); choice = null }),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected, onClick = null)
                            Spacer(Modifier.width(16.dp)); Text(label)
                        }
                    }
                } }, confirmButton = { TextButton(onClick = { choice = null }) { Text(stringResource(android.R.string.cancel)) } })
        }
    }
}
