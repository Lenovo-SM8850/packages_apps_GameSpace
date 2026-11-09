// SPDX-FileCopyrightText: 2026 miner7222
// SPDX-License-Identifier: Apache-2.0

package io.github.miner7222.gamespace.gamebar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.chaldeaprjkt.gamespace.R
import io.github.miner7222.gamespace.TouchSensitivitySettings
import io.github.miner7222.gamespace.library.SessionOptions
import kotlin.math.roundToInt

/** Four touch controls, saved for the running game and written as soon as they change. */
@Composable
fun BoxScope.TouchSensitivitySheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val packageName = SessionOptions.packageName(context)
    val title = context.getString(R.string.touch_sensitivity_title)
    var highPolling by remember { mutableStateOf(TouchSensitivitySettings.highPolling(context)) }
    // Pen input turns high polling off for the session; the stored choice stays as it is.
    val penInput = remember { TouchSensitivitySettings.penInput(context) }
    var tap by remember { mutableIntStateOf(TouchSensitivitySettings.tap(context)) }
    var move by remember { mutableIntStateOf(TouchSensitivitySettings.move(context)) }
    var edge by remember { mutableIntStateOf(TouchSensitivitySettings.edge(context)) }
    val edgeLabels = listOf(R.string.touch_sensitivity_edge_off,
        R.string.touch_sensitivity_edge_small, R.string.touch_sensitivity_edge_medium,
        R.string.touch_sensitivity_edge_large)
    DashboardDetailSheet(title, onBack = onDismiss) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !penInput) {
            highPolling = !highPolling
            TouchSensitivitySettings.setValue(context, packageName,
                TouchSensitivitySettings.KEY_HIGH_POLLING, if (highPolling) 1 else 0)
        }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(context.getString(R.string.touch_sensitivity_high_polling),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (penInput) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        else MaterialTheme.colorScheme.onSurface)
                if (penInput) Text(context.getString(R.string.touch_sensitivity_high_polling_pen),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = highPolling, onCheckedChange = null, enabled = !penInput)
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        // Higher slider position = more sensitive; the node stores the inverted value.
        SensitivitySlider(context.getString(R.string.touch_sensitivity_tap), "${(21 - tap) * 5}%",
            0f..100f, 19, ((21 - tap) * 5).toFloat()) {
            tap = 21 - it.coerceIn(0, 100) / 5
            TouchSensitivitySettings.setValue(context, packageName,
                TouchSensitivitySettings.KEY_TAP, tap)
        }
        SensitivitySlider(context.getString(R.string.touch_sensitivity_move), "${(6 - move) * 25}%",
            0f..100f, 3, ((6 - move) * 25).toFloat()) {
            move = 6 - it.coerceIn(0, 100) / 25
            TouchSensitivitySettings.setValue(context, packageName,
                TouchSensitivitySettings.KEY_MOVE, move)
        }
        SensitivitySlider(context.getString(R.string.touch_sensitivity_edge),
            context.getString(edgeLabels[edge]), 0f..3f, 2, edge.toFloat()) {
            edge = it
            TouchSensitivitySettings.setValue(context, packageName,
                TouchSensitivitySettings.KEY_EDGE, it)
        }
    }
}

@Composable
private fun SensitivitySlider(title: String, value: String, range: ClosedFloatingPointRange<Float>,
    steps: Int, current: Float, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = current, onValueChange = { onChange(it.roundToInt()) },
            valueRange = range, steps = steps)
    }
}
