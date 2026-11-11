// SPDX-FileCopyrightText: 2026 miner7222
// SPDX-License-Identifier: Apache-2.0

package io.github.miner7222.gamespace.gamebar

import android.app.ActivityManager
import android.content.Context
import android.os.IThermalService
import android.os.ServiceManager
import android.os.Temperature
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.preference.PreferenceManager
import io.chaldeaprjkt.gamespace.R
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** A value the performance overlay can show: its name in the list and its short overlay label. */
enum class PerfItem(val id: String, val nameRes: Int, val labelRes: Int) {
    FPS("fps", R.string.perf_item_fps, R.string.gamebar_fps_label),
    CPU_CLOCK("cpu_clock", R.string.perf_item_cpu_clock, R.string.perf_label_cpu),
    GPU_CLOCK("gpu_clock", R.string.perf_item_gpu_clock, R.string.perf_label_gpu),
    BATTERY_TEMP("battery_temp", R.string.perf_item_battery_temp, R.string.perf_label_battery),
    BATTERY_LEVEL("battery_level", R.string.perf_item_battery_level, R.string.perf_label_level),
    CPU_TEMP("cpu_temp", R.string.perf_item_cpu_temp, R.string.perf_label_cpu),
    GPU_TEMP("gpu_temp", R.string.perf_item_gpu_temp, R.string.perf_label_gpu),
    RAM("ram", R.string.perf_item_ram, R.string.perf_label_ram),
}

/** One row of the list: an item and whether the overlay shows it. */
data class PerfEntry(val item: PerfItem, val shown: Boolean)

/**
 * The overlay's items in their order, shared by every game. The first five are shown by default;
 * the others can be turned on.
 */
object PerfOverlayItems {
    /** The most items the overlay shows; the overlay stays within a portrait screen. */
    const val MAX_SHOWN = 5
    private const val KEY = "perf_overlay_items"
    private val DEFAULT_SHOWN = setOf(PerfItem.FPS, PerfItem.CPU_CLOCK, PerfItem.GPU_CLOCK,
        PerfItem.BATTERY_TEMP, PerfItem.BATTERY_LEVEL)
    private var state: MutableState<List<PerfEntry>>? = null

    fun entries(context: Context): State<List<PerfEntry>> =
        state ?: mutableStateOf(read(context)).also { state = it }

    fun save(context: Context, entries: List<PerfEntry>) {
        (state ?: mutableStateOf(entries).also { state = it }).value = entries.toList()
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(KEY, entries.joinToString(",") { "${it.item.id}:${if (it.shown) 1 else 0}" })
            .apply()
    }

    private fun read(context: Context): List<PerfEntry> {
        val saved = PreferenceManager.getDefaultSharedPreferences(context).getString(KEY, null)
            ?.split(",")?.mapNotNull { part ->
                val (id, shown) = part.split(":").takeIf { it.size == 2 } ?: return@mapNotNull null
                PerfItem.entries.firstOrNull { it.id == id }?.let { PerfEntry(it, shown == "1") }
            }?.distinctBy { it.item }.orEmpty()
        // Items added in a later version join at the end, off.
        val missing = PerfItem.entries.filter { item -> saved.none { it.item == item } }
            .map { PerfEntry(it, saved.isEmpty() && it in DEFAULT_SHOWN) }
        var shown = 0
        val list = (saved + missing).map { entry ->
            if (entry.shown && ++shown > MAX_SHOWN) entry.copy(shown = false) else entry
        }
        return list.takeIf { it.any { entry -> entry.shown } }
            ?: PerfItem.entries.map { PerfEntry(it, it in DEFAULT_SHOWN) }
    }
}

/** The sampled values; null where the device does not report one. */
data class PerfValues(
    val cpuGhz: Float? = null,
    val gpuMhz: Int? = null,
    val cpuTemp: Float? = null,
    val gpuTemp: Float? = null,
    val ramPercent: Int? = null,
)

/** Samples the values the shown items need, once a second while composed. */
@Composable
fun rememberPerfValues(items: Set<PerfItem>): State<PerfValues> {
    val context = LocalContext.current
    return produceState(PerfValues(), items) {
        while (true) {
            value = withContext(Dispatchers.IO) { samplePerf(context, items) }
            delay(1000L)
        }
    }
}

private fun samplePerf(context: Context, items: Set<PerfItem>): PerfValues {
    val thermal = if (items.any { it == PerfItem.CPU_TEMP || it == PerfItem.GPU_TEMP }) {
        runCatching {
            IThermalService.Stub.asInterface(ServiceManager.getService(Context.THERMAL_SERVICE))
        }.getOrNull()
    } else null
    fun hottest(type: Int): Float? = runCatching {
        thermal?.getCurrentTemperaturesWithType(type)?.maxOfOrNull { it.value }
    }.getOrNull()?.takeIf { it.isFinite() && it > -50f }
    return PerfValues(
        // The fastest core: the clock the game's busiest thread can run at right now.
        cpuGhz = if (PerfItem.CPU_CLOCK in items) {
            File("/sys/devices/system/cpu/cpufreq").listFiles { f -> f.name.startsWith("policy") }
                ?.mapNotNull { readLong(File(it, "scaling_cur_freq")) }?.maxOrNull()
                ?.let { it / 1_000_000f }
        } else null,
        gpuMhz = if (PerfItem.GPU_CLOCK in items) {
            (readLong(File("/sys/class/kgsl/kgsl-3d0/gpuclk"))
                ?: readLong(File("/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq")))
                ?.let { (it / 1_000_000L).toInt() }
        } else null,
        cpuTemp = if (PerfItem.CPU_TEMP in items) hottest(Temperature.TYPE_CPU) else null,
        gpuTemp = if (PerfItem.GPU_TEMP in items) hottest(Temperature.TYPE_GPU) else null,
        ramPercent = if (PerfItem.RAM in items) {
            val info = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
            if (info.totalMem > 0) ((info.totalMem - info.availMem) * 100 / info.totalMem).toInt()
            else null
        } else null,
    )
}

