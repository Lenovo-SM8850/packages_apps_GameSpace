// SPDX-FileCopyrightText: 2026 miner7222
// SPDX-License-Identifier: Apache-2.0

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.miner7222.gamespace.gamebar

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/** Overlays sit on top of a game, so they always use the dark scheme. */
@Composable
fun OverlayTheme(context: Context, content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = dynamicDarkColorScheme(context),
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}

fun overlayPanelAlpha(): Float = 0.95f

fun overlayInnerAlpha(): Float = 1f

/** Keeps an overlay's edge visible over a black game. */
@Composable
fun overlayBorder(): BorderStroke =
    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
