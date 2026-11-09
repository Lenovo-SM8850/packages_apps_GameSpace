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

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat

import com.android.settingslib.widget.SettingsBasePreferenceFragment

import dagger.hilt.android.AndroidEntryPoint

import io.chaldeaprjkt.gamespace.R
import io.github.miner7222.gamespace.library.HideGamesPreference
import io.github.miner7222.gamespace.library.LibraryActivity
import io.chaldeaprjkt.gamespace.utils.di.ServiceViewEntryPoint
import io.chaldeaprjkt.gamespace.utils.entryPointOf

@AndroidEntryPoint(SettingsBasePreferenceFragment::class)
class SettingsFragment : Hilt_SettingsFragment() {

    private val systemSettings by lazy {
        requireContext().entryPointOf<ServiceViewEntryPoint>().systemSettings()
    }

    private val appSettings by lazy {
        requireContext().entryPointOf<ServiceViewEntryPoint>().appSettings()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        appSettings.inGameOptions.ensureMigrated()
        appSettings.migrateHandleOpacity()
        setPreferencesFromResource(R.xml.root_preferences, rootKey)
        arguments?.getString("section")?.let { section ->
            val keep = if (section == "main")
                setOf("library_preferences", "global_in_game_options") else setOf(section)
            for (index in preferenceScreen.preferenceCount - 1 downTo 0) {
                val preference = preferenceScreen.getPreference(index)
                if (preference.key !in keep) preferenceScreen.removePreference(preference)
            }
        }
    }

    fun setInlineLayout(inline: Boolean) {
        arguments?.putBoolean("inline", inline)
        val height = if (inline) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT
        view?.let { root ->
            root.findViewById<View>(android.R.id.list_container)?.let { container ->
                val params = container.layoutParams
                params.height = if (inline) ViewGroup.LayoutParams.WRAP_CONTENT
                    else if (params is LinearLayout.LayoutParams) 0 else ViewGroup.LayoutParams.MATCH_PARENT
                if (params is LinearLayout.LayoutParams) params.weight = if (inline) 0f else 1f
                container.layoutParams = params
            }
            root.layoutParams?.let { params ->
                if (params.height != height) {
                    params.height = height
                    root.layoutParams = params
                }
            }
        }
        listView?.apply {
            isNestedScrollingEnabled = !inline
            isVerticalScrollBarEnabled = !inline
            layoutParams?.let { params ->
                if (params.height != height) {
                    params.height = height
                    layoutParams = params
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setInlineLayout(arguments?.getBoolean("inline", false) == true)
        findPreference<Preference>("global_in_game_options")?.setOnPreferenceClickListener {
            (activity as? SettingsActivity)?.openGlobalInGameOptions()
            true
        }
        findPreference<SwitchPreferenceCompat>("gamespace_auto_game_detect")?.apply {
            isChecked = systemSettings.autoGameDetect
            setOnPreferenceChangeListener { _, value ->
                systemSettings.autoGameDetect = value as Boolean
                true
            }
        }
        findPreference<androidx.preference.ListPreference>("gamespace_default_game_mode")?.apply {
            value = systemSettings.libraryDefaultMode.toString()
            setOnPreferenceChangeListener { _, selected ->
                systemSettings.libraryDefaultMode = (selected as String).toInt()
                true
            }
        }
        findPreference<Preference>("library_open")?.apply {
            isVisible = activity?.intent?.hasExtra("referer") == true
            setOnPreferenceClickListener {
                startActivity(Intent(context, LibraryActivity::class.java))
                true
            }
        }

    }

    override fun onResume() {
        super.onResume()
        findPreference<androidx.preference.ListPreference>("gamespace_default_game_mode")?.value =
            systemSettings.libraryDefaultMode.toString()
        findPreference<SwitchPreferenceCompat>("gamespace_auto_game_detect")?.isChecked =
            systemSettings.autoGameDetect
        findPreference<HideGamesPreference>("gamespace_hide_games")?.refresh()
        val count = systemSettings.userGames.size
        findPreference<Preference>("library_open")?.summary = resources.getQuantityString(
            R.plurals.library_game_count, count, count)
    }

}
