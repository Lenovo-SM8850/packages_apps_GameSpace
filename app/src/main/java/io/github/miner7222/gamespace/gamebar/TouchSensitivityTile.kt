// SPDX-FileCopyrightText: 2026 miner7222
// SPDX-License-Identifier: Apache-2.0

package io.github.miner7222.gamespace.gamebar

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import io.chaldeaprjkt.gamespace.R
import io.chaldeaprjkt.gamespace.gamebar.tiles.TileAction
import io.chaldeaprjkt.gamespace.gamebar.tiles.TileCategory
import io.chaldeaprjkt.gamespace.gamebar.tiles.TileKind
import io.github.miner7222.gamespace.TouchSensitivitySettings
import io.github.miner7222.gamespace.shortLabel

/** Opens the in-dashboard touch sensitivity panel. */
class TouchSensitivityTile(private val context: Context,
    private val revision: () -> Int) : TileAction {
    override val id = "touch_sensitivity"
    override val kind = TileKind.DIALOG
    override val category = TileCategory.GAME_TOOLS
    override val label = context.getString(R.string.tile_touch_sensitivity)
    override val shortLabel = context.shortLabel(R.string.tile_touch_sensitivity_short)
    override val description = label
    override val icon = R.drawable.materialsymbols_ic_touch_app_rounded_filled
    /** On unless high polling is off and the tap, move and edge values are the defaults. */
    override val isEnabled: Boolean
        get() {
            revision()
            return TouchSensitivitySettings.highPolling(context) ||
                TouchSensitivitySettings.tap(context) != TouchSensitivitySettings.DEFAULT_TAP ||
                TouchSensitivitySettings.move(context) != TouchSensitivitySettings.DEFAULT_MOVE ||
                TouchSensitivitySettings.edge(context) != TouchSensitivitySettings.DEFAULT_EDGE
        }

    /** Pen input turns high polling off for the session, which holds this tile back. */
    override val limited: Boolean
        get() {
            revision()
            return TouchSensitivitySettings.penInput(context)
        }

    @Composable
    override fun observeEnabled(): State<Boolean> = rememberUpdatedState(isEnabled)

    override fun toggle() {}
}
