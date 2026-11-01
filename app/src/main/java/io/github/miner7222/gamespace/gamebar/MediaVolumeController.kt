// SPDX-FileCopyrightText: 2026 miner7222
// SPDX-License-Identifier: Apache-2.0

package io.github.miner7222.gamespace.gamebar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import com.android.settingslib.media.MediaOutputConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class MediaVolumeState(
    val current: Int = 0,
    val maximum: Int = 0,
    val minimum: Int = 0,
    val fixed: Boolean = true,
    val available: Boolean = false,
)

@Singleton
class MediaVolumeController @Inject constructor(@ApplicationContext private val context: Context) {
    private val audio by lazy { context.getSystemService(AudioManager::class.java) }
    private val mutableState = MutableStateFlow(MediaVolumeState())
    val state: StateFlow<MediaVolumeState> = mutableState.asStateFlow()
    private var listening = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (listening) refresh()
        }
    }

    fun start() {
        if (!listening) {
            context.registerReceiver(receiver, IntentFilter(AudioManager.VOLUME_CHANGED_ACTION).apply {
                addAction(AudioManager.STREAM_DEVICES_CHANGED_ACTION)
            }, null, Handler(Looper.getMainLooper()), Context.RECEIVER_EXPORTED)
            listening = true
        }
        refresh()
    }

    fun refresh() {
        mutableState.value = try {
            val minimum = audio.getStreamMinVolume(AudioManager.STREAM_MUSIC)
            val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(minimum)
            MediaVolumeState(audio.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(minimum, maximum),
                maximum, minimum, audio.isVolumeFixed || maximum == minimum, available = true)
        } catch (_: RuntimeException) {
            MediaVolumeState()
        }
    }

    /** Returns whether the requested index was read back; policy may reject or limit it. */
    fun setVolume(index: Int): Boolean {
        refresh()
        val before = state.value
        if (!before.available || before.fixed) return false
        val target = index.coerceIn(before.minimum, before.maximum)
        return try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            refresh()
            state.value.available && state.value.current == target
        } catch (_: RuntimeException) {
            refresh()
            false
        }
    }

    /** Sends a system-routing request; the overlay caller closes its panel before this call. */
    fun openOutputSwitcher(): Boolean = try {
        val user = UserHandle.of(context.userId)
        context.sendBroadcastAsUser(Intent(MediaOutputConstants.ACTION_LAUNCH_SYSTEM_MEDIA_OUTPUT_DIALOG)
            .setPackage(MediaOutputConstants.SYSTEMUI_PACKAGE_NAME)
            .putExtra(MediaOutputConstants.EXTRA_USER_HANDLE, user), user)
        true
    } catch (_: RuntimeException) {
        false
    }

    fun dispose() {
        if (listening) context.unregisterReceiver(receiver)
        listening = false
        mutableState.value = MediaVolumeState()
    }
}
