// SPDX-FileCopyrightText: 2026 miner7222
// SPDX-License-Identifier: Apache-2.0

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.miner7222.gamespace.gamebar

import android.database.ContentObserver
import android.provider.Settings
import android.view.ViewTreeObserver
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Image
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.graphics.drawable.toBitmap
import io.github.miner7222.gamespace.library.SessionOptions
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ceil
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.PowerManager
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.axion.compose.lifecycle.repeatWhenAttached
import io.chaldeaprjkt.gamespace.R
import io.chaldeaprjkt.gamespace.gamebar.fromSystemGameMode
import kotlin.math.roundToInt

/** Only confirmed persistent states are supplied by the session owner. */
enum class SessionStatusChip(val label: Int, val icon: ImageVector) {
    DND(R.string.status_do_not_disturb, Icons.Rounded.DoNotDisturbOn),
    BYPASS_CHARGING(R.string.bypass_charge_enabled_title, Icons.Rounded.BatteryChargingFull),
    BYPASS_READY(R.string.status_bypass_ready, Icons.Rounded.BatteryChargingFull),
    TOUCH(R.string.tile_touch_response, Icons.Rounded.TouchApp),
    WIFI(R.string.wifi_low_latency_title, Icons.Rounded.Wifi),
    SIDEBAR_HIDDEN(R.string.status_sidebar_hidden, Icons.Rounded.VisibilityOff),
    GESTURES(R.string.tile_gesture_lock, Icons.Rounded.Lock),
    RINGER_SOUND(R.string.tile_ringer_sound, Icons.Rounded.VolumeUp),
    RINGER_VIBRATE(R.string.tile_ringer_vibrate, Icons.Rounded.Vibration),
    RINGER_MUTE(R.string.tile_ringer_mute, Icons.Rounded.VolumeOff),
}

data class SessionStartStatus(val mode: Int, val chips: List<SessionStatusChip>, val memoryCleaned: Boolean) {
    val modeOnly: Boolean get() = chips.isEmpty() && !memoryCleaned
}

