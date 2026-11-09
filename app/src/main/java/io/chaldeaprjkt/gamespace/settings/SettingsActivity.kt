/*
 * Copyright (C) 2021 Chaldeaprjkt
 * Copyright (C) 2022-2026 crDroid Android Project
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
package io.chaldeaprjkt.gamespace.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentContainerView
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity
import dagger.hilt.android.AndroidEntryPoint
import io.chaldeaprjkt.gamespace.R
import io.chaldeaprjkt.gamespace.data.AppSettings
import io.github.miner7222.gamespace.library.*
import kotlin.math.roundToInt

@AndroidEntryPoint(CollapsingToolbarBaseActivity::class)
class SettingsActivity : Hilt_SettingsActivity() {
    companion object {
        const val EXTRA_EDIT_QUICK_APPS = "edit_quick_start_apps"
    }

    private var globalOptionsOpen by mutableStateOf(false)
    private var globalCategory by mutableIntStateOf(0)
    private var editQuickApps by mutableStateOf(false)

    fun openGlobalInGameOptions() {
        globalOptionsOpen = true
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The request is consumed once; a recreated activity restores its own state instead.
        val quickApps = savedInstanceState == null && intent.getBooleanExtra(EXTRA_EDIT_QUICK_APPS, false)
        intent.removeExtra(EXTRA_EDIT_QUICK_APPS)
        editQuickApps = quickApps
        globalOptionsOpen = savedInstanceState?.getBoolean("global_options_open") ?: quickApps
        // Quick start apps live in the first group, the screen and overlay options.
        globalCategory = savedInstanceState?.getInt("global_category")
            ?.coerceIn(0, GameOptionGroup.entries.lastIndex) ?: 0
        enableEdgeToEdge()
        actionBar?.hide()
        window.setContentView(ComposeView(this).apply {
            setContent {
                MaterialExpressiveTheme(
                    colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(this@SettingsActivity)
                        else dynamicLightColorScheme(this@SettingsActivity),
                    motionScheme = MotionScheme.expressive()) {
                    SettingsContent()
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("global_options_open", globalOptionsOpen)
        outState.putInt("global_category", globalCategory)
        super.onSaveInstanceState(outState)
    }

    @Composable
    private fun SettingsContent() {
        val mainTitles = listOf(R.string.game_list_title, R.string.global_in_game_options_title)
        val gameTitles = GameOptionGroup.entries.map { it.title }
        val back = { if (globalOptionsOpen) globalOptionsOpen = false else finish() }
        val globalRows: @Composable () -> Unit = {
            GlobalScreenRows(editQuickApps) { editQuickApps = false }
        }
        BackHandler(onBack = back)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainer) {
            BoxWithConstraints(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                val expanded = maxWidth >= 840.dp
                Column(Modifier.fillMaxSize()) {
                    OptionsHeader(stringResource(if (globalOptionsOpen) R.string.global_in_game_options_title
                        else if (intent.hasExtra("referer")) R.string.settings_title else R.string.tile_settings),
                        onBack = back)
                    if (expanded) Row(Modifier.weight(1f).padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        OptionsCategories(if (globalOptionsOpen) gameTitles else mainTitles,
                            if (globalOptionsOpen) globalCategory else 0, { selected ->
                                if (globalOptionsOpen) globalCategory = selected
                                else if (selected == 1) openGlobalInGameOptions()
                            }, Modifier.width(340.dp))
                        Surface(Modifier.weight(1f).fillMaxHeight(), shape = RoundedCornerShape(28.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            if (globalOptionsOpen) {
                                GameOptionsContent(null, expanded = false,
                                    selectedGroup = GameOptionGroup.entries[globalCategory], onBack = back,
                                    globalScreenRows = globalRows)
                            } else PreferencesPane("library_preferences")
                        }
                    } else Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        Box(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
                            if (globalOptionsOpen)
                                GameOptionsContent(null, expanded = false, onBack = back,
                                    globalScreenRows = globalRows)
                            else PreferencesPane("main")
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PreferencesPane(section: String, inline: Boolean = false) {
        key(section) {
            val tag = "settings_$section"
            AndroidView(modifier = (if (inline) Modifier.fillMaxWidth() else Modifier.fillMaxSize()).clipToBounds(),
                factory = { context ->
                    FragmentContainerView(context).apply {
                        id = com.android.settingslib.collapsingtoolbar.R.id.content_frame
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            if (inline) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT)
                    }
                }, update = { container ->
                    container.post {
                        if (container.isAttachedToWindow && !supportFragmentManager.isStateSaved) {
                            val existing = supportFragmentManager.findFragmentById(container.id)
                            if (existing?.tag == tag) {
                                (existing as? SettingsFragment)?.setInlineLayout(inline)
                                if (existing.view?.parent !== container) {
                                    supportFragmentManager.beginTransaction().detach(existing).commitNow()
                                    supportFragmentManager.beginTransaction().attach(existing).commitNow()
                                }
                            } else {
                                val fragment = SettingsFragment().apply {
                                    arguments = Bundle().apply {
                                        putString("section", section)
                                        putBoolean("inline", inline)
                                    }
                                }
                                supportFragmentManager.beginTransaction().replace(container.id, fragment, tag).commitNow()
                            }
                        }
                    }
                }, onRelease = { container ->
                    if (!supportFragmentManager.isStateSaved) {
                        supportFragmentManager.findFragmentById(container.id)?.takeIf {
                            it.tag == tag && it.view?.parent === container
                        }?.let { supportFragmentManager.beginTransaction().remove(it).commitNow() }
                    }
                })
        }
    }
}

/**
 * Rows of the screen group that exist only globally: handle opacity, handle auto-hide and the
 * quick start apps. They keep their preference keys, so the handle and sidebar update live.
 */
