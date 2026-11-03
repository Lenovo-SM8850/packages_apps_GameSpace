// SPDX-FileCopyrightText: 2026 miner7222
// SPDX-License-Identifier: Apache-2.0

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package io.github.miner7222.gamespace.library

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import io.github.miner7222.gamespace.FitLabel
import io.github.miner7222.gamespace.shortLabelResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import io.chaldeaprjkt.gamespace.R
import io.chaldeaprjkt.gamespace.ui.viewmodel.PerAppSettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Additional per-game rows are inserted after the in-game options and before Home visibility. */
data class GameSheetExtraRow(val key: String, val content: @Composable (Shape) -> Unit)

@Composable
internal fun GameSheet(game: LibraryGame, expanded: Boolean, state: LibraryState,
    library: LibraryViewModel, model: PerAppSettingsViewModel, resumeGeneration: Int,
    onDismiss: () -> Unit, onRemove: () -> Unit, extraRows: List<GameSheetExtraRow> = emptyList(),
    initialDestination: GameSettingsDestination? = null) {
    var optionsOpen by rememberSaveable(game.packageName) {
        mutableStateOf(initialDestination == GameSettingsDestination.IN_GAME_OPTIONS)
    }
    if (optionsOpen) {
        GameOptionsScreen(game, expanded) { optionsOpen = false }
        return
    }
    LaunchedEffect(game.packageName, resumeGeneration) { model.loadGame(game.packageName) }
    val content: @Composable () -> Unit = {
        if (model.packageName == game.packageName) {
            GameSheetContent(game, state, library, model, resumeGeneration, onRemove, extraRows,
                focusMode = initialDestination == GameSettingsDestination.MODE,
                onOptions = { optionsOpen = true })
        }
    }
    if (expanded) {
        Dialog(onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            val view = LocalView.current
            DisposableEffect(view) {
                (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f)
                onDispose { }
            }
            val dismissInteraction = remember { MutableInteractionSource() }
            val pane = stringResource(R.string.library_game_settings)
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().background(BottomSheetDefaults.ScrimColor)
                    .clickable(interactionSource = dismissInteraction, indication = null, onClick = onDismiss)
                    .clearAndSetSemantics { })
                Surface(Modifier.align(Alignment.CenterEnd).windowInsetsPadding(WindowInsets.safeDrawing)
                    .width(400.dp).fillMaxHeight().semantics { paneTitle = pane }
                    .pointerInput(Unit) { detectTapGestures(onTap = { }) },
                    shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow) { content() }
            }
        }
    } else {
        ModalBottomSheet(onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            sheetMaxWidth = 640.dp,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            dragHandle = { BottomSheetDefaults.DragHandle() },
            contentWindowInsets = { WindowInsets.navigationBars.only(WindowInsetsSides.Bottom) },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            Box(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.9f)) {
                content()
            }
        }
    }
}

@Composable
private fun GameSheetContent(game: LibraryGame, state: LibraryState, library: LibraryViewModel,
    model: PerAppSettingsViewModel, resumeGeneration: Int, onRemove: () -> Unit,
    extraRows: List<GameSheetExtraRow>, focusMode: Boolean, onOptions: () -> Unit) {
    val modeFocus = remember { FocusRequester() }
    val modePosition = remember { BringIntoViewRequester() }
    var modePlaced by remember(game.packageName) { mutableStateOf(false) }
    LaunchedEffect(game.packageName, focusMode, modePlaced) {
        if (focusMode && modePlaced) {
            modePosition.bringIntoView()
            modeFocus.requestFocus()
        }
    }
    val context = LocalContext.current
    val options = remember(context) { InGameOptions(context) }
    val snapshot = rememberGameOptions(options, game.packageName)
    val effectiveMode = snapshot.runtimeMode ?: model.preferredMode
    val tuningIntent = remember(context, game.packageName, resumeGeneration) {
        resolveTuning(context, game.packageName)
    }
    var performanceSummary by remember(game.packageName) { mutableStateOf<String?>(null) }
    LaunchedEffect(game.packageName, effectiveMode, resumeGeneration, tuningIntent) {
        performanceSummary = null
        if (tuningIntent != null && effectiveMode != 0) {
            performanceSummary = withContext(Dispatchers.IO) {
                queryActivePreset(context, game.packageName, effectiveMode)
            }
        }
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GameIcon(game, 56.dp)
            Column(Modifier.weight(1f)) {
                Text(game.label, fontSize = 22.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(playedText(game.lastPlayed, lastPlayed = true),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = { library.repository.launch(game.packageName) }, contentPadding = PaddingValues(horizontal = 16.dp)) {
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.library_play))
            }
        }
        Text(stringResource(R.string.per_app_mode_title), color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        PreferredModeGroup(model, Modifier.bringIntoViewRequester(modePosition)
            .onGloballyPositioned { modePlaced = true }.focusRequester(modeFocus).focusable()) { mode ->
            model.updatePreferredMode(mode)
            library.refresh()
        }
        when (model.preferredMode) {
            0 -> R.string.game_mode_none_summary
            2 -> R.string.library_mode_performance_summary
            else -> null
        }?.let {
            Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        val rows = mutableListOf<@Composable (Shape) -> Unit>()
        if (effectiveMode == 0 || tuningIntent != null) rows.add { shape ->
            SheetRow(stringResource(R.string.per_app_performance_title),
                if (effectiveMode == 0) stringResource(R.string.library_tuning_off)
                    else performanceSummary ?: stringResource(R.string.per_app_performance_summary), Icons.Rounded.Speed,
                shape, enabled = effectiveMode != 0,
                onClick = { tuningIntent?.let { context.startActivity(it) } })
        }
        rows.add { shape ->
            val count = snapshot.changed.size
            SheetRow(stringResource(R.string.in_game_preferences_title),
                if (count == 0) stringResource(R.string.library_options_defaults)
                else androidx.compose.ui.res.pluralStringResource(R.plurals.library_options_count, count, count),
                Icons.Rounded.Tune, shape, onClick = onOptions)
        }
        extraRows.forEach { row -> rows.add { shape -> key(row.key) { row.content(shape) } } }
        if (state.hideGames) rows.add { shape ->
            val checked = game.packageName in state.homeExceptions
            SheetRow(stringResource(R.string.library_also_show_home), null, Icons.Rounded.Home,
                shape, checked = checked, onCheckedChange = { library.showOnHome(game.packageName, it) })
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            rows.forEachIndexed { index, row -> row(groupedShape(index, rows.size)) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", game.packageName, null)))
            }) {
                Icon(Icons.Rounded.Info, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.library_app_info))
            }
            TextButton(onClick = onRemove, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.library_remove))
            }
        }
    }
}

