// SPDX-FileCopyrightText: 2026 miner7222
// SPDX-License-Identifier: Apache-2.0

package io.github.miner7222.gamespace.gamebar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.chaldeaprjkt.gamespace.R

/**
 * Host for a dashboard tile's detail page: a bottom sheet with a back button beside the title.
 * [onBack] is the same action as the system back and a tap on the scrim.
 */
@Composable
fun BoxScope.DashboardDetailSheet(title: String, onBack: () -> Unit,
    contentModifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(
        MaterialTheme.colorScheme.scrim.copy(alpha = 0.3f)).clickable(onClick = onBack))
    Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.8f)
        .semantics { paneTitle = title },
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = overlayInnerAlpha()),
        contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                        stringResource(R.string.library_back))
                }
                Text(title, Modifier.weight(1f).padding(start = 4.dp, top = 12.dp, bottom = 12.dp),
                    style = MaterialTheme.typography.titleMedium)
            }
            Column(contentModifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp), content = content)
        }
    }
}
