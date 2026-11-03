// SPDX-FileCopyrightText: 2026 miner7222
// SPDX-License-Identifier: Apache-2.0

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package io.github.miner7222.gamespace.library

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
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

object AppPickerView {
    fun interface ToggleListener {
        fun onToggle(packageName: String, checked: Boolean)
    }

    @JvmStatic
    fun create(context: Context, names: Array<String>, packages: Array<String>, icons: Array<Drawable>,
        selected: Set<String>, initialQuery: String, onQuery: java.util.function.Consumer<String>,
        listener: ToggleListener): View = ComposeView(context).apply {
        setContent {
            MaterialExpressiveTheme(colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context)
                else dynamicLightColorScheme(context), motionScheme = MotionScheme.expressive()) {
                var query by rememberSaveable { mutableStateOf(initialQuery) }
                val checked = remember { mutableStateMapOf<String, Boolean>().apply {
                    packages.forEach { put(it, it in selected) }
                } }
                val duplicates = remember { names.groupingBy { it.lowercase() }.eachCount() }
                val indices = remember(query) { packages.indices.filter {
                    names[it].contains(query, ignoreCase = true) || packages[it].contains(query, ignoreCase = true)
                } }
                Column(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                    AppSearchField(query) { query = it; onQuery.accept(it) }
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(indices, key = { packages[it] }) { index ->
                            val packageName = packages[index]
                            val enabled = checked[packageName] == true
                            val bitmap = remember(icons[index]) { icons[index].toBitmap(96, 96).asImageBitmap() }
                            Surface(shape = groupedShape(indices.indexOf(index), indices.size),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                Row(Modifier.fillMaxWidth().toggleable(enabled, role = Role.Checkbox,
                                    onValueChange = { checked[packageName] = it; listener.onToggle(packageName, it) })
                                    .heightIn(min = 64.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Image(bitmap, null, Modifier.size(40.dp))
                                    Spacer(Modifier.width(16.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(names[index], style = MaterialTheme.typography.bodyLarge)
                                        if ((duplicates[names[index].lowercase()] ?: 0) > 1)
                                            Text(packageName, style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Checkbox(enabled, onCheckedChange = null)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
