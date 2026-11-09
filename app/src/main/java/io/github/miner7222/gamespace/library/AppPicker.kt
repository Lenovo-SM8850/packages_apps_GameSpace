// SPDX-FileCopyrightText: 2026 miner7222
// SPDX-License-Identifier: Apache-2.0

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package io.github.miner7222.gamespace.library

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.chaldeaprjkt.gamespace.R

@Composable
internal fun AppSearchField(query: String, onQuery: (String) -> Unit) {
    OutlinedTextField(query, onQuery, label = { Text(stringResource(R.string.app_search_title)) },
        singleLine = true, modifier = Modifier.fillMaxWidth().padding(16.dp))
}

/** One launchable app offered by the picker. */
internal class PickerApp(val name: String, val packageName: String, val icon: Drawable)

/** The selection is reported through [onToggle]; [selected] only seeds the checkboxes. */
@Composable
internal fun AppPickerContent(apps: List<PickerApp>, selected: Set<String>, initialQuery: String,
    onQuery: (String) -> Unit, onToggle: (String, Boolean) -> Unit) {
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    val checked = remember { mutableStateMapOf<String, Boolean>().apply {
        apps.forEach { put(it.packageName, it.packageName in selected) }
    } }
    val duplicates = remember(apps) { apps.groupingBy { it.name.lowercase() }.eachCount() }
    val visible = remember(apps, query) { apps.filter {
        it.name.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
    } }
    Column(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
        AppSearchField(query) { query = it; onQuery(it) }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(visible, key = { _, app -> app.packageName }) { index, app ->
                val enabled = checked[app.packageName] == true
                val bitmap = remember(app.icon) { app.icon.toBitmap(96, 96).asImageBitmap() }
                Surface(shape = groupedShape(index, visible.size),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(Modifier.fillMaxWidth().toggleable(enabled, role = Role.Checkbox,
                        onValueChange = { checked[app.packageName] = it; onToggle(app.packageName, it) })
                        .heightIn(min = 64.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Image(bitmap, null, Modifier.size(40.dp))
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.name, style = MaterialTheme.typography.bodyLarge)
                            if ((duplicates[app.name.lowercase()] ?: 0) > 1)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Checkbox(enabled, onCheckedChange = null)
                    }
                }
            }
        }
    }
}