/** A full session owns one opportunity to show this window; updates never reopen it. */
class SessionStatusOverlay(
    private val context: Context,
    private val wm: WindowManager,
    private val onOpenDashboard: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val status = mutableStateOf<SessionStartStatus?>(null)
    private val geometry = mutableStateOf(Geometry(0.dp, 0.dp, 0, 0))
    private var eligible = false
    private var shown = false
    private var gameVisible = false
    private var shownAt: Long? = null
    private var fading = false
    private val visible = mutableStateOf(false)
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private val timeout = Runnable { fadeOut() }

    fun beginSession(enabled: Boolean) {
        cancel()
        status.value = null
        shown = false
        gameVisible = false
        eligible = enabled
    }

    fun update(value: SessionStartStatus) {
        status.value = value
        if (view != null && !fading) scheduleTimeout()
        showIfReady()
    }

    fun setGameVisible(visible: Boolean) {
        gameVisible = visible
        if (visible) showIfReady() else removeWindow()
    }

    /** Screen off, user switch, session end or an explicit dismissal consumes the opportunity. */
    fun cancel() {
        eligible = false
        removeWindow()
    }

    fun onConfigurationChanged() {
        geometry.value = measureGeometry()
        val current = view ?: return
        params?.let {
            it.x = geometry.value.x
            it.y = 0
            runCatching { wm.updateViewLayout(current, it) }
        }
    }

    private fun showIfReady() {
        if (!eligible || shown || !gameVisible || status.value == null) return
        if (!context.getSystemService(PowerManager::class.java).isInteractive) {
            cancel()
            return
        }
        // Mark before attachment: acknowledgements and visibility refreshes cannot retry it.
        shown = true
        geometry.value = measureGeometry()
        if (geometry.value.height <= 0.dp || geometry.value.width <= 0.dp) return
        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = geometry.value.x
            y = 0
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        visible.value = true
        val pill = ComposeView(context)
        val content = object : FrameLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                // A wrap-content window is first measured at a dialog-sized width, narrower than the
                // pill's own budget, which would squeeze or clip its last chips. Let the pill size
                // itself within the same budget it was laid out for.
                val limit = (geometry.value.width.value * resources.displayMetrics.density).roundToInt()
                val spec = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.AT_MOST) {
                    MeasureSpec.makeMeasureSpec(maxOf(MeasureSpec.getSize(widthMeasureSpec), limit), MeasureSpec.AT_MOST)
                } else widthMeasureSpec
                super.onMeasure(spec, heightMeasureSpec)
            }
        }
        content.addView(pill, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val touchRegion = ViewTreeObserver.OnComputeInternalInsetsListener { info ->
            info.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION)
            info.touchableRegion.set(0, geometry.value.y, content.width,
                geometry.value.y + (geometry.value.height.value * context.resources.displayMetrics.density).roundToInt())
        }
        content.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                view.viewTreeObserver.addOnComputeInternalInsetsListener(touchRegion)
            }
            override fun onViewDetachedFromWindow(view: View) {
                view.viewTreeObserver.takeIf { it.isAlive }?.removeOnComputeInternalInsetsListener(touchRegion)
            }
        })
        // The window root owns the lifecycle, so the hosted pill finds it when composing.
        content.apply {
            repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    pill.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                    pill.setContent {
                        OverlayTheme(context) {
                            status.value?.let { value ->
                                val top = with(LocalDensity.current) { geometry.value.y.toDp() }
                                val overshootRoom = with(LocalDensity.current) {
                                    ceil(geometry.value.height.toPx() * (66f / 56f - 1f) / 2f).toInt().toDp()
                                }
                                Box(Modifier.padding(top = top, bottom = overshootRoom)) {
                                    StatusPill(value, geometry.value.width, geometry.value.height,
                                        visible.value, onEntered = {
                                            if (view === content && !fading && shownAt == null) {
                                                shownAt = SystemClock.uptimeMillis()
                                                scheduleTimeout()
                                            }
                                        }, onHidden = {
                                            if (view === content && fading) removeWindow()
                                        }, onOpen = { cancel(); onOpenDashboard() }, onDismiss = {
                                            eligible = false
                                            fadeOut()
                                        })
                                }
                            }
                        }
                    }
                }
            }
        }
        view = content
        params = layout
        try {
            wm.addView(content, layout)
        } catch (_: RuntimeException) {
            removeWindow()
        }
    }

    private fun scheduleTimeout() {
        handler.removeCallbacks(timeout)
        val enteredAt = shownAt ?: return
        // A late acknowledgement can extend a mode-only pill, but never reset its clock.
        val duration = if (status.value?.modeOnly == true) 1500L else 3500L
        handler.postDelayed(timeout, (enteredAt + duration - SystemClock.uptimeMillis()).coerceAtLeast(0L))
    }

    private fun fadeOut() {
        if (view == null || fading) return
        fading = true
        visible.value = false
    }

    private fun removeWindow() {
        handler.removeCallbacks(timeout)
        val current = view
        view = null
        params = null
        shownAt = null
        fading = false
        visible.value = false
        if (current != null) runCatching { wm.removeViewImmediate(current) }
    }

    private data class Geometry(val width: Dp, val height: Dp, val x: Int, val y: Int)

    private fun measureGeometry(): Geometry {
        val metrics = wm.maximumWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val density = context.resources.displayMetrics.density
        val topCutoutInset = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.displayCutout()).top
        val safeHeight = (metrics.bounds.height() - insets.top - insets.bottom) / density
        return Geometry(
            ((metrics.bounds.width() - insets.left - insets.right) / density - 48).coerceAtLeast(0f).dp,
            minOf(52f, (safeHeight - 32).coerceAtLeast(0f)).dp,
            (insets.left - insets.right) / 2,
            maxOf(insets.top + (16 * density).toInt(), topCutoutInset + (100 * density).toInt()),
        )
    }
}

