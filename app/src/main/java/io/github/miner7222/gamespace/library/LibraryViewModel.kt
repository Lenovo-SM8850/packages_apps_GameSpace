// SPDX-FileCopyrightText: 2026 miner7222
// SPDX-License-Identifier: Apache-2.0

package io.github.miner7222.gamespace.library

import android.app.usage.UsageStatsManager
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.drawable.AdaptiveIconDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.palette.graphics.Palette
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.chaldeaprjkt.gamespace.data.SystemSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import javax.inject.Inject

data class LibraryGame(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap,
    val iconMask: Path?,
    val accent: Int,
    val mode: Int,
    val lastPlayed: Long,
)

data class LibraryState(
    val games: List<LibraryGame> = emptyList(),
    val loading: Boolean = true,
    val autoDetect: Boolean = true,
    val hideGames: Boolean = false,
    val homeExceptions: Set<String> = emptySet(),
    val hintDismissed: Boolean = false,
    val sort: Int = 0,
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SystemSettings,
    val repository: LibraryRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(LibraryState())
    val state = mutableState.asStateFlow()
    private var refreshJob: Job? = null
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) { refresh() }
    }

    init {
        listOf(Settings.System.getUriFor(LibraryKeys.GAMES),
            Settings.System.getUriFor("gamespace_auto_game_detect"),
            Settings.Secure.getUriFor(LibraryKeys.HIDE),
            Settings.Secure.getUriFor(LibraryKeys.EXCEPTIONS),
            Settings.Secure.getUriFor(LibraryKeys.HINT),
            Settings.Secure.getUriFor(LibraryKeys.SORT)).forEach {
            context.contentResolver.registerContentObserver(it, false, observer)
        }
        refresh()
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            mutableState.value = withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val usage = try {
                    context.getSystemService(UsageStatsManager::class.java)
                        .queryAndAggregateUsageStats(now - 365L * 24 * 60 * 60 * 1000, now)
                } catch (_: Exception) { emptyMap() }
                val pm = context.packageManager
                val games = settings.userGames.distinctBy { it.packageName }.mapNotNull { game ->
                    try {
                        val info = pm.getApplicationInfo(game.packageName, android.content.pm.PackageManager.ApplicationInfoFlags.of(0))
                        val drawable = info.loadIcon(pm)
                        val size = (66 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        drawable.setBounds(0, 0, size, size)
                        val mask = if (drawable is AdaptiveIconDrawable) {
                            val mask = Path(drawable.iconMask)
                            val inset = size / 4
                            listOf(drawable.background, drawable.foreground).forEach { layer ->
                                layer?.setBounds(-inset, -inset, size + inset, size + inset)
                                layer?.draw(canvas)
                            }
                            mask
                        } else {
                            drawable.draw(canvas)
                            null
                        }
                        LibraryGame(game.packageName, info.loadLabel(pm).toString(), bitmap.asImageBitmap(),
                            mask, Palette.from(bitmap).generate().getDominantColor(0), game.mode,
                            usage[game.packageName]?.lastTimeUsed ?: 0L)
                    } catch (_: android.content.pm.PackageManager.NameNotFoundException) { null }
                }
                val sort = settings.librarySort
                val collator = Collator.getInstance()
                val comparator = Comparator<LibraryGame> { a, b -> collator.compare(a.label, b.label) }
                LibraryState(
                    games = if (sort == 1) games.sortedWith(comparator)
                        else games.sortedWith(compareByDescending<LibraryGame> { it.lastPlayed }.then(comparator)),
                    loading = false,
                    autoDetect = settings.autoGameDetect,
                    hideGames = settings.libraryHideGames,
                    homeExceptions = settings.libraryHomeExceptions,
                    hintDismissed = settings.libraryHintDismissed,
                    sort = sort,
                )
            }
        }
    }

    fun register(packageName: String) { repository.register(packageName); refresh() }
    fun setSort(sort: Int) { settings.librarySort = sort; refresh() }
    fun dismissHint() { settings.libraryHintDismissed = true; refresh() }
    fun enableAutoDetection() { settings.autoGameDetect = true; refresh() }
    fun showOnHome(packageName: String, enabled: Boolean) {
        settings.libraryHomeExceptions = if (enabled) settings.libraryHomeExceptions + packageName
            else settings.libraryHomeExceptions - packageName
        LauncherVisibility.sync(context, context.userId)
        refresh()
    }

    override fun onCleared() { context.contentResolver.unregisterContentObserver(observer) }
}