@Composable
private fun GlobalScreenRows(editQuickApps: Boolean, onQuickAppsHandled: () -> Unit) {
    val context = LocalContext.current
    val settings = remember(context) { AppSettings(context) }
    var opacity by remember { mutableIntStateOf(settings.menuOpacity) }
    var autoHide by remember { mutableStateOf(settings.handleAutoHide) }
    var picking by rememberSaveable { mutableStateOf(false) }
    // The dashboard can ask for the picker directly; consume the request once.
    LaunchedEffect(editQuickApps) {
        if (editQuickApps) {
            picking = true
            onQuickAppsHandled()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SliderRow(stringResource(R.string.icon_idle_opacity_title), "$opacity%", opacity.toFloat(),
            0f..100f, 0, groupedShape(0, 3)) { position ->
            val next = position.roundToInt().coerceIn(0, 100)
            if (next != opacity) {
                opacity = next
                settings.menuOpacity = next
            }
        }
        OptionRow(stringResource(R.string.handle_auto_hide_title),
            stringResource(R.string.handle_auto_hide_summary), autoHide, groupedShape(1, 3)) {
            autoHide = !autoHide
            settings.handleAutoHide = autoHide
        }
        OptionRow(stringResource(R.string.quick_start_apps_title), null, null, groupedShape(2, 3)) {
            picking = true
        }
    }
    if (picking) QuickStartAppsDialog(settings.quickStartApps, onDismiss = { picking = false },
        onSave = { apps ->
            settings.quickStartApps = apps
            picking = false
        })
}

/** Selection is a comma-separated package list, the format the sidebar reads. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickStartAppsDialog(saved: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val context = LocalContext.current
    val apps = remember(context) { launchableApps(context) }
    var selection by rememberSaveable { mutableStateOf(saved) }
    // Only seeds the checkboxes; later toggles are tracked in [selection].
    val seed = remember { selection.split(',').filter { it.isNotEmpty() }.toSet() }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.quick_start_apps_title)) },
        text = {
            AppPickerContent(apps, seed, "", {}) { packageName, checked ->
                val packages = selection.split(',').filter { it.isNotEmpty() }.toMutableList()
                if (checked) {
                    if (packageName !in packages) packages.add(packageName)
                } else packages.remove(packageName)
                selection = packages.joinToString(",")
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(selection) }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        })
}

/** One entry per launcher package, sorted by label. */
private fun launchableApps(context: Context): List<PickerApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
    val seen = mutableSetOf<String>()
    val apps = mutableListOf<PickerApp>()
    for (info in pm.queryIntentActivities(intent, 0)) {
        val packageName = info.activityInfo?.packageName ?: continue
        if (!seen.add(packageName)) continue
        try {
            val app = pm.getApplicationInfo(packageName, 0)
            apps.add(PickerApp(pm.getApplicationLabel(app).toString(), packageName, pm.getApplicationIcon(app)))
        } catch (_: PackageManager.NameNotFoundException) {
            // The package was removed while the list was being built.
        }
    }
    return apps.sortedWith { a, b -> a.name.compareTo(b.name, ignoreCase = true) }
}
