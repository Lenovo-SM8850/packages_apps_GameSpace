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

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.chaldeaprjkt.gamespace.gamebar

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityTaskManager
import android.app.TaskStackListener
import android.content.*
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Process
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.*
import android.window.TaskFpsCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import io.github.miner7222.gamespace.gamebar.MediaVolumeController
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import com.android.axion.compose.lifecycle.repeatWhenAttached
import com.android.axion.platform.AxPlatformClient
import io.chaldeaprjkt.gamespace.BuildFlags
import io.chaldeaprjkt.gamespace.R
import io.github.miner7222.gamespace.library.GameOption
import io.chaldeaprjkt.gamespace.data.AppSettings
import io.chaldeaprjkt.gamespace.data.SystemSettings
import io.chaldeaprjkt.gamespace.gamebar.brightness.*
import io.chaldeaprjkt.gamespace.gamebar.fps.*
import io.chaldeaprjkt.gamespace.gamebar.mapper.MapperController
import io.chaldeaprjkt.gamespace.gamebar.tiles.*
import io.chaldeaprjkt.gamespace.utils.*
import java.math.RoundingMode
import java.text.DecimalFormat

class GameSidebar(
    private val context: Context,
    private val wm: WindowManager,
    private val handler: Handler,
    private val appSettings: AppSettings,
    private val screenUtils: ScreenUtils,
    private val danmakuService: DanmakuService,
    private val brightnessInteractor: BrightnessInteractor,
    private val fpsInteractor: FpsInteractor,
    private val gameModeUtils: GameModeUtils,
    private val settings: SystemSettings,
    private val tileRepository: TileRepository,
    private val platform: AxPlatformClient,
    private val mapperController: MapperController,
    private val mediaVolume: MediaVolumeController,
) {
    /** Called when the pill, the panel or the mapper editor starts or stops covering the game. */
    var onUiBlockingChanged: ((Boolean) -> Unit)? = null

    private val gameBarLayoutParam = createGameBarLayoutParam()
    private val panelLayoutParam = createPanelLayoutParam()

    private var halfWidth = 0
    private var safeHeight = 0
    private var safeArea = 0
    private var shouldClose = false
    private var panelShowing = false
    private var barReady = false
    private var screenshotInProgress = false
    private var mapperEditing = false
    private var gameTaskHidden = false
    private var gameVisible = false
    private var gamePackage: String? = null
    private var gameTaskId = ActivityTaskManager.INVALID_TASK_ID
    private var taskListenerRegistered = false

    private lateinit var gameBarView: ComposeView
    private var panelView: View? = null

    private val firstPaint = Runnable { initActions() }

    private var circleOnLeft = false
    private val dockedOnLeftState = mutableStateOf(false)

    private val noOpBackDispatcherOwner = object : OnBackPressedDispatcherOwner {
        override val onBackPressedDispatcher = OnBackPressedDispatcher()
        private val reg = LifecycleRegistry(this).apply {
            currentState = Lifecycle.State.RESUMED
        }
        override val lifecycle: Lifecycle get() = reg
    }

    private val panelDismissing = mutableStateOf(false)

    private val showFpsState = tileRepository.isFpsInfoVisible
    private val fpsTextState = mutableStateOf("")
    private val isLockedState = mutableStateOf(false)
    private val isIdleState = mutableStateOf(true)
    private val collapseRequestState = mutableIntStateOf(0)
    private var pillExpanded = false
    private val pillExpandedState = mutableStateOf(false)
    private val barTopState = mutableIntStateOf(0)
    private var handleCenterOffsetPx = 0
    private val taskManager by lazy { ActivityTaskManager.getService() }

    private val refreshGameTask = Runnable { updateGameTaskVisibility() }

    private val taskListener = object : TaskStackListener() {
        override fun onTaskFocusChanged(taskId: Int, focused: Boolean) {
            scheduleGameTaskRefresh()
        }

        override fun onTaskStackChanged() {
            scheduleGameTaskRefresh()
        }

        override fun onTaskMovedToFront(taskInfo: ActivityManager.RunningTaskInfo) {
            scheduleGameTaskRefresh()
        }
    }

    private fun scheduleGameTaskRefresh() {
        handler.post {
            handler.removeCallbacks(refreshGameTask)
            if (gamePackage != null) {
                updateGameTaskVisibility()
                handler.postDelayed(refreshGameTask, TASK_RECHECK_DELAY_MS)
            }
        }
    }

    private fun updateGameTaskVisibility() {
        val packageName = gamePackage ?: return
        val tasks = try {
            val matchesGame = { task: ActivityManager.RunningTaskInfo ->
                task.userId == UserHandle.myUserId() &&
                    (task.baseActivity?.packageName == packageName ||
                        task.topActivity?.packageName == packageName)
            }
            val activityTaskManager = ActivityTaskManager.getInstance()
            val nearbyTasks = activityTaskManager.getTasks(TASK_QUERY_LIMIT).filter(matchesGame)
            if (nearbyTasks.isEmpty() || (gameTaskId != ActivityTaskManager.INVALID_TASK_ID &&
                    nearbyTasks.none { it.taskId == gameTaskId })) {
                activityTaskManager.getTasks(Int.MAX_VALUE).filter(matchesGame)
            } else {
                nearbyTasks
            }
        } catch (e: Exception) {
            Log.w("GameSidebar", "Unable to query the game task", e)
            return
        }
        val task = tasks.firstOrNull { it.taskId == gameTaskId }
            ?: tasks.firstOrNull { it.isFocused }
            ?: tasks.firstOrNull { it.isVisible }
            ?: tasks.firstOrNull()
        gameTaskId = task?.taskId ?: ActivityTaskManager.INVALID_TASK_ID
        val hidden = task != null && task.isFloatingWindow && !task.isFocused
        if (gameTaskHidden == hidden) return
        gameTaskHidden = hidden
        if (hidden) {
            forceRemovePanel()
            collapseVerticalPill()
            setPillExpanded(false)
        }
        updateGameBarVisibility()
    }

    private fun notifyUiBlocking() {
        onUiBlockingChanged?.invoke(panelShowing || pillExpanded || mapperEditing)
    }

    private fun updateGameBarVisibility() {
        gameBarView.visibility = if (gameVisible && barReady && !shouldClose && !gameTaskHidden &&
            !panelShowing && !screenshotInProgress && !mapperEditing) {
            View.VISIBLE
        } else {
            View.INVISIBLE
        }
    }

    private val taskFpsCallback = object : TaskFpsCallback() {
        override fun onFpsReported(fps: Float) {
            if (::gameBarView.isInitialized && gameBarView.isAttachedToWindow) {
                val formatted = DecimalFormat("#").apply {
                    roundingMode = RoundingMode.HALF_EVEN
                }.format(fps)
                handler.post { fpsTextState.value = formatted }
            }
        }
    }

    private val recordingListener = object : AxPlatformClient.Listener() {
        override fun onStateChanged(key: String, state: Bundle) {}
    }

    fun onCreate() {
        tileRepository.onBeforePlatformAction = { feature ->
            when (feature) {
                AxPlatformClient.FEATURE_SCREENSHOT -> {
                    screenshotInProgress = true
                    forceRemovePanel()
                    updateGameBarVisibility()
                }
                AxPlatformClient.FEATURE_SCREEN_RECORD -> {
                    if (!platform.getState(feature).getBoolean("enabled", false)) forceRemovePanel()
                }
            }
        }
        tileRepository.onScreenshotComplete = {
            screenshotInProgress = false
            updateGameBarVisibility()
        }
        gameBarView = ComposeView(context).apply {
            repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                    setContent {
                        LaunchedEffect(showFpsState.value) {
                            updateFpsTracking()
                        }
                        CompositionLocalProvider(
                            LocalOnBackPressedDispatcherOwner provides noOpBackDispatcherOwner,
                        ) {
                            MaterialExpressiveTheme(
                            colorScheme = dynamicDarkColorScheme(context),
                            motionScheme = MotionScheme.expressive(),
                        ) {
                            GameBarView(
                                showFps = showFpsState.value,
                                fpsText = fpsTextState.value,
                                isLocked = isLockedState.value,
                                isIdle = isIdleState.value,
                                idleAlpha = (appSettings.iconIdleAlpha / 100f).coerceAtLeast(0.05f),
                                dockedOnLeft = dockedOnLeftState.value,
                                mapperEnabled = BuildFlags.MAPPER_ENABLED,
                                onShowPanel = { handler.post { showPanel() } },
                                onMapControls = { handler.post { enterMapperEdit() } },
                                onToggleLock = {
                                    val enabled = !isLockedState.value
                                    io.github.miner7222.gamespace.library.SessionOptions.choose(context, GameOption.GESTURES, if (enabled) 1 else 0)
                                    setGestureLock(enabled)
                                    scheduleIdle()
                                },
                                onToggleFps = {
                                    tileRepository.setFpsInfoEnabled(!showFpsState.value)
                                    scheduleIdle()
                                },
                                pillExpanded = pillExpandedState.value,
                                barTopPx = barTopState.intValue,
                                onExpanded = { handler.post { setPillExpanded(true) } },
                                onCollapsed = {
                                    handler.post { setPillExpanded(false) }
                                    scheduleIdle()
                                },
                                collapseRequestKey = collapseRequestState.intValue,
                                onDragStart = {
                                    val loc = IntArray(2)
                                    gameBarView.getLocationOnScreen(loc)
                                    gameBarLayoutParam.gravity = Gravity.TOP or Gravity.START
                                    gameBarLayoutParam.x = loc[0]
                                    gameBarLayoutParam.y = loc[1]
                                    runCatching { wm.updateViewLayout(gameBarView, gameBarLayoutParam) }
                                    Pair(loc[0], loc[1])
                                },
                                onDragUpdate = { x, y ->
                                    gameBarLayoutParam.x = x
                                    gameBarLayoutParam.y = y
                                    runCatching { wm.updateViewLayout(gameBarView, gameBarLayoutParam) }
                                },
                                onDragEnd = { x, y ->
                                    circleOnLeft = x < halfWidth
                                    dockedOnLeftState.value = circleOnLeft
                                    appSettings.x = if (circleOnLeft) -1 else 1
                                    appSettings.y = y
                                    dockGameBar()
                                    runCatching { wm.updateViewLayout(gameBarView, gameBarLayoutParam) }
                                    scheduleIdle()
                                },
                            )
                        }
                        }
                    }
                }
            }
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                v.setSystemGestureExclusionRects(
                    listOf(Rect(0, 0, v.width, v.height))
                )
            }
        }

        updateScreenMetrics()
        danmakuService.init()
    }

    fun setGameVisible(visible: Boolean) {
        if (gameVisible == visible) return
        gameVisible = visible
        if (visible) {
            updateGameTaskVisibility()
        } else {
            forceRemovePanel()
            collapseVerticalPill()
            setPillExpanded(false)
        }
        updateGameBarVisibility()
        mapperController.setGameVisible(visible)
    }

    private var sessionUserId = context.userId

    fun onGameStart(packageName: String) {
        sessionUserId = context.userId
        shouldClose = false
        barReady = false
        screenshotInProgress = false
        mapperEditing = false
        notifyUiBlocking()
        gameTaskHidden = false
        gamePackage = packageName
        gameTaskId = ActivityTaskManager.INVALID_TASK_ID
        if (!taskListenerRegistered) {
            try {
                taskManager.registerTaskStackListener(taskListener)
                taskListenerRegistered = true
            } catch (e: Exception) {
                Log.w("GameSidebar", "Unable to observe the game task", e)
            }
        }
        scheduleGameTaskRefresh()
        if (BuildFlags.MAPPER_ENABLED) {
            mapperController.onGameStart(packageName)
        }
        platform.addListener(recordingListener)
        handler.post {
            if (!::gameBarView.isInitialized) return@post
            runCatching {
                dockGameBar()
                wm.addView(gameBarView, gameBarLayoutParam)
                gameBarView.visibility = View.INVISIBLE
                gameBarView.alpha = 0f
                handler.postDelayed(firstPaint, 500)
            }
        }
    }

    fun onGameLeave() {
        gamePackage = null
        handler.removeCallbacks(refreshGameTask)
        if (taskListenerRegistered) {
            runCatching { taskManager.unregisterTaskStackListener(taskListener) }
            taskListenerRegistered = false
        }
        mapperController.onGameLeave()
        platform.removeListener(recordingListener)
        stopFpsTracking()
        shouldClose = true
        setGestureLock(false)
        handler.removeCallbacksAndMessages(null)
        forceRemovePanel()
        runCatching { wm.removeViewImmediate(gameBarView) }
    }

    fun collapseForScreenOff() {
        forceRemovePanel()
        collapseVerticalPill()
    }

    fun refreshInGameOptions() {
        if (gamePackage != null) setGestureLock(appSettings.inGameOptions.current(GameOption.GESTURES) == 1)
    }

    fun setGestureLock(value: Boolean) {
        isLockedState.value = value
        Settings.Secure.putIntForUser(
            context.contentResolver,
            "ax_gaming_gesture_lock",
            if (value) 1 else 0,
            sessionUserId
        )
    }

    fun onConfigurationChanged(newConfig: Configuration) {
        mapperController.onConfigurationChanged(newConfig)
        updateScreenMetrics()
        forceRemovePanel()
        if (gameBarView.visibility != View.VISIBLE) {
            handler.removeCallbacks(firstPaint)
            handler.postDelayed({ firstPaint.run() }, 100)
        } else {
            dockGameBar()
            runCatching { wm.updateViewLayout(gameBarView, gameBarLayoutParam) }
        }
        danmakuService.updateConfiguration(newConfig)
    }

    private fun showPanel() {
        updateGameTaskVisibility()
        if (!gameVisible || shouldClose || gameTaskHidden || panelShowing) return
        panelShowing = true
        notifyUiBlocking()
        panelDismissing.value = false
        handler.removeCallbacks(idleRunnable)
        isIdleState.value = false

        tileRepository.refreshPlatformStates()
        fpsInteractor.start()
        brightnessInteractor.start()

        val pv = createPanelView()
        panelView = pv

        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            Process.setThreadGroupAndCpuset(Process.myTid(), Process.THREAD_GROUP_SYSTEM)
            Process.setProcessGroup(Process.myPid(), Process.THREAD_GROUP_SYSTEM)
            wm.addView(pv, panelLayoutParam)
            gameBarView.visibility = View.GONE
        } catch (_: Exception) {
            brightnessInteractor.dispose()
            fpsInteractor.dispose()
            panelShowing = false
            notifyUiBlocking()
        }
    }

    private fun requestDismissPanel() {
        panelDismissing.value = true
    }

    private fun removePanelAndRestoreCircle() {
        if (!panelShowing) return
        panelShowing = false
        notifyUiBlocking()

        brightnessInteractor.dispose()
        fpsInteractor.dispose()

        panelView?.let { pv ->
            runCatching { wm.removeViewImmediate(pv) }
            panelView = null
        }
        runCatching {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            Process.setThreadGroupAndCpuset(Process.myTid(), Process.THREAD_GROUP_BACKGROUND)
            Process.setProcessGroup(Process.myPid(), Process.THREAD_GROUP_BACKGROUND)
        }

        if (!shouldClose) {
            updateGameBarVisibility()
            scheduleIdle()
        }
    }

    private fun forceRemovePanel() {
        if (!panelShowing) return
        panelShowing = false
        notifyUiBlocking()
        panelDismissing.value = true

        brightnessInteractor.dispose()
        fpsInteractor.dispose()

        panelView?.let { pv ->
            runCatching { wm.removeViewImmediate(pv) }
            panelView = null
        }
        if (::gameBarView.isInitialized) updateGameBarVisibility()
    }

    private fun createPanelView(): ComposeView {
        return ComposeView(context).apply {
            repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                    setContent {
                        MaterialExpressiveTheme(
                            colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context),
                            motionScheme = MotionScheme.expressive(),
                        ) {
                            PanelContent()
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PanelContent() {
        val apps = remember { getQuickStartApps(context) }
        val dismissing by panelDismissing

        var entered by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { entered = true }

        val show = entered && !dismissing
        val slideSign = if (circleOnLeft) -1f else 1f

        val slideOffset by animateFloatAsState(
            targetValue = if (show) 0f else slideSign * 400f,
            animationSpec = tween(
                durationMillis = if (show) 350 else 250,
                easing = if (show) FastOutSlowInEasing else FastOutLinearInEasing,
            ),
            label = "panel_slide",
            finishedListener = {
                if (dismissing) handler.post { removePanelAndRestoreCircle() }
            },
        )

        val panelAlpha by animateFloatAsState(
            targetValue = if (show) 1f else 0f,
            animationSpec = tween(if (show) 280 else 180),
            label = "panel_alpha",
        )

        val panelScale by animateFloatAsState(
            targetValue = if (show) 1f else 0.88f,
            animationSpec = tween(
                durationMillis = if (show) 350 else 220,
                easing = if (show) FastOutSlowInEasing else FastOutLinearInEasing,
            ),
            label = "panel_scale",
        )

        val density = LocalDensity.current
        val configuration = LocalConfiguration.current
        val metrics = wm.maximumWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
        val safeLeft = with(density) { insets.left.toDp() }
        val safeTop = with(density) { insets.top.toDp() }
        val safeWidth = with(density) { (metrics.bounds.width() - insets.left - insets.right).coerceAtLeast(0).toDp() }
        val safeHeightDp = with(density) { (metrics.bounds.height() - insets.top - insets.bottom).coerceAtLeast(0).toDp() }
        val tablet = configuration.smallestScreenWidthDp >= 600
        val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val phonePortrait = !tablet && !landscape
        val width = minOf(if (tablet) { if (landscape) 420.dp else 400.dp }
            else if (landscape) 340.dp else safeWidth, (safeWidth - 24.dp).coerceAtLeast(0.dp))
        var editingPanel by remember { mutableStateOf(false) }
        val primaryRows = (minOf(8, tileRepository.tiles.size) + 3) / 4
        val portraitContentHeight = 242.dp + 72.dp * primaryRows + 8.dp * (primaryRows - 1).coerceAtLeast(0) +
            (if (tileRepository.tiles.size > 8) 55.dp else 0.dp) + (if (apps.isNotEmpty()) 62.dp else 0.dp) + 12.dp
        val height = when {
            phonePortrait && editingPanel -> (safeHeightDp - 72.dp).coerceAtLeast(0.dp)
            phonePortrait -> minOf(portraitContentHeight, (safeHeightDp - 24.dp).coerceAtLeast(0.dp))
            !tablet -> (safeHeightDp - 24.dp).coerceAtLeast(0.dp)
            else -> minOf(640.dp, (safeHeightDp - 32.dp).coerceAtLeast(0.dp))
        }
        // Full-height phone landscapes leave 12dp above/below; other edge panels use 16dp.
        val margin = minOf(16.dp, ((safeHeightDp - height) / 2).coerceAtLeast(0.dp))
        val handleLocation = IntArray(2).also { gameBarView.getLocationOnScreen(it) }
        val anchorPx = if (pillExpanded) barTopState.intValue + handleCenterOffsetPx
            else handleLocation[1] + gameBarView.height / 2
        val anchor = with(density) { anchorPx.toDp() }
        val top = if (phonePortrait) safeTop + safeHeightDp - height - 12.dp
            else (anchor - height / 2).coerceIn(safeTop + margin,
                (safeTop + safeHeightDp - height - margin).coerceAtLeast(safeTop + margin))
        val left = if (phonePortrait || circleOnLeft) safeLeft + 12.dp
            else safeLeft + safeWidth - width - 12.dp
        val transformOriginX = if (circleOnLeft) 0f else 1f
        val transformOriginY = if (height > 0.dp) ((anchor - top) / height).coerceIn(0f, 1f) else 0.5f
        var swipe by remember { mutableFloatStateOf(0f) }
        val threshold = with(density) { 48.dp.toPx() }
        val packageName = gamePackage ?: return
        Box(Modifier.fillMaxSize().clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
        ) { if (!dismissing) requestDismissPanel() }) {
            Box(Modifier.offset(left.coerceAtLeast(0.dp), top.coerceAtLeast(0.dp))
                .graphicsLayer {
                    translationX = slideOffset
                    alpha = panelAlpha
                    scaleX = panelScale
                    scaleY = panelScale
                    transformOrigin = TransformOrigin(transformOriginX, transformOriginY)
                }
                .pointerInput(circleOnLeft) {
                    detectHorizontalDragGestures(onDragStart = { swipe = 0f }, onDragEnd = {
                        if ((circleOnLeft && swipe < -threshold) || (!circleOnLeft && swipe > threshold)) requestDismissPanel()
                    }, onHorizontalDrag = { change, amount -> swipe += amount; change.consume() })
                }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) {
                GamePanelCard(interactor = brightnessInteractor, fpsInteractor = fpsInteractor, apps = apps,
                    gameModeUtils = gameModeUtils, systemSettings = settings, tileRepository = tileRepository,
                    mediaVolume = mediaVolume, gamePackage = packageName,
                    onDismiss = { forceRemovePanel() }, panelWidth = width, maxHeight = height,
                    onEditingChanged = { editingPanel = it })
            }
        }
    }

    private fun setPillExpanded(expanded: Boolean) {
        if (expanded && !gameVisible) return
        if (pillExpanded == expanded) return
        pillExpanded = expanded
        pillExpandedState.value = expanded
        notifyUiBlocking()
        if (expanded) {
            barTopState.intValue = gameBarLayoutParam.y
            val location = IntArray(2).also { gameBarView.getLocationOnScreen(it) }
            handleCenterOffsetPx = location[1] - gameBarLayoutParam.y + gameBarView.height / 2
            gameBarLayoutParam.gravity = Gravity.TOP or Gravity.START
            gameBarLayoutParam.x = 0
            gameBarLayoutParam.y = 0
            gameBarLayoutParam.width = WindowManager.LayoutParams.MATCH_PARENT
            gameBarLayoutParam.height = WindowManager.LayoutParams.MATCH_PARENT
            gameBarLayoutParam.flags = gameBarLayoutParam.flags and
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL.inv()
        } else {
            gameBarLayoutParam.width = WindowManager.LayoutParams.WRAP_CONTENT
            gameBarLayoutParam.height = WindowManager.LayoutParams.WRAP_CONTENT
            gameBarLayoutParam.flags = gameBarLayoutParam.flags or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            gameBarLayoutParam.y = barTopState.intValue
            dockGameBar()
        }
        runCatching { wm.updateViewLayout(gameBarView, gameBarLayoutParam) }
    }

    private fun collapseVerticalPill() {
        if (!pillExpanded) return
        collapseRequestState.intValue = collapseRequestState.intValue + 1
    }

    private fun updateFpsTracking() {
        stopFpsTracking()
        if (showFpsState.value) {
            taskManager?.focusedRootTaskInfo?.taskId?.let {
                wm.registerTaskFpsCallback(it, Runnable::run, taskFpsCallback)
            }
        } else {
            stopFpsTracking()
        }
    }

    private fun stopFpsTracking() {
        runCatching { wm.unregisterTaskFpsCallback(taskFpsCallback) }
    }

    private fun updateScreenMetrics() {
        val bounds = wm.maximumWindowMetrics.bounds
        halfWidth = bounds.width() / 2
        safeArea = context.statusbarHeight + (4 * context.resources.displayMetrics.density).toInt()
        safeHeight = bounds.height() - safeArea
    }

    private fun initActions() {
        if (shouldClose) return
        updateGameTaskVisibility()
        barReady = true
        updateGameBarVisibility()
        gameBarView.animate().alpha(1f).setDuration(300).start()
        circleOnLeft = appSettings.x < 0
        dockedOnLeftState.value = circleOnLeft
        gameBarLayoutParam.y = appSettings.y
        dockGameBar()
        runCatching { wm.updateViewLayout(gameBarView, gameBarLayoutParam) }
        tileRepository.setFpsInfoEnabled(appSettings.showFps)
        updateFpsTracking()
        setGestureLock(appSettings.inGameOptions.current(GameOption.GESTURES) == 1)
        scheduleIdle()
    }

    private fun dockGameBar() {
        gameBarLayoutParam.gravity = Gravity.TOP or (if (circleOnLeft) Gravity.START else Gravity.END)
        gameBarLayoutParam.x = 0
        gameBarLayoutParam.y = gameBarLayoutParam.y.coerceIn(safeArea, safeHeight)
    }

    private val idleRunnable = Runnable {
        isIdleState.value = true
    }

    private fun enterMapperEdit() {
        updateGameTaskVisibility()
        if (!gameVisible || shouldClose || gameTaskHidden) return
        mapperEditing = true
        notifyUiBlocking()
        forceRemovePanel()
        gameBarView.animate().alpha(0f).setDuration(150).withEndAction {
            gameBarView.visibility = View.GONE
        }.start()
        mapperController.onEditStarted = null
        mapperController.onEditFinished = {
            handler.post {
                mapperEditing = false
                notifyUiBlocking()
                updateGameBarVisibility()
                gameBarView.animate().alpha(1f).setDuration(200).start()
                scheduleIdle()
            }
        }
        mapperController.enterEditMode()
    }

    private fun scheduleIdle() {
        handler.removeCallbacks(idleRunnable)
        isIdleState.value = false
        handler.postDelayed(idleRunnable, IDLE_TIMEOUT_MS)
    }

    private fun getQuickStartApps(context: Context): List<AppInfo> {
        val appList = mutableListOf<AppInfo>()
        val packageManager = context.packageManager
        if (appSettings.quickStartApps.isNullOrEmpty() == false) {
            val packages = appSettings.quickStartApps.split(",")
            for (pkg in packages) {
                try {
                    val appInfo = packageManager.getApplicationInfo(pkg, 0)
                    val appName = packageManager.getApplicationLabel(appInfo).toString()
                    val icon = packageManager.getApplicationIcon(appInfo)
                    appList.add(AppInfo(name = appName, icon = icon, packageName = pkg))
                } catch (_: Exception) {}
            }
        }
        return appList
    }

    private fun createGameBarLayoutParam() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        preferMinimalPostProcessing = true
        gravity = Gravity.TOP or Gravity.END
    }

    private fun createPanelLayoutParam() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        setFitInsetsTypes(0)
        width = WindowManager.LayoutParams.MATCH_PARENT
        height = WindowManager.LayoutParams.MATCH_PARENT
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        preferMinimalPostProcessing = true
    }

    companion object {
        private const val IDLE_TIMEOUT_MS = 3000L
        private const val TASK_RECHECK_DELAY_MS = 500L
        private const val TASK_QUERY_LIMIT = 16
    }
}
