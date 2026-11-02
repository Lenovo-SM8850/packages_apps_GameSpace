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

package io.chaldeaprjkt.gamespace.gamebar

import android.graphics.Rect
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.compose.animation.scene.SceneTransitionLayout
import com.android.compose.animation.scene.SwipeDetector
import com.android.compose.animation.scene.rememberMutableSceneTransitionLayoutState
import io.chaldeaprjkt.gamespace.R
import io.github.miner7222.gamespace.FitLabel
import io.github.miner7222.gamespace.gamebar.RecordingPhase
import io.github.miner7222.gamespace.gamebar.RecordingState
import io.github.miner7222.gamespace.gamebar.overlayBlur
import io.github.miner7222.gamespace.gamebar.overlayBorder
import io.github.miner7222.gamespace.gamebar.overlayInnerAlpha
import io.github.miner7222.gamespace.gamebar.overlayPanelAlpha
import io.github.miner7222.gamespace.shortLabelResource
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// Shared with the native overlay so hit targets and gesture exclusions use the same bounds.
internal const val HANDLE_WIDTH_DP = 48
internal const val HANDLE_HEIGHT_DP = 80
internal const val MINI_WIDTH_DP = 136
internal const val MINI_HEIGHT_DP = 128
internal const val MINI_RECORDING_HEIGHT_DP = 184

internal fun miniWidthDp(showStats: Boolean) =
    if (showStats) 76 else MINI_WIDTH_DP

internal fun miniHeightDp(showStats: Boolean, recording: Boolean) =
    if (showStats) {
        if (recording) MINI_HEIGHT_DP else 68
    } else {
        if (recording) MINI_RECORDING_HEIGHT_DP else MINI_HEIGHT_DP
    }

@Composable
fun GameBarView(
    showFps: Boolean,
    fpsText: String,
    isLocked: Boolean,
    isIdle: Boolean,
    idleAlpha: Float,
    dockedOnLeft: Boolean,
    recording: RecordingState,
    onShowPanel: (expandFps: Boolean) -> Unit,
    onStopRecording: () -> Unit,
    pillExpanded: Boolean,
    anchorY: Int,
    onExpanded: () -> Unit,
    onCollapsed: () -> Unit,
    onInteraction: (pressed: Boolean) -> Unit,
    onDragStart: () -> Pair<Int, Int>,
    onDragUpdate: (Int, Int) -> Unit,
    onDragEnd: (Int, Int) -> Unit,
    onContentBoundsChanged: (Rect) -> Unit,
) {
    val state = rememberMutableSceneTransitionLayoutState(
        initialScene = GameBarScenes.Handle,
        transitions = GameBarTransitions,
    )
    val scope = rememberCoroutineScope()
    val noSwipe = remember {
        object : SwipeDetector {
            override fun detectSwipe(change: PointerInputChange) = false
        }
    }
    LaunchedEffect(pillExpanded) {
        state.setTargetScene(if (pillExpanded) GameBarScenes.MiniBar else GameBarScenes.Handle, scope)
    }
    val animatedAlpha by animateFloatAsState(
        targetValue = if (isIdle && !pillExpanded) idleAlpha else 1f,
        label = "handleAlpha",
    )
    var startWindowX by remember { mutableIntStateOf(0) }
    var startWindowY by remember { mutableIntStateOf(0) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var repositioning by remember { mutableStateOf(false) }
    val dragModifier = Modifier.pointerInput(dockedOnLeft) {
        val threshold = 12.dp.toPx()
        detectDragGestures(
            onDragStart = {
                val position = onDragStart()
                startWindowX = position.first
                startWindowY = position.second
                dragX = 0f
                dragY = 0f
                repositioning = false
            },
            onDrag = { change, amount ->
                change.consume()
                dragX += amount.x
                dragY += amount.y
                if (abs(dragX) > threshold || abs(dragY) > threshold) repositioning = true
                if (repositioning) onDragUpdate(startWindowX + dragX.toInt(), startWindowY + dragY.toInt())
            },
            onDragEnd = {
                onDragEnd(startWindowX + if (repositioning) dragX.toInt() else 0,
                    startWindowY + if (repositioning) dragY.toInt() else 0)
            },
            onDragCancel = { onDragEnd(startWindowX, startWindowY) },
        )
    }
    // Observe without consuming: taps, swipes and held fingers all postpone collapse.
    val currentInteraction by rememberUpdatedState(onInteraction)
    val currentContentBoundsChanged by rememberUpdatedState(onContentBoundsChanged)
    Layout(modifier = Modifier.fillMaxSize().pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            currentInteraction(true)
            try {
                var pressed = true
                while (pressed) {
                    pressed = awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed }
                }
            } finally {
                currentInteraction(false)
            }
        }
    }, content = {
        SceneTransitionLayout(state = state, swipeDetector = noSwipe) {
            scene(GameBarScenes.Handle) {
                EdgeHandle(isLocked, dockedOnLeft, animatedAlpha, onExpanded,
                    Modifier.element(GameBarElements.HandleContent).then(dragModifier))
            }
            scene(GameBarScenes.MiniBar) {
                MiniBar(fpsText, showFps, dockedOnLeft, recording, onShowPanel, onStopRecording, onCollapsed,
                    Modifier.element(GameBarElements.MiniBarContent))
            }
        }
    }) { measurables, constraints ->
        val scene = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val left = if (dockedOnLeft) 0 else constraints.maxWidth - scene.width
            val top = (anchorY - scene.height / 2).coerceIn(0,
                (constraints.maxHeight - scene.height).coerceAtLeast(0))
            scene.place(left, top)
            // Report the same animated bounds used for placement, even when the window is unchanged.
            currentContentBoundsChanged(Rect(left.coerceAtLeast(0), top,
                (left + scene.width).coerceAtMost(constraints.maxWidth),
                (top + scene.height).coerceAtMost(constraints.maxHeight)))
        }
    }
}

