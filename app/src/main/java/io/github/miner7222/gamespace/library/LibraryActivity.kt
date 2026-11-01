// SPDX-FileCopyrightText: 2026 miner7222
// SPDX-License-Identifier: Apache-2.0

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package io.github.miner7222.gamespace.library

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.chaldeaprjkt.gamespace.preferences.AppListPreferences
import io.chaldeaprjkt.gamespace.preferences.appselector.AppSelectorActivity
import io.chaldeaprjkt.gamespace.settings.SettingsActivity
import io.chaldeaprjkt.gamespace.ui.viewmodel.PerAppSettingsViewModel

@AndroidEntryPoint(ComponentActivity::class)
class LibraryActivity : Hilt_LibraryActivity() {
    private val library: LibraryViewModel by viewModels()
    private val gameSettings: PerAppSettingsViewModel by viewModels()
    private var resumeGeneration by mutableIntStateOf(0)
    private val selector = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            it.data?.getStringExtra(AppListPreferences.EXTRA_APP)?.let(library::register)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by library.state.collectAsStateWithLifecycle()
            MaterialExpressiveTheme(
                colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(this)
                    else dynamicLightColorScheme(this),
                motionScheme = MotionScheme.expressive(),
            ) {
                LibraryScreen(state, library, gameSettings, resumeGeneration,
                    onAdd = { selector.launch(Intent(this, AppSelectorActivity::class.java)) },
                    onSettings = { startActivity(Intent(this, SettingsActivity::class.java)) })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeGeneration++
        library.refresh()
    }
}