private fun readLong(file: File): Long? =
    runCatching { file.readText().trim().toLong() }.getOrNull()?.takeIf { it > 0 }

/** The text the overlay shows for [item]. */
@Composable
fun perfValueText(item: PerfItem, fps: String, values: PerfValues, batteryTemp: Float,
    batteryLevel: Int): String {
    val context = LocalContext.current
    fun degrees(value: Float?) =
        value?.let { context.getString(R.string.dashboard_temperature, it.roundToInt()) } ?: "–"
    return when (item) {
        PerfItem.FPS -> fps.ifEmpty { "–" }
        PerfItem.CPU_CLOCK -> values.cpuGhz?.let {
            context.getString(R.string.perf_value_ghz, String.format(Locale.ROOT, "%.2f", it))
        } ?: "–"
        PerfItem.GPU_CLOCK -> values.gpuMhz?.let { context.getString(R.string.perf_value_mhz, it) } ?: "–"
        PerfItem.BATTERY_TEMP -> degrees(batteryTemp)
        PerfItem.BATTERY_LEVEL -> if (batteryLevel >= 0) "$batteryLevel%" else "–"
        PerfItem.CPU_TEMP -> degrees(values.cpuTemp)
        PerfItem.GPU_TEMP -> degrees(values.gpuTemp)
        PerfItem.RAM -> values.ramPercent?.let { "$it%" } ?: "–"
    }
}

/**
 * The overlay's items: a check shows an item, the handle moves it. The order runs left to right
 * on the overlay. At least one item stays shown.
 */
@Composable
fun androidx.compose.foundation.layout.BoxScope.PerfOverlaySheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val entries = remember { PerfOverlayItems.entries(context).value.toMutableStateList() }
    val rowHeight = 52.dp
    val rowPx = with(LocalDensity.current) { rowHeight.toPx() }
    var dragged by remember { mutableStateOf<PerfItem?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val save = { PerfOverlayItems.save(context, entries) }
    DashboardDetailSheet(stringResource(R.string.tile_fps_info), onBack = onDismiss) {
        Text(stringResource(R.string.perf_overlay_items_summary),
            Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column {
            entries.forEach { entry ->
                key(entry.item) {
                    val moving = dragged == entry.item
                    val lastShown = entry.shown && entries.count { it.shown } == 1
                    val full = !entry.shown &&
                        entries.count { it.shown } >= PerfOverlayItems.MAX_SHOWN
                    val enabled = !lastShown && !full
                    val name = stringResource(entry.item.nameRes)
                    val moveUp = stringResource(R.string.perf_overlay_move_up)
                    val moveDown = stringResource(R.string.perf_overlay_move_down)
                    fun move(by: Int): Boolean {
                        val from = entries.indexOfFirst { it.item == entry.item }
                        val to = from + by
                        if (from < 0 || to !in entries.indices) return false
                        entries.add(to, entries.removeAt(from))
                        save()
                        return true
                    }
                    Row(Modifier.fillMaxWidth().height(rowHeight)
                        .zIndex(if (moving) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (moving) offset else 0f
                            shadowElevation = if (moving) 6.dp.toPx() else 0f
                        },
                        verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).fillMaxHeight()
                            .toggleable(value = entry.shown, enabled = enabled, role = Role.Checkbox) { shown ->
                                val index = entries.indexOfFirst { it.item == entry.item }
                                if (index >= 0) {
                                    entries[index] = entries[index].copy(shown = shown)
                                    save()
                                }
                            },
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = entry.shown, enabled = enabled, onCheckedChange = null)
                            Text(name, Modifier.padding(start = 12.dp),
                                style = MaterialTheme.typography.bodyLarge)
                        }
                        Icon(Icons.Rounded.DragHandle, stringResource(R.string.perf_overlay_move, name),
                            Modifier.size(48.dp)
                                .semantics {
                                    customActions = listOf(
                                        CustomAccessibilityAction(moveUp) { move(-1) },
                                        CustomAccessibilityAction(moveDown) { move(1) },
                                    )
                                }
                                .padding(12.dp).pointerInput(entry.item) {
                                detectDragGestures(
                                    onDragStart = { dragged = entry.item; offset = 0f },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        offset += amount.y
                                        val from = entries.indexOfFirst { it.item == entry.item }
                                        val to = (from + (offset / rowPx).roundToInt())
                                            .coerceIn(0, entries.lastIndex)
                                        if (from >= 0 && to != from) {
                                            entries.add(to, entries.removeAt(from))
                                            offset -= (to - from) * rowPx
                                        }
                                    },
                                    onDragEnd = { dragged = null; offset = 0f; save() },
                                    onDragCancel = { dragged = null; offset = 0f; save() },
                                )
                            },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
