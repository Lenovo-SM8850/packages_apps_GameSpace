// SPDX-FileCopyrightText: 2026 miner7222
// SPDX-License-Identifier: Apache-2.0

package io.github.miner7222.gamespace.library

import android.content.Context
import android.util.AttributeSet
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.chaldeaprjkt.gamespace.R
import io.chaldeaprjkt.gamespace.utils.di.ServiceViewEntryPoint
import io.chaldeaprjkt.gamespace.utils.entryPointOf

class HideGamesPreference @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    SwitchPreferenceCompat(context, attrs) {
    private val settings by lazy { context.entryPointOf<ServiceViewEntryPoint>().systemSettings() }

    init {
        isPersistent = false
        setOnPreferenceChangeListener { _, value ->
            if (value == true) {
                confirmHiding()
                false
            } else {
                settings.libraryHideGames = false
                LauncherVisibility.sync(context, context.userId)
                true
            }
        }
    }

    override fun onAttached() {
        super.onAttached()
        refresh()
    }

    fun refresh() { isChecked = settings.libraryHideGames }

    private fun confirmHiding() {
        val dialogContext = ContextThemeWrapper(context, R.style.LibraryTheme)
        val content = LayoutInflater.from(dialogContext).inflate(R.layout.library_hide_confirmation, null)
        MaterialAlertDialogBuilder(dialogContext)
            .setTitle(R.string.library_hide_confirmation_title)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.library_hide_action) { _, _ ->
                settings.libraryHideGames = true
                LauncherVisibility.sync(context, context.userId)
                refresh()
            }.show().apply {
                val width = minOf(context.resources.configuration.screenWidthDp - 48, 560)
                window?.setLayout((width * context.resources.displayMetrics.density).toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT)
            }
    }
}
