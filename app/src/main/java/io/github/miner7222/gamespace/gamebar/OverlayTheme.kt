// SPDX-FileCopyrightText: 2026 miner7222
// SPDX-License-Identifier: Apache-2.0

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.miner7222.gamespace.gamebar

import android.content.Context
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.function.Consumer
import kotlin.math.roundToInt

private const val BLUR_RADIUS_DP = 28

/** Whether the surfaces behind the overlay are blurred, which sets how translucent they may be. */
val LocalOverlayBlur = staticCompositionLocalOf { false }

/** Reads the opacity of the layer an overlay surface sits in, so its blur fades with it. */
val LocalOverlayFade = staticCompositionLocalOf<() -> Float> { { 1f } }

/** Overlays sit on top of a game, so they always use the dark scheme. */
@Composable
fun OverlayTheme(context: Context, content: @Composable () -> Unit) {
    val blur = rememberBlurAvailable(context)
    MaterialExpressiveTheme(
        colorScheme = dynamicDarkColorScheme(context),
        motionScheme = MotionScheme.expressive(),
    ) {
        CompositionLocalProvider(LocalOverlayBlur provides blur, content = content)
    }
}

@Composable
private fun rememberBlurAvailable(context: Context): Boolean {
    val view = LocalView.current
    val windowManager = remember(context) { context.getSystemService(WindowManager::class.java) }
    var enabled by remember(windowManager) { mutableStateOf(windowManager.isCrossWindowBlurEnabled) }
    DisposableEffect(windowManager) {
        val listener = Consumer<Boolean> { enabled = it }
        windowManager.addCrossWindowBlurEnabledListener(context.mainExecutor, listener)
        onDispose { windowManager.removeCrossWindowBlurEnabledListener(listener) }
    }
    return enabled && view.viewRootImpl != null
}

/** Container opacity: 86% over a blur, 95% without one. */
@Composable
fun overlayPanelAlpha(): Float = if (LocalOverlayBlur.current) 0.86f else 0.95f

/** Inactive inner surfaces: 92% over a blur, opaque without one. */
@Composable
fun overlayInnerAlpha(): Float = if (LocalOverlayBlur.current) 0.92f else 1f

/** Keeps an overlay's edge visible over a black game. */
@Composable
fun overlayBorder(): BorderStroke =
    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

/**
 * Blurs the game behind this rounded rectangle. The overlay windows span more than their
 * surfaces, so the blur is a region of the window, the same one a window background blur uses.
 */
@Composable
fun Modifier.overlayBlur(cornerRadius: Dp): Modifier {
    if (!LocalOverlayBlur.current) return this
    val view = LocalView.current
    val fade = LocalOverlayFade.current
    val radius = with(LocalDensity.current) { BLUR_RADIUS_DP.dp.roundToPx() }
    val drawable = remember(view, radius) {
        runCatching {
            view.viewRootImpl?.createBackgroundBlurDrawable()?.also { it.setBlurRadius(radius) }
        }.getOrNull()
    } ?: return this
    DisposableEffect(drawable) { onDispose { drawable.setVisible(false, false) } }
    return drawBehind {
        drawable.setCornerRadius(cornerRadius.toPx())
        drawable.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
        drawable.setAlpha((fade().coerceIn(0f, 1f) * 255).roundToInt())
        drawable.draw(drawContext.canvas.nativeCanvas)
    }
}
