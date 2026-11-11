// SPDX-FileCopyrightText: 2026 miner7222
// SPDX-License-Identifier: Apache-2.0
package io.github.miner7222.gamespace

/** Decisions shared by the UI and the host transition checks. Android game-mode IDs. */
object GppModeRules {
    fun modeForChoice(mode: Int, choice: Int): Int =
        if (choice > 0 && (mode == 1 || mode == 3)) 2 else mode
    fun keepFeatures(mode: Int): Boolean = mode == 2
    fun rememberedChoice(previous: Int, choice: Int): Int = if (choice > 0) choice else previous
}
