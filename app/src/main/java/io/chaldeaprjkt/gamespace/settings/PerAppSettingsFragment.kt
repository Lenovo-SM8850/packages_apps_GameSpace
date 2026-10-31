/*
 * Copyright (C) 2026 crDroid Android Project
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

import android.app.Activity
import android.app.GameManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView

import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference

import com.android.settingslib.widget.LayoutPreference
import com.android.settingslib.widget.SettingsBasePreferenceFragment

import dagger.hilt.android.AndroidEntryPoint

import io.chaldeaprjkt.gamespace.R
import io.chaldeaprjkt.gamespace.ui.viewmodel.PerAppSettingsViewModel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint(SettingsBasePreferenceFragment::class)
class PerAppSettingsFragment : Hilt_PerAppSettingsFragment(),
    Preference.OnPreferenceChangeListener {

    private val viewModel: PerAppSettingsViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activity?.title = context?.getString(R.string.per_app_title)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.per_app_preferences, rootKey)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Header (game icon + label)
        findPreference<LayoutPreference>(PREF_HEADERS)?.apply {
            isSelectable = false
            findViewById<ImageView>(android.R.id.icon)
                ?.setImageDrawable(viewModel.gameIcon)
            findViewById<TextView>(android.R.id.title)
                ?.text = viewModel.gameLabel
        }

        // Game mode picker — entries supplied dynamically by the ViewModel
        findPreference<ListPreference>(PREF_PREFERRED_MODE)?.apply {
            val opts = viewModel.gameModeOptions
            entries = opts.map { context.getString(it.labelRes) }.toTypedArray()
            entryValues = opts.map { it.mode.toString() }.toTypedArray()
            value = viewModel.preferredMode.toString()
            onPreferenceChangeListener = this@PerAppSettingsFragment
        }

        findPreference<ListPreference>(PREF_ANGLE_DRIVER)?.apply {
            if (!viewModel.angleFeatureAvailable) {
                isVisible = false
                return@apply
            }
            val opts = viewModel.angleDriverOptions
            entries = opts.map { it.second }.toTypedArray()
            entryValues = opts.map { it.first }.toTypedArray()
            value = viewModel.angleDriverChoice
            onPreferenceChangeListener = this@PerAppSettingsFragment
        }

        findPreference<Preference>(PREF_PERFORMANCE)?.apply {
            val tuning = Intent(ACTION_GAME_TUNING)
                .putExtra(EXTRA_PACKAGE_NAME, viewModel.packageName)
            val resolved = context.packageManager.resolveActivity(
                tuning,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_SYSTEM_ONLY.toLong())
            )
            if (resolved == null) {
                isVisible = false
                return@apply
            }
            tuning.component = ComponentName(resolved.activityInfo.packageName, resolved.activityInfo.name)
            setOnPreferenceClickListener {
                startActivity(tuning)
                true
            }
        }

        findPreference<Preference>(PREF_UNREGISTER)?.apply {
            summary = context.getString(R.string.per_app_unregister, viewModel.gameLabel)
            setOnPreferenceClickListener {
                viewModel.unregisterGame()
                activity?.setResult(
                    Activity.RESULT_OK,
                    Intent().apply {
                        putExtra(PREF_UNREGISTER, viewModel.packageName)
                    }
                )
                activity?.finish()
                true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPerformanceSummary()
    }

    // The preset can change in the tuning app, so ask again every time the page comes back.
    private fun refreshPerformanceSummary() {
        val pref = findPreference<Preference>(PREF_PERFORMANCE) ?: return
        if (!pref.isVisible) return
        val ctx = context ?: return
        val pkg = viewModel.packageName
        if (pkg.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val active = withContext(Dispatchers.IO) { queryActivePreset(ctx, pkg) }
            pref.summary = active ?: ctx.getString(R.string.per_app_performance_summary)
        }
    }

    private fun queryActivePreset(ctx: Context, pkg: String): String? = try {
        val mode = ctx.getSystemService(GameManager::class.java)?.getGameMode(pkg)
        if (mode == null) {
            null
        } else {
            val uri = Uri.Builder()
                .scheme("content")
                .authority(PRESETS_AUTHORITY)
                .appendPath("active")
                .appendQueryParameter("package", pkg)
                .appendQueryParameter("mode", mode.toString())
                .build()
            ctx.contentResolver.query(uri, null, null, null, null)?.use {
                if (it.moveToFirst()) {
                    val modeLabel = it.getString(it.getColumnIndexOrThrow("mode_label"))
                    val presetLabel = it.getString(it.getColumnIndexOrThrow("preset_label"))
                    if (modeLabel.isNullOrEmpty() || presetLabel.isNullOrEmpty()) null
                    else ctx.getString(R.string.per_app_performance_active, modeLabel, presetLabel)
                } else {
                    null
                }
            }
        }
    } catch (e: Exception) {
        null
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        return when (preference.key) {
            PREF_PREFERRED_MODE -> {
                val newMode = (newValue as String).toIntOrNull() ?: 1
                viewModel.updatePreferredMode(newMode)
                true
            }
            PREF_ANGLE_DRIVER -> {
                viewModel.updateAngleDriverChoice(newValue as String)
                true
            }
            else -> false
        }
    }

    companion object {
        const val PREF_HEADERS = "headers"
        const val PREF_PREFERRED_MODE = "per_app_preferred_mode"
        const val PREF_ANGLE_DRIVER = "per_app_angle_driver"
        const val PREF_PERFORMANCE = "per_app_performance"
        const val PREF_UNREGISTER = "per_app_unregister"
        private const val ACTION_GAME_TUNING = "io.github.miner7222.gametuning.action.GAME_TUNING"
        private const val EXTRA_PACKAGE_NAME = "package_name"
        private const val PRESETS_AUTHORITY = "io.github.miner7222.gametuning.presets"
    }
}
