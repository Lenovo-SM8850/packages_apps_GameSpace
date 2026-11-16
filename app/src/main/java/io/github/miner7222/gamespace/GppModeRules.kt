// SPDX-FileCopyrightText: 2026 miner7222
// SPDX-License-Identifier: Apache-2.0
package io.github.miner7222.gamespace

/** Decisions shared by the UI and the host transition checks. Android game-mode IDs. */
object GppModeRules {
    fun modeForChoice(mode: Int, choice: Int): Int =
        if (choice > 0 && (mode == 1 || mode == 3)) 2 else mode
    fun keepFeatures(mode: Int): Boolean = mode == 2
    fun rememberedChoice(previous: Int, choice: Int): Int = if (choice > 0) choice else previous

    /** Output rates for frame interpolation [factor] (1 = 2x, 2 = 3x); 0 = Auto comes first. */
    fun targetChoices(factor: Int): List<Int> = when (factor) {
        1 -> listOf(0, 60, 90, 120, 144, 165)
        2 -> listOf(0, 90, 120, 144, 165)
        else -> listOf(0)
    }

    /** Super resolution limits the source frames to 60 fps, so higher targets cannot be reached. */
    fun targetSelectable(factor: Int, superResolution: Boolean, target: Int): Boolean =
        target in targetChoices(factor) && (!superResolution || target <= SR_SOURCE_LIMIT * (factor + 1))

    /**
     * The target that applies: Auto when [stored] is not offered for [factor], else the highest
     * selectable target not above [stored]. The stored value itself is kept.
     */
    fun effectiveTarget(factor: Int, superResolution: Boolean, stored: Int): Int {
        if (stored !in targetChoices(factor)) return 0
        return targetChoices(factor).filter { it <= stored && targetSelectable(factor, superResolution, it) }.max()
    }

    private const val SR_SOURCE_LIMIT = 60
}
