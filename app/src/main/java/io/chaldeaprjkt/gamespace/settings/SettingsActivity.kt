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

import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import io.chaldeaprjkt.gamespace.R
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint(CollapsingToolbarBaseActivity::class)
class SettingsActivity : Hilt_SettingsActivity() {
    companion object {
        const val EXTRA_EDIT_QUICK_APPS = "edit_quick_start_apps"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(if (intent.hasExtra("referer")) R.string.settings_title else R.string.tile_settings)
        appBarLayout?.setExpanded(false, false)
        findViewById<android.view.View>(com.android.settingslib.collapsingtoolbar.R.id.content_frame)?.apply {
            val params = layoutParams
            params.width = (minOf(resources.configuration.screenWidthDp, 840) * resources.displayMetrics.density).toInt()
            when (params) {
                is FrameLayout.LayoutParams -> params.gravity = Gravity.CENTER_HORIZONTAL
                is android.widget.LinearLayout.LayoutParams -> params.gravity = Gravity.CENTER_HORIZONTAL
                is androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams -> params.gravity = Gravity.CENTER_HORIZONTAL
            }
            layoutParams = params
        }
        if (savedInstanceState == null) {
            val fragment = SettingsFragment()
            supportFragmentManager
                .beginTransaction()
                .replace(
                    com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                    fragment
                )
                .runOnCommit {
                    if (intent.getBooleanExtra(EXTRA_EDIT_QUICK_APPS, false)) fragment.view?.post {
                        fragment.findPreference<androidx.preference.Preference>(
                            io.chaldeaprjkt.gamespace.data.AppSettings.KEY_QUICK_START_APPS)?.let { preference ->
                            fragment.scrollToPreference(preference)
                            fragment.onDisplayPreferenceDialog(preference)
                        }
                    }
                }
                .commit()
        }
    }
}