@Composable
private fun StatusPill(status: SessionStartStatus, maxWidth: Dp, maxHeight: Dp,
    visible: Boolean, onEntered: () -> Unit, onHidden: () -> Unit,
    onOpen: () -> Unit, onDismiss: () -> Unit) {
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val tablet = config.smallestScreenWidthDp >= 600
    val width = minOf(maxWidth, 660.dp)
    val height = minOf(52.dp, maxHeight)
    val mode = fromSystemGameMode(status.mode)
    val modeLabel = stringResource(mode.displayNameRes)
    val labels = status.chips.map { stringResource(it.label) }
    val memoryLabel = stringResource(R.string.status_memory_cleaned)
    val openLabel = stringResource(R.string.gamebar_open_dashboard)
    val summary = (listOf(modeLabel) + labels + if (status.memoryCleaned) listOf(memoryLabel) else emptyList()).joinToString(", ")
    val description = stringResource(R.string.status_session_applied, summary)
    val modeStyle = TextStyle(fontSize = if (tablet) 16.sp else 15.sp, fontWeight = FontWeight.SemiBold)
    val chipStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    val measurer = rememberTextMeasurer()
    fun textPx(text: String, style: TextStyle): Int = measurer.measure(text, style, maxLines = 1).size.width
    val gap = 8.dp
    // The layout rounds every dimension to whole pixels, so the budget is summed in pixels too.
    fun px(value: Dp): Int = with(density) { value.roundToPx() }
    val budget = (px(width) - px(height) - px(gap)).coerceAtLeast(0)
    val gapPx = px(gap)
    val modePx = px(22.dp) + px(6.dp) + textPx(modeLabel, modeStyle)
    fun chipPx(index: Int, icons: Boolean): Int =
        if (icons) px(36.dp)
        else px(8.dp) + px(18.dp) + px(6.dp) + minOf(textPx(labels[index], chipStyle), px(132.dp)) + px(12.dp)
    // Start and end padding, the mode, divider, "+N" counter and result badge never shrink, so they
    // are counted first, with the gap between every pair of neighbours.
    fun totalPx(count: Int, icons: Boolean): Int {
        var sum = px(16.dp) + px(8.dp) + modePx
        var items = 1
        if (status.chips.isNotEmpty() || status.memoryCleaned) { sum += px(1.dp); items++ }
        repeat(count) { sum += chipPx(it, icons); items++ }
        if (count < status.chips.size) {
            sum += maxOf(px(36.dp), 2 * px(10.dp) + textPx("+${status.chips.size - count}", chipStyle)); items++
        }
        if (status.memoryCleaned) { sum += px(36.dp); items++ }
        return sum + gapPx * (items - 1)
    }
    // Chips fold into +N from the third one; icons alone are the last resort for two labelled chips.
    var iconOnly = false
    var count = minOf(3, status.chips.size)
    while (count > 0 && totalPx(count, false) > budget) count--
    if (count < minOf(2, status.chips.size)) {
        iconOnly = true
        count = minOf(3, status.chips.size)
        while (count > 0 && totalPx(count, true) > budget) count--
    }
    val modeTextMaxWidth = with(density) {
        (budget - totalPx(count, iconOnly) + textPx(modeLabel, modeStyle)).coerceAtLeast(0).toDp()
    }
    val shape = RoundedCornerShape(26.dp)
    val context = LocalContext.current
    val gameIcon = remember(context) {
        SessionOptions.packageName(context)?.let { packageName ->
            runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
    }
    val motion = remember { StatusPillMotion() }
    val reduceMotion = rememberReduceMotion()
    val entered by rememberUpdatedState(onEntered)
    val hidden by rememberUpdatedState(onHidden)
    LaunchedEffect(visible, reduceMotion) {
        val scale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
        motion.animate(visible, reduceMotion || scale == 0f)
        if (visible) entered() else hidden()
    }
    val iconDirection = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    var measuredWidth by remember { mutableIntStateOf(0) }
    var drag by remember { mutableFloatStateOf(0f) }
    // The blur is applied inside the clipped surface, so it never reaches past the pill.
    Box(Modifier.onSizeChanged { measuredWidth = it.width }.graphicsLayer {
        scaleX = motion.circle.value
        scaleY = scaleX
    }.drawWithContent {
        val circle = minOf(size.height, size.width)
        val shownWidth = circle + (size.width - circle) * motion.expansion.value.coerceIn(0f, 1f)
        val left = (size.width - shownWidth) / 2f
        val path = Path().apply {
            addRoundRect(RoundRect(left, 0f, left + shownWidth, size.height, CornerRadius(size.height / 2f)))
        }
        clipPath(path) { this@drawWithContent.drawContent() }
    }.then(Modifier.widthIn(max = width).height(height).pointerInput(Unit) {
        val threshold = 24.dp.toPx()
        var dismissed = false
        detectVerticalDragGestures(onDragStart = { drag = 0f; dismissed = false }, onVerticalDrag = { change, amount ->
            change.consume()
            drag += amount
            if (!dismissed && drag < -threshold) { dismissed = true; onDismiss() }
        })
    }.shadow(6.dp, shape).clip(shape).overlayBlur(26.dp)
        .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = overlayPanelAlpha()))
        .border(overlayBorder(), shape)
        .clickable(role = Role.Button, onClick = onOpen)
        .clearAndSetSemantics {
            contentDescription = description
            role = Role.Button
            onClick(label = openLabel) { onOpen(); true }
        })) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.size(height))
                Box(Modifier.graphicsLayer { alpha = motion.content.value }) {
                    Row(Modifier.padding(start = 16.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(mode.icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Text(modeLabel, Modifier.widthIn(max = modeTextMaxWidth), style = modeStyle,
                color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (status.chips.isNotEmpty() || status.memoryCleaned) {
            Box(Modifier.requiredSize(1.dp, 24.dp).background(MaterialTheme.colorScheme.outlineVariant))
        }
        repeat(count) { index ->
            Row((if (iconOnly) Modifier.requiredSize(36.dp) else Modifier.height(36.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp))
                .then(if (iconOnly) Modifier else Modifier.padding(start = 8.dp, end = 12.dp)),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(status.chips[index].icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                if (!iconOnly) Text(labels[index], Modifier.widthIn(max = 132.dp), style = chipStyle,
                    color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (count < status.chips.size) {
            Box(Modifier.requiredHeight(36.dp).requiredWidthIn(min = 36.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = overlayInnerAlpha()), RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                Text("+${status.chips.size - count}", style = chipStyle, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            }
        }
        if (status.memoryCleaned) {
            Box(Modifier.requiredSize(36.dp).background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Memory, memoryLabel, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
    
            }
                }
            }
        Box(Modifier.size(height).graphicsLayer {
            alpha = motion.icon.value
            scaleX = motion.icon.value
            scaleY = scaleX
            translationX = iconDirection * (measuredWidth - size.width) / 2f * (1f - motion.expansion.value.coerceIn(0f, 1f))
        }, contentAlignment = Alignment.Center) {
            if (gameIcon != null) Image(gameIcon, null, Modifier.size(36.dp).clip(CircleShape))
            else Icon(mode.icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

// The circle and width channels keep their own clocks.
private class StatusPillMotion {
    val circle = Animatable(0f)
    val expansion = Animatable(0f)
    val content = Animatable(0f)
    val icon = Animatable(1f)
    private val stageClock = Animatable(0f)
    private val entrance = CubicBezierEasing(0f, 0f, 0.2f, 1f)
    private val settle = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    private val collapse = CubicBezierEasing(0.4f, 0f, 1f, 1f)
    private val fade = Easing { fraction -> ((1.0 - cos(PI * fraction)) / 2.0).toFloat() }
    private val iconExit = CubicBezierEasing(0.3f, 0f, 0.8f, 0.32f)

    private suspend fun hold(millis: Int) {
        stageClock.snapTo(0f)
        stageClock.animateTo(1f, tween(millis, easing = LinearEasing))
    }

    suspend fun animate(visible: Boolean, reduced: Boolean) {
        if (reduced) {
            val target = if (visible) 1f else 0f
            circle.snapTo(target)
            expansion.snapTo(target)
            content.snapTo(target)
            icon.snapTo(target)
        } else if (visible) {
            icon.snapTo(1f)
            circle.animateTo(66f / 56f, tween(250, easing = entrance))
            circle.animateTo(1f, tween(83, easing = settle))
            hold(667)
            coroutineScope {
                launch {
                    hold(83)
                    content.animateTo(1f, tween(167, easing = fade))
                }
                expansion.animateTo(1f, tween(500, easing = entrance))
            }
        } else {
            coroutineScope {
                launch { content.animateTo(0f, tween(167, easing = fade)) }
                expansion.animateTo(0f, tween(333, easing = collapse))
            }
            coroutineScope {
                launch { icon.animateTo(0f, tween(400, easing = iconExit)) }
                hold(583)
            }
            circle.animateTo(0f, tween(83, easing = fade))
        }
    }
}

@Composable
private fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    val key = Settings.Global.Wearable.REDUCE_MOTION
    fun read() = Settings.Global.getInt(context.contentResolver, key, 0) != 0
    var reduced by remember(context) { mutableStateOf(read()) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reduced = read() }
        }
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(key), false, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return reduced
}