@Composable
private fun EdgeHandle(
    locked: Boolean,
    onLeft: Boolean,
    alpha: Float,
    onTap: () -> Unit,
    modifier: Modifier,
) {
    val description = stringResource(R.string.gamebar_open_controls)
    val lockState = if (locked) stringResource(R.string.dashboard_gesture_on) else null
    Box(modifier.size(HANDLE_WIDTH_DP.dp, HANDLE_HEIGHT_DP.dp).alpha(alpha)
        .semantics {
            contentDescription = description
            if (lockState != null) stateDescription = lockState
        }.clickable(role = Role.Button, onClick = onTap)
        .padding(horizontal = 4.dp),
        contentAlignment = if (onLeft) Alignment.CenterStart else Alignment.CenterEnd) {
        val shape = RoundedCornerShape(6.dp)
        Box(Modifier.size(12.dp, 64.dp).shadow(3.dp, shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.94f), shape)
            .border(overlayBorder(), shape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(4.dp, 28.dp).background(
                if (locked) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f), RoundedCornerShape(2.dp)))
        }
    }
}

@Composable
private fun MiniBar(
    fpsText: String,
    showStats: Boolean,
    onLeft: Boolean,
    recording: RecordingState,
    onShowPanel: (Boolean) -> Unit,
    onStopRecording: () -> Unit,
    onCollapsed: () -> Unit,
    modifier: Modifier,
) {
    val battery = rememberBatteryInfo()
    val activeRecording = recording.phase == RecordingPhase.ACTIVE
    val width by animateDpAsState(miniWidthDp(showStats).dp,
        animationSpec = tween(180), label = "miniWidth")
    val height by animateDpAsState(miniHeightDp(showStats, activeRecording).dp,
        animationSpec = tween(180), label = "miniHeight")
    var swipe by remember { mutableFloatStateOf(0f) }
    Column(modifier.requiredSize(width, height)
        .padding(start = if (onLeft) 8.dp else 0.dp, end = if (onLeft) 0.dp else 8.dp)
        .pointerInput(onLeft) {
            val threshold = 32.dp.toPx()
            var collapseSent = false
            detectHorizontalDragGestures(onDragStart = { swipe = 0f; collapseSent = false },
                onHorizontalDrag = { change, amount ->
                    swipe += amount
                    change.consume()
                    if (!collapseSent && ((onLeft && swipe < -threshold) || (!onLeft && swipe > threshold))) {
                        collapseSent = true
                        onCollapsed()
                    }
                })
        }
        .overlayBlur(32.dp)
        .shadow(6.dp, RoundedCornerShape(32.dp))
        .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = overlayPanelAlpha()), RoundedCornerShape(32.dp))
        .border(overlayBorder(), RoundedCornerShape(32.dp))
        .padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showStats) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                DashboardCell(onLeft) { onShowPanel(false) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onLeft) DashboardCell(onLeft) { onShowPanel(false) }
                ReadoutCell(fpsText.ifEmpty { "–" }, stringResource(R.string.gamebar_fps_label)) { onShowPanel(true) }
                if (!onLeft) DashboardCell(onLeft) { onShowPanel(false) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ReadoutCell(stringResource(R.string.dashboard_temperature, battery.temperatureC.roundToInt()),
                    stringResource(R.string.gamebar_temperature_label)) { onShowPanel(false) }
                ReadoutCell(if (battery.level >= 0) "${battery.level}%" else "–",
                    stringResource(R.string.gamebar_battery_label),
                    shortLabelResource(R.string.gamebar_battery_label_short)) { onShowPanel(false) }
            }
        }
        if (recording.phase == RecordingPhase.ACTIVE) {
            val elapsed = recording.elapsedMillis?.let {
                String.format(Locale.getDefault(), "%d:%02d", it / 60000, it / 1000 % 60)
            } ?: stringResource(R.string.dashboard_elapsed_unknown)
            val stop = stringResource(R.string.dashboard_stop_recording)
            val recordingModifier = (if (showStats) Modifier.size(56.dp).align(Alignment.CenterHorizontally)
                else Modifier.fillMaxWidth().height(52.dp))
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 26.dp, bottomStart = 26.dp))
                .background(MaterialTheme.colorScheme.errorContainer)
                .semantics(mergeDescendants = true) { contentDescription = "$stop, $elapsed" }
                .clickable(role = Role.Button, onClick = onStopRecording)
            if (showStats) {
                Column(recordingModifier, horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Rounded.Stop, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
                    Text(elapsed, color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 11.sp, lineHeight = 14.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1, style = TextStyle(fontFeatureSettings = "tnum"))
                }
            } else {
                Row(recordingModifier, verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                    Icon(Icons.Rounded.Stop, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
                    Text(elapsed, color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 16.sp,
                        fontWeight = FontWeight.Bold, style = TextStyle(fontFeatureSettings = "tnum"))
                }
            }
        }
    }
}

@Composable
private fun DashboardCell(onLeft: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner by animateDpAsState(if (pressed) 20.dp else 28.dp, label = "dashboardCorner")
    val shape = RoundedCornerShape(topStart = if (onLeft) corner else 20.dp,
        topEnd = if (onLeft) 20.dp else corner, bottomStart = 20.dp, bottomEnd = 20.dp)
    Box(Modifier.size(56.dp).clip(shape).background(MaterialTheme.colorScheme.primaryContainer)
        .clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(),
            role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Dashboard, stringResource(R.string.gamebar_open_dashboard), Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun ReadoutCell(value: String, label: String, shortLabel: String? = null, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner by animateDpAsState(if (pressed) 24.dp else 20.dp, label = "readoutCorner")
    Column(Modifier.size(56.dp).clip(RoundedCornerShape(corner))
        .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = overlayInnerAlpha()))
        .semantics(mergeDescendants = true) {}
        .clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(),
            role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(value, color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp, lineHeight = 20.sp,
            fontWeight = FontWeight.Bold, style = TextStyle(fontFeatureSettings = "tnum"))
        FitLabel(label, shortLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 14.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp)
    }
}
