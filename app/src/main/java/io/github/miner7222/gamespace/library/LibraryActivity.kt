// SPDX-FileCopyrightText: 2026 miner7222
// SPDX-License-Identifier: Apache-2.0

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package io.github.miner7222.gamespace.library

import android.app.Activity
import android.app.ComponentCaller
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Process
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.chaldeaprjkt.gamespace.preferences.AppListPreferences
import io.chaldeaprjkt.gamespace.preferences.appselector.AppSelectorActivity
import io.chaldeaprjkt.gamespace.settings.SettingsActivity
import io.chaldeaprjkt.gamespace.ui.viewmodel.PerAppSettingsViewModel

enum class GameSettingsDestination { MODE, IN_GAME_OPTIONS }

data class GameSettingsRequest(val packageName: String, val destination: GameSettingsDestination)

@AndroidEntryPoint(ComponentActivity::class)
class LibraryActivity : Hilt_LibraryActivity() {
    private val library: LibraryViewModel by viewModels()
    private val gameSettings: PerAppSettingsViewModel by viewModels()
    private var resumeGeneration by mutableIntStateOf(0)
    private var settingsRequest by mutableStateOf<GameSettingsRequest?>(null)
    private val selector = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            it.data?.getStringExtra(AppListPreferences.EXTRA_APP)?.let(library::register)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            receiveSettingsIntent(intent, launchedFromUid)
        } else {
            settingsRequest = readSettingsRequest(savedInstanceState.getString(EXTRA_GAME_PACKAGE),
                savedInstanceState.getString(EXTRA_DESTINATION))
        }
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
                    onSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                    settingsRequest = settingsRequest,
                    onSettingsRequestHandled = { settingsRequest = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent, caller: ComponentCaller) {
        super.onNewIntent(intent, caller)
        setIntent(intent, caller)
        receiveSettingsIntent(intent, caller.uid)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        settingsRequest?.let {
            outState.putString(EXTRA_GAME_PACKAGE, it.packageName)
            outState.putString(EXTRA_DESTINATION, it.destination.name)
        }
        super.onSaveInstanceState(outState)
    }

    private fun receiveSettingsIntent(intent: Intent, callerUid: Int) {
        if (intent.action != ACTION_GAME_SETTINGS || callerUid < 0) return
        // The launcher remains public; only same-signature callers may use routing extras.
        // Use the framework's caller identity for this delivery, never an intent extra.
        if (packageManager.checkSignatures(Process.myUid(), callerUid) != PackageManager.SIGNATURE_MATCH) return
        settingsRequest = readSettingsRequest(intent.getStringExtra(EXTRA_GAME_PACKAGE),
            intent.getStringExtra(EXTRA_DESTINATION))
    }

    private fun readSettingsRequest(packageName: String?, destination: String?): GameSettingsRequest? {
        if (packageName.isNullOrBlank()) return null
        val mode = ModePreferences.rawMode(this, packageName) ?: return null
        if (mode !in 0..3) return null
        val target = GameSettingsDestination.entries.firstOrNull { it.name == destination } ?: return null
        return GameSettingsRequest(packageName, target)
    }

    companion object {
        private const val ACTION_GAME_SETTINGS = "io.github.miner7222.gamespace.action.GAME_SETTINGS"
        private const val EXTRA_GAME_PACKAGE = "game_package"
        private const val EXTRA_DESTINATION = "game_settings_destination"

        fun gameSettingsIntent(context: Context, packageName: String,
            destination: GameSettingsDestination): Intent = Intent(context, LibraryActivity::class.java)
            .setAction(ACTION_GAME_SETTINGS)
            .putExtra(EXTRA_GAME_PACKAGE, packageName)
            .putExtra(EXTRA_DESTINATION, destination.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    override fun onResume() {
        super.onResume()
        resumeGeneration++
        library.refresh()
    }
}
