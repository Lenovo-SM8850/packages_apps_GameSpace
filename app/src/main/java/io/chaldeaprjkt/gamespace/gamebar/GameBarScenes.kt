/*
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

package io.chaldeaprjkt.gamespace.gamebar

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.InterruptionHandler
import com.android.compose.animation.scene.InterruptionResult
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.SceneTransitions
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.compose.animation.scene.transitions

object GameBarScenes {
    val Handle = SceneKey("gamebar_handle")
    val MiniBar = SceneKey("gamebar_mini_bar")
}

object GameBarElements {
    val HandleContent = ElementKey("gamebar_handle_content")
    val MiniBarContent = ElementKey("gamebar_mini_bar_content")
}

val GameBarTransitions: SceneTransitions = transitions {
    interruptionHandler = object : InterruptionHandler {
        override fun onInterruption(
            interrupted: TransitionState.Transition.ChangeScene,
            newTargetScene: SceneKey,
        ): InterruptionResult = InterruptionResult(
            animateFrom = interrupted.currentScene,
            chain = false,
        )
    }

    from(GameBarScenes.Handle, to = GameBarScenes.MiniBar) {
        spec = tween(durationMillis = 180, easing = FastOutSlowInEasing)
        fractionRange(end = 0.25f) { fade(GameBarElements.HandleContent) }
        fractionRange(start = 0.15f) { fade(GameBarElements.MiniBarContent) }
    }

    from(GameBarScenes.MiniBar, to = GameBarScenes.Handle) {
        spec = tween(durationMillis = 150, easing = FastOutLinearInEasing)
        fractionRange(end = 0.4f) { fade(GameBarElements.MiniBarContent) }
        fractionRange(start = 0.3f) { fade(GameBarElements.HandleContent) }
    }
}