@Composable
private fun PreferredModeGroup(model: PerAppSettingsViewModel, modifier: Modifier = Modifier, onModeChanged: (Int) -> Unit) {
    val options = model.gameModeOptions.sortedBy { listOf(0, 3, 1, 2).indexOf(it.mode) }
    val labels = options.map { stringResource(it.labelRes) }
    val shortLabels = options.map { shortLabelResource(it.shortRes) }
    val weights = options.map {
        animateFloatAsState(if (it.mode == model.preferredMode) 1.15f else 1f,
            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(), label = "preferredModeWidth").value
    }
    val shapes = options.mapIndexed { index, _ ->
        when (index) {
            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes(shape = RoundedCornerShape(
                topStart = 28.dp, bottomStart = 28.dp, topEnd = 8.dp, bottomEnd = 8.dp))
            options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes(shape = RoundedCornerShape(
                topStart = 8.dp, bottomStart = 8.dp, topEnd = 28.dp, bottomEnd = 28.dp))
            else -> ButtonGroupDefaults.connectedMiddleButtonShapes(shape = RoundedCornerShape(8.dp))
        }
    }
    ButtonGroup(overflowIndicator = { ButtonGroupDefaults.OverflowIndicator(it) },
        modifier = modifier.fillMaxWidth().height(56.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEachIndexed { index, option ->
            customItem(buttonGroupContent = {
                ToggleButton(checked = model.preferredMode == option.mode,
                    onCheckedChange = { onModeChanged(option.mode) },
                    modifier = Modifier.weight(weights[index]).fillMaxHeight(), shapes = shapes[index],
                    elevation = null, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    if (model.preferredMode == option.mode) {
                        Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                    }
                    FitLabel(labels[index], shortLabels[index], fontSize = 14.sp)
                }
            }, menuContent = { menu ->
                DropdownMenuItem(text = { Text(labels[index]) }, onClick = {
                    onModeChanged(option.mode); menu.dismiss()
                })
            })
        }
    }
}

@Composable
private fun SheetRow(title: String, summary: String?, icon: ImageVector, shape: Shape,
    onClick: () -> Unit = { }, checked: Boolean? = null, enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit = { }) {
    val interaction = if (checked != null) Modifier.toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange)
        else Modifier.clickable(enabled = enabled, onClick = onClick)
    Surface(shape = shape, color = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.surfaceContainer
        else MaterialTheme.colorScheme.surfaceBright) {
        Row(Modifier.fillMaxWidth().then(interaction).heightIn(min = 72.dp).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(24.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!summary.isNullOrEmpty()) Text(summary, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (checked != null) {
                Spacer(Modifier.width(12.dp))
                Switch(checked = checked, onCheckedChange = null, thumbContent = {
                    if (checked) Icon(Icons.Rounded.Check, null, Modifier.size(SwitchDefaults.IconSize))
                })
            }
        }
    }
}

internal fun resolveTuning(context: Context, packageName: String? = null): Intent? {
    val intent = Intent("io.github.miner7222.gametuning.action.GAME_TUNING")
        .putExtra("package_name", packageName)
    val resolved = context.packageManager.resolveActivity(intent,
        PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_SYSTEM_ONLY.toLong())) ?: return null
    intent.component = ComponentName(resolved.activityInfo.packageName, resolved.activityInfo.name)
    return intent
}

internal fun queryActivePreset(context: Context, packageName: String, mode: Int,
    includeMode: Boolean = true): String? = try {
    val uri = Uri.Builder().scheme("content").authority("io.github.miner7222.gametuning.presets")
        .appendPath("active").appendQueryParameter("package", packageName)
        .appendQueryParameter("mode", mode.toString()).build()
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val modeLabel = cursor.getString(cursor.getColumnIndexOrThrow("mode_label"))
            val presetLabel = cursor.getString(cursor.getColumnIndexOrThrow("preset_label"))
            if (presetLabel.isNullOrEmpty() || (includeMode && modeLabel.isNullOrEmpty())) null
            else if (includeMode) context.getString(R.string.per_app_performance_active, modeLabel, presetLabel)
            else presetLabel
        } else null
    }
} catch (_: Exception) { null }
