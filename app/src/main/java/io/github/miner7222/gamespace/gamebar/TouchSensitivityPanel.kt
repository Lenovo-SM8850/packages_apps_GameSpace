// SPDX-FileCopyrightText: 2026 miner7222
// SPDX-License-Identifier: Apache-2.0

package io.github.miner7222.gamespace.gamebar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.chaldeaprjkt.gamespace.R
import io.github.miner7222.gamespace.TouchSensitivitySettings
import kotlin.math.roundToInt

/** Four global touch controls, written as soon as they change. */
@Composable
fun BoxScope.TouchSensitivitySheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val title = context.getString(R.string.touch_sensitivity_title)
    var highPolling by remember { mutableStateOf(TouchSensitivitySettings.highPolling(context)) }
    var tap by remember { mutableIntStateOf(TouchSensitivitySettings.tap(context)) }
    var move by remember { mutableIntStateOf(TouchSensitivitySettings.move(context)) }
    var edge by remember { mutableIntStateOf(TouchSensitivitySettings.edge(context)) }
    val edgeLabels = listOf(R.string.touch_sensitivity_edge_off,
        R.string.touch_sensitivity_edge_small, R.string.touch_sensitivity_edge_medium,
        R.string.touch_sensitivity_edge_large)
    Box(Modifier.fillMaxSize().background(
        MaterialTheme.colorScheme.scrim.copy(alpha = 0.3f)).clickable(onClick = onDismiss))
    Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.8f)
        .semantics { paneTitle = title }, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = overlayInnerAlpha()),
        contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(title, Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                highPolling = !highPolling
                TouchSensitivitySettings.setHighPolling(context, highPolling)
            }, verticalAlignment = Alignment.CenterVertically) {
                Text(context.getString(R.string.touch_sensitivity_high_polling),
                    Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = highPolling, onCheckedChange = null)
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            // Higher slider position = more sensitive; the node stores the inverted value.
            SensitivitySlider(context.getString(R.string.touch_sensitivity_tap), "${(21 - tap) * 5}%",
                0f..100f, 19, ((21 - tap) * 5).toFloat()) {
                tap = 21 - it.coerceIn(0, 100) / 5
                TouchSensitivitySettings.setTap(context, tap)
            }
            SensitivitySlider(context.getString(R.string.touch_sensitivity_move), "${(6 - move) * 25}%",
                0f..100f, 3, ((6 - move) * 25).toFloat()) {
                move = 6 - it.coerceIn(0, 100) / 25
                TouchSensitivitySettings.setMove(context, move)
            }
            SensitivitySlider(context.getString(R.string.touch_sensitivity_edge),
                context.getString(edgeLabels[edge]), 0f..3f, 2, edge.toFloat()) {
                edge = it
                TouchSensitivitySettings.setEdge(context, it)
            }
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
