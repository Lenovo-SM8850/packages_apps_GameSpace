// SPDX-FileCopyrightText: 2026 miner7222
// SPDX-License-Identifier: Apache-2.0

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.miner7222.gamespace.library

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.chaldeaprjkt.gamespace.R
import io.github.miner7222.gamespace.TouchSensitivitySettings
import kotlin.math.roundToInt

/** The same touch values and ranges as the dashboard sheet, stored per game or as the default. */
@Composable
internal fun TouchRows(packageName: String?, gestureRow: (@Composable (Shape) -> Unit)? = null) {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { revision++ }
        }
        listOf(TouchSensitivitySettings.KEY_HIGH_POLLING, TouchSensitivitySettings.KEY_TAP,
            TouchSensitivitySettings.KEY_MOVE, TouchSensitivitySettings.KEY_EDGE,
            TouchSensitivitySettings.KEY_PEN_INPUT, TouchSensitivitySettings.OVERRIDES,
            TouchSensitivitySettings.DEFAULTS).forEach { key ->
            context.contentResolver.registerContentObserver(Settings.Secure.getUriFor(key), false,
                observer, context.userId)
        }
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    var highPolling by remember(revision, packageName) {
        mutableStateOf(TouchSensitivitySettings.value(context, packageName,
            TouchSensitivitySettings.KEY_HIGH_POLLING) == 1)
    }
    var tap by remember(revision, packageName) {
        mutableIntStateOf(TouchSensitivitySettings.value(context, packageName, TouchSensitivitySettings.KEY_TAP))
    }
    var move by remember(revision, packageName) {
        mutableIntStateOf(TouchSensitivitySettings.value(context, packageName, TouchSensitivitySettings.KEY_MOVE))
    }
    var edge by remember(revision, packageName) {
        mutableIntStateOf(TouchSensitivitySettings.value(context, packageName, TouchSensitivitySettings.KEY_EDGE))
    }
    var penInput by remember(revision, packageName) {
        mutableStateOf(TouchSensitivitySettings.value(context, packageName,
            TouchSensitivitySettings.KEY_PEN_INPUT) == 1)
    }
    fun changed(key: String) =
        packageName != null && TouchSensitivitySettings.hasOverride(context, packageName, key)
    val edgeLabels = listOf(R.string.touch_sensitivity_edge_off,
        R.string.touch_sensitivity_edge_small, R.string.touch_sensitivity_edge_medium,
        R.string.touch_sensitivity_edge_large)
    val rowCount = if (gestureRow != null) 6 else 5
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        OptionRow(stringResource(R.string.touch_sensitivity_high_polling), null, highPolling,
            groupedShape(0, rowCount), changed = changed(TouchSensitivitySettings.KEY_HIGH_POLLING)) {
            highPolling = !highPolling
            TouchSensitivitySettings.setValue(context, packageName,
                TouchSensitivitySettings.KEY_HIGH_POLLING, if (highPolling) 1 else 0)
        }
        // A higher slider position is more sensitive; the node stores the inverted value.
        SliderRow(stringResource(R.string.touch_sensitivity_tap), "${(21 - tap) * 5}%",
            ((21 - tap) * 5).toFloat(), 0f..100f, 19, groupedShape(1, rowCount),
            changed = changed(TouchSensitivitySettings.KEY_TAP)) { position ->
            val next = 21 - position.roundToInt().coerceIn(0, 100) / 5
            if (next != tap) {
                tap = next
                TouchSensitivitySettings.setValue(context, packageName,
                    TouchSensitivitySettings.KEY_TAP, next)
            }
        }
        SliderRow(stringResource(R.string.touch_sensitivity_move), "${(6 - move) * 25}%",
            ((6 - move) * 25).toFloat(), 0f..100f, 3, groupedShape(2, rowCount),
            changed = changed(TouchSensitivitySettings.KEY_MOVE)) { position ->
            val next = 6 - position.roundToInt().coerceIn(0, 100) / 25
            if (next != move) {
                move = next
                TouchSensitivitySettings.setValue(context, packageName,
                    TouchSensitivitySettings.KEY_MOVE, next)
            }
        }
        SliderRow(stringResource(R.string.touch_sensitivity_edge), stringResource(edgeLabels[edge]),
            edge.toFloat(), 0f..3f, 2, groupedShape(3, rowCount),
            changed = changed(TouchSensitivitySettings.KEY_EDGE)) { position ->
            val next = position.roundToInt().coerceIn(0, 3)
            if (next != edge) {
                edge = next
                TouchSensitivitySettings.setValue(context, packageName,
                    TouchSensitivitySettings.KEY_EDGE, next)
            }
        }
        gestureRow?.invoke(groupedShape(4, rowCount))
        OptionRow(stringResource(R.string.tile_pen_input),
            if (penInput) stringResource(R.string.tile_pen_input_high_polling_off) else null,
            penInput, groupedShape(if (gestureRow != null) 5 else 4, rowCount),
            changed = changed(TouchSensitivitySettings.KEY_PEN_INPUT)) {
            penInput = !penInput
            TouchSensitivitySettings.setValue(context, packageName,
                TouchSensitivitySettings.KEY_PEN_INPUT, if (penInput) 1 else 0)
        }
    }
}

@Composable
internal fun SliderRow(title: String, value: String, position: Float,
    range: ClosedFloatingPointRange<Float>, steps: Int, shape: Shape, changed: Boolean = false,
    onChange: (Float) -> Unit) {
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(value, style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (changed) Text(stringResource(R.string.library_option_changed),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Slider(value = position, onValueChange = onChange, modifier = Modifier.semantics {
                contentDescription = title
            }, valueRange = range, steps = steps)
        }
    }
}
