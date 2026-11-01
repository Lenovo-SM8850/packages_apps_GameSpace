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

import androidx.activity.result.contract.ActivityResultContracts
import io.chaldeaprjkt.gamespace.preferences.AppListPreferences
import io.chaldeaprjkt.gamespace.preferences.appselector.AppSelectorActivity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.net.wifi.WifiManager
import io.chaldeaprjkt.gamespace.data.AppSettings
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemProperties
import android.os.Vibrator
import android.view.View
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat

import com.android.settingslib.widget.SettingsBasePreferenceFragment

import dagger.hilt.android.AndroidEntryPoint

import io.chaldeaprjkt.gamespace.R
import io.github.miner7222.gamespace.library.HideGamesPreference
import io.github.miner7222.gamespace.library.LibraryActivity
import io.chaldeaprjkt.gamespace.utils.di.ServiceViewEntryPoint
import io.chaldeaprjkt.gamespace.utils.entryPointOf
import io.chaldeaprjkt.gamespace.preferences.QuickStartAppPreference
import io.chaldeaprjkt.gamespace.preferences.QuickStartAppPreferenceDialogFragment

@AndroidEntryPoint(SettingsBasePreferenceFragment::class)
class SettingsFragment : Hilt_SettingsFragment(),
    QuickStartAppPreferenceDialogFragment.QuickStartAppListener {

    private val systemSettings by lazy {
        requireContext().entryPointOf<ServiceViewEntryPoint>().systemSettings()
    }

    private val appSettings by lazy {
        requireContext().entryPointOf<ServiceViewEntryPoint>().appSettings()
    }

    private var sidebarOptionsObservation: AutoCloseable? = null

    private fun updateSidebarPreference() {
        findPreference<Preference>("gamespace_high_touch_polling_enabled")?.isVisible =
            io.github.miner7222.gamespace.library.SidebarVisibility.isHighTouchSupported(requireContext())
        findPreference<Preference>("gamespace_hide_sidebar")?.isVisible =
            io.github.miner7222.gamespace.library.SidebarVisibility.isAvailable(requireContext())
    }

    private val wifiStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateWifiPreference()
        }
    }

    private fun updateWifiPreference() {
        findPreference<Preference>(AppSettings.KEY_WIFI_LOW_LATENCY)?.isVisible =
            appSettings.wifiLowLatencySupported
    }

    override fun onStart() {
        super.onStart()
        sidebarOptionsObservation = appSettings.inGameOptions.observe { updateSidebarPreference() }
        requireContext().registerReceiver(wifiStateReceiver,
            IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION), Context.RECEIVER_EXPORTED)
    }

    override fun onStop() {
        sidebarOptionsObservation?.close()
        sidebarOptionsObservation = null
        requireContext().unregisterReceiver(wifiStateReceiver)
        super.onStop()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)
        updatePreferences()
    }

    private fun hasVibrator(): Boolean {
        val vibrator = context?.getSystemService(Vibrator::class.java)
        return vibrator?.hasVibrator() == true
    }

    private fun updatePreferences() {
        updateWifiPreference()
        updateSidebarPreference()
        val isBypassSupported =
            Build.MANUFACTURER.equals("Google", ignoreCase = true) ||
            SystemProperties.getBoolean("persist.sys.battery_bypass_supported", false)

        if (!isBypassSupported) {
            findPreference<PreferenceCategory>("in_game_preferences")
                ?.removePreference(findPreference("bypass_charge_enabled")!!)
        }

        if (!hasVibrator()) {
            findPreference<PreferenceCategory>("in_game_preferences")
                ?.removePreference(findPreference("gamespace_pulse_bass_haptics_disabled")!!)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

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
        findPreference<SwitchPreferenceCompat>("bypass_charge_enabled")?.apply {
            isChecked = appSettings.inGameOptions.global(io.github.miner7222.gamespace.library.GameOption.BYPASS) == 1
            setOnPreferenceChangeListener { _, value ->
                appSettings.inGameOptions.setBypassDefault(value as Boolean)
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
        updateWifiPreference()
        updateSidebarPreference()
        findPreference<androidx.preference.ListPreference>("gamespace_default_game_mode")?.value =
            systemSettings.libraryDefaultMode.toString()
        findPreference<SwitchPreferenceCompat>("bypass_charge_enabled")?.isChecked =
            appSettings.inGameOptions.global(io.github.miner7222.gamespace.library.GameOption.BYPASS) == 1
        findPreference<SwitchPreferenceCompat>("gamespace_auto_game_detect")?.isChecked =
            systemSettings.autoGameDetect
        findPreference<HideGamesPreference>("gamespace_hide_games")?.refresh()
        val count = systemSettings.userGames.size
        findPreference<Preference>("library_open")?.summary = resources.getQuantityString(
            R.plurals.library_game_count, count, count)
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (preference is QuickStartAppPreference) {
            val dialogFragment =
                QuickStartAppPreferenceDialogFragment.newInstance(preference.key)
            dialogFragment.setListener(this)
            dialogFragment.setTargetFragment(this, 0)
            dialogFragment.show(parentFragmentManager, "QuickStartAppPreferenceDialogFragment")
        } else {
            super.onDisplayPreferenceDialog(preference)
        }
    }

    override fun getSavedQuickStartApps(): String {
        val prefs = preferenceManager.sharedPreferences ?: return ""
        return prefs.getString(io.chaldeaprjkt.gamespace.data.AppSettings.KEY_QUICK_START_APPS, "") ?: ""
    }

    override fun saveQuickStartApps(apps: String) { /* no-op */ }

}
