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
import android.graphics.Region
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import io.github.miner7222.gamespace.gamebar.MediaVolumeController
import io.github.miner7222.gamespace.gamebar.OverlayTheme
import io.github.miner7222.gamespace.gamebar.RecordingPhase
import io.github.miner7222.gamespace.gamebar.StatsOverlay
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.IntOffset
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
    private var safeLeft = 0
    private var safeRight = 0
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

    private var sessionActive = false
    private val firstPaint = Runnable { initActions() }
    private val attachGameBar = Runnable {
        if (sessionActive && !shouldClose && gamePackage != null && ::gameBarView.isInitialized) {
            runCatching {
                if (!gameBarView.isAttachedToWindow) {
                    dockGameBar()
                    wm.addView(gameBarView, gameBarLayoutParam)
                    gameBarView.visibility = View.INVISIBLE
                    gameBarView.alpha = 0f
                }
                handler.removeCallbacks(firstPaint)
                handler.postDelayed(firstPaint, 500)
            }
        }
    }

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
    private val statsOverlay by lazy {
        StatsOverlay(context, wm, handler, appSettings, fpsTextState) {
            setPillExpanded(true)
        }
    }
    private val isLockedState = mutableStateOf(false)
    private val isIdleState = mutableStateOf(true)
    private var pillExpanded = false
    private val pillExpandedState = mutableStateOf(false)
    private val barAnchorState = mutableIntStateOf(0)
    private val barTouchBounds = Rect()
    private val syncBarTouchRegion = Runnable {
        if (::gameBarView.isInitialized && gameBarView.isAttachedToWindow) {
            // Compose can place scenes during drawing, after ViewRoot computed internal insets.
            // Publish on the next traversal without changing the native surface bounds.
            gameBarView.viewRootImpl?.setTouchableRegion(Region(barTouchBounds))
        }
    }
    private var handleCenterY = 0
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
        gameBarView.visibility = if (sessionActive && gameVisible && barReady && !shouldClose && !gameTaskHidden &&
            !panelShowing && !screenshotInProgress && !mapperEditing) {
            View.VISIBLE
        } else {
            View.INVISIBLE
        }
        updateStatsOverlayVisibility()
    }

    private fun updateStatsOverlayVisibility() {
        statsOverlay.setVisible(showFpsState.value && sessionActive && gameVisible && barReady &&
            !shouldClose && !gameTaskHidden && !screenshotInProgress && !mapperEditing)
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

    @SuppressLint("ClickableViewAccessibility")
    fun onCreate() {
        tileRepository.onBeforePlatformAction = beforeAction@{ feature ->
            if (!sessionActive) return@beforeAction
            when (feature) {
                AxPlatformClient.FEATURE_SCREENSHOT -> {
                    screenshotInProgress = true
                    // Keep the panel composition and its result collector attached during capture.
                    panelView?.let { view ->
                        view.alpha = 0f
                        panelLayoutParam.alpha = 0f
                        panelLayoutParam.flags = panelLayoutParam.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        runCatching { wm.updateViewLayout(view, panelLayoutParam) }
                    }
                    updateGameBarVisibility()
                }
                AxPlatformClient.FEATURE_SCREEN_RECORD -> {
                    if (!platform.getState(feature).getBoolean("enabled", false)) forceRemovePanel()
                }
            }
        }
        tileRepository.onScreenshotComplete = screenshotComplete@{
            if (!sessionActive) return@screenshotComplete
            screenshotInProgress = false
            panelView?.let { view ->
                panelLayoutParam.flags = panelLayoutParam.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                view.alpha = 1f
                panelLayoutParam.alpha = 1f
                runCatching { wm.updateViewLayout(view, panelLayoutParam) }
            }
            updateGameBarVisibility()
        }
        gameBarView = ComposeView(context).apply {
            repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                    setContent {
                        val recording by tileRepository.recordingState.collectAsState()
                        val idleOpacity by produceState(appSettings.menuOpacity, appSettings) {
                            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                                if (key == AppSettings.KEY_MENU_OPACITY || key == AppSettings.KEY_ICON_IDLE_ALPHA)
                                    value = appSettings.menuOpacity
                            }
                            appSettings.registerPreferenceListener(listener)
                            awaitDispose { appSettings.unregisterPreferenceListener(listener) }
                        }
                        LaunchedEffect(showFpsState.value, recording.phase) {
                            if (miniDashboardOnly(showFpsState.value, recording.phase == RecordingPhase.ACTIVE)) {
                                setPillExpanded(false)
                            }
                        }
                        LaunchedEffect(showFpsState.value) {
                            if (!sessionActive) return@LaunchedEffect
                            updateFpsTracking()
                            updateStatsOverlayVisibility()
                        }
                        CompositionLocalProvider(
                            LocalOnBackPressedDispatcherOwner provides noOpBackDispatcherOwner,
                            LocalLayoutDirection provides LayoutDirection.Ltr,
                        ) {
                            OverlayTheme(context) {
                            GameBarView(
                                showFps = showFpsState.value,
                                fpsText = fpsTextState.value,
                                isLocked = isLockedState.value,
                                isIdle = isIdleState.value,
                                idleAlpha = (idleOpacity / 100f).coerceIn(0.2f, 1f),
                                dockedOnLeft = dockedOnLeftState.value,
                                recording = recording,
                                onShowPanel = { expandFps -> handler.post { showPanel(expandFps) } },
                                onStopRecording = { if (sessionActive) tileRepository.stopRecording() },
                                pillExpanded = pillExpandedState.value,
                                anchorY = barAnchorState.intValue,
                                onExpanded = { handler.post { setPillExpanded(true) } },
                                onCollapsed = { handler.post { setPillExpanded(false) } },
                                onInteraction = { pressed ->
                                    handler.removeCallbacks(miniCollapseRunnable)
                                    handler.removeCallbacks(idleRunnable)
                                    isIdleState.value = false
                                    if (!pressed) {
                                        if (pillExpanded) scheduleMiniCollapse() else scheduleIdle()
                                    }
                                },
                                onDragStart = dragStart@{
                                    if (!sessionActive) return@dragStart Pair(0, 0)
                                    val loc = IntArray(2)
                                    gameBarView.getLocationOnScreen(loc)
                                    gameBarLayoutParam.gravity = Gravity.TOP or Gravity.LEFT
                                    // Coordinates are absolute because this window does not fit system insets.
                                    gameBarLayoutParam.x = loc[0]
                                    gameBarLayoutParam.y = loc[1]
                                    runCatching { wm.updateViewLayout(gameBarView, gameBarLayoutParam) }
                                    Pair(loc[0] + if (circleOnLeft) 0 else gameBarLayoutParam.width - px(HANDLE_WIDTH_DP),
                                        handleCenterY - px(HANDLE_HEIGHT_DP) / 2)
                                },
                                onDragUpdate = dragUpdate@{ x, y ->
                                    if (!sessionActive) return@dragUpdate
                                    gameBarLayoutParam.x = x -
                                        if (circleOnLeft) 0 else gameBarLayoutParam.width - px(HANDLE_WIDTH_DP)
                                    positionGameBar(y)
                                    runCatching { wm.updateViewLayout(gameBarView, gameBarLayoutParam) }
                                },
                                onDragEnd = dragEnd@{ x, y ->
                                    if (!sessionActive) return@dragEnd
                                    circleOnLeft = x < halfWidth
                                    dockedOnLeftState.value = circleOnLeft
                                    appSettings.x = if (circleOnLeft) -1 else 1
                                    dockGameBar(y)
                                    appSettings.y = handleCenterY - px(HANDLE_HEIGHT_DP) / 2
                                    runCatching { wm.updateViewLayout(gameBarView, gameBarLayoutParam) }
                                    scheduleIdle()
                                },
                                onContentBoundsChanged = { bounds ->
                                    if (barTouchBounds != bounds) {
                                        barTouchBounds.set(bounds)
                                        updateGestureExclusions()
                                        gameBarView.removeCallbacks(syncBarTouchRegion)
                                        gameBarView.post(syncBarTouchRegion)
                                    }
                                },
                            )
                        }
                        }
                    }
                }
            }
            // Reapply the content-only region when a session attaches a new ViewRoot.
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    view.post(syncBarTouchRegion)
                }

                override fun onViewDetachedFromWindow(view: View) {
                    view.removeCallbacks(syncBarTouchRegion)
                }
            })
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE && pillExpanded &&
                    !barTouchBounds.contains(event.x.toInt(), event.y.toInt())) {
                    setPillExpanded(false)
                    true
                } else false
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
        mapperController.setGameVisible(visible && sessionActive)
    }

    private var sessionUserId = context.userId

    fun onGameStart(packageName: String, active: Boolean = true) {
        handler.removeCallbacks(attachGameBar)
        handler.removeCallbacks(firstPaint)
        sessionActive = active
        sessionUserId = context.userId
        shouldClose = false
        barReady = false
        screenshotInProgress = false
        mapperEditing = false
        notifyUiBlocking()
        gameTaskHidden = false
        gamePackage = packageName
        fpsTextState.value = ""
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
        if (active) {
            if (BuildFlags.MAPPER_ENABLED) mapperController.onGameStart(packageName)
            handler.post(attachGameBar)
        }
    }

    fun onGameLeave() {
        sessionActive = false
        statsOverlay.setVisible(false)
        handler.removeCallbacks(attachGameBar)
        handler.removeCallbacks(firstPaint)
        gameBarView.animate().cancel()
        updateGestureExclusions()
        gamePackage = null
        handler.removeCallbacks(refreshGameTask)
        if (taskListenerRegistered) {
            runCatching { taskManager.unregisterTaskStackListener(taskListener) }
            taskListenerRegistered = false
        }
        mapperController.onGameLeave()
        stopFpsTracking()
        shouldClose = true
        setPillExpanded(false)
        setGestureLock(false)
        handler.removeCallbacksAndMessages(null)
        forceRemovePanel()
        runCatching { wm.removeViewImmediate(gameBarView) }
    }

    fun setSessionActive(active: Boolean) {
        if (sessionActive == active) return
        sessionActive = active
        handler.removeCallbacks(attachGameBar)
        handler.removeCallbacks(firstPaint)
        if (!active) {
            barReady = false
            gameBarView.animate().cancel()
            stopFpsTracking()
            updateGestureExclusions()
            setGestureLock(false)
            if (BuildFlags.MAPPER_ENABLED) mapperController.onGameLeave()
        } else {
            if (BuildFlags.MAPPER_ENABLED) gamePackage?.let { mapperController.onGameStart(it) }
            refreshInGameOptions()
            handler.post(attachGameBar)
        }
        updateGameBarVisibility()
    }

    fun collapseForScreenOff() {
        forceRemovePanel()
        collapseVerticalPill()
    }

    fun refreshInGameOptions() {
        if (sessionActive && gamePackage != null) setGestureLock(appSettings.inGameOptions.current(GameOption.GESTURES) == 1)
    }

    fun setGestureLock(value: Boolean) {
        val enabled = value && sessionActive && !shouldClose && gamePackage != null
        isLockedState.value = enabled
        Settings.Secure.putIntForUser(
            context.contentResolver,
            "ax_gaming_gesture_lock",
            if (enabled) 1 else 0,
            sessionUserId
        )
    }

    fun onConfigurationChanged(newConfig: Configuration) {
        mapperController.onConfigurationChanged(newConfig)
        updateScreenMetrics()
        statsOverlay.onConfigurationChanged(newConfig)
        forceRemovePanel()
        setPillExpanded(false)
        if (!sessionActive) return
        if (gameBarView.visibility != View.VISIBLE) {
            handler.removeCallbacks(firstPaint)
            handler.postDelayed(firstPaint, 100)
        } else {
            dockGameBar()
            runCatching { wm.updateViewLayout(gameBarView, gameBarLayoutParam) }
        }
        danmakuService.updateConfiguration(newConfig)
    }

    fun openDashboard() { showPanel() }

    private fun showPanel(initialGraphExpanded: Boolean = false) {
        updateGameTaskVisibility()
        if (!sessionActive || !gameVisible || shouldClose || gameTaskHidden || panelShowing || screenshotInProgress) return
        panelShowing = true
        setPillExpanded(false)
        notifyUiBlocking()
        panelDismissing.value = false
        handler.removeCallbacks(idleRunnable)
        isIdleState.value = false

        tileRepository.refreshPlatformStates()
        fpsInteractor.start()
        brightnessInteractor.start()

        panelLayoutParam.flags = panelLayoutParam.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        panelLayoutParam.alpha = 1f
        val pv = createPanelView(initialGraphExpanded)
        panelView = pv

        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            Process.setThreadGroupAndCpuset(Process.myTid(), Process.THREAD_GROUP_SYSTEM)
            Process.setProcessGroup(Process.myPid(), Process.THREAD_GROUP_SYSTEM)
            wm.addView(pv, panelLayoutParam)
            gameBarView.visibility = View.GONE
            updateStatsOverlayVisibility()
            statsOverlay.raiseAbovePanel()
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

    private fun createPanelView(initialGraphExpanded: Boolean): ComposeView {
        return ComposeView(context).apply {
            val touchRegion = ViewTreeObserver.OnComputeInternalInsetsListener { info ->
                // Keep outside-tap dismissal without allocating a full-display surface or taking focus.
                val location = IntArray(2)
                getLocationOnScreen(location)
                val bounds = wm.maximumWindowMetrics.bounds
                info.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION)
                info.touchableRegion.set(-location[0], -location[1],
                    bounds.width() - location[0], bounds.height() - location[1])
            }
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    view.viewTreeObserver.addOnComputeInternalInsetsListener(touchRegion)
                }
                override fun onViewDetachedFromWindow(view: View) {
                    view.viewTreeObserver.takeIf { it.isAlive }?.removeOnComputeInternalInsetsListener(touchRegion)
                }
            })
            var outsideGesture = false
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    outsideGesture = event.x < 0 || event.y < 0 || event.x >= width || event.y >= height
                    if (outsideGesture) requestDismissPanel()
                }
                val consumed = outsideGesture
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    outsideGesture = false
                }
                consumed
            }
            repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                    setContent {
                        OverlayTheme(context) {
                            PanelContent(initialGraphExpanded)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PanelContent(initialGraphExpanded: Boolean) {
        val apps = remember { getQuickStartApps(context) }
        val dismissing by panelDismissing

        var entered by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { entered = true }

        val show = entered && !dismissing
        val slideSign = if (circleOnLeft) -1f else 1f

        val slideOffset by animateFloatAsState(
            targetValue = if (show) 0f else slideSign * PANEL_SLIDE_PX,
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
        val portraitContentHeight = 249.dp + 72.dp * primaryRows + 8.dp * (primaryRows - 1).coerceAtLeast(0) +
            (if (tileRepository.tiles.size > 8) 55.dp else 0.dp) + (if (apps.isNotEmpty()) 62.dp else 0.dp) + 12.dp
        val height = when {
            phonePortrait && editingPanel -> (safeHeightDp - 72.dp).coerceAtLeast(0.dp)
            phonePortrait -> minOf(portraitContentHeight, (safeHeightDp - 24.dp).coerceAtLeast(0.dp))
            !tablet -> (safeHeightDp - 24.dp).coerceAtLeast(0.dp)
            else -> minOf(640.dp, (safeHeightDp - 32.dp).coerceAtLeast(0.dp))
        }
        // Full-height phone landscapes leave 12dp above/below; other edge panels use 16dp.
        val margin = minOf(16.dp, ((safeHeightDp - height) / 2).coerceAtLeast(0.dp))
        val anchor = with(density) { handleCenterY.toDp() }
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
        val panelLeft = with(density) { left.coerceAtLeast(0.dp).roundToPx() }
        val panelTop = with(density) { top.coerceAtLeast(0.dp).roundToPx() }
        val panelWidth = with(density) { width.roundToPx().coerceAtLeast(1) }
        val panelHeight = with(density) { height.roundToPx().coerceAtLeast(1) }
        // Include only the slide's visible sweep, keeping the panel at its existing screen position.
        val windowLeft = if (circleOnLeft) (panelLeft - PANEL_SLIDE_PX).coerceAtLeast(0) else panelLeft
        val windowRight = if (circleOnLeft) panelLeft + panelWidth else
            (panelLeft + panelWidth + PANEL_SLIDE_PX).coerceAtMost(metrics.bounds.width())
        val windowWidth = (windowRight - windowLeft).coerceAtLeast(1)
        SideEffect {
            if (panelLayoutParam.x != windowLeft || panelLayoutParam.y != panelTop ||
                panelLayoutParam.width != windowWidth || panelLayoutParam.height != panelHeight) {
                panelLayoutParam.x = windowLeft
                panelLayoutParam.y = panelTop
                panelLayoutParam.width = windowWidth
                panelLayoutParam.height = panelHeight
                panelView?.takeIf { it.isAttachedToWindow }?.let { wm.updateViewLayout(it, panelLayoutParam) }
            }
        }
        Box(Modifier.fillMaxSize().clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
        ) { if (!dismissing) requestDismissPanel() }) {
            Box(Modifier.offset { IntOffset(panelLeft - windowLeft, 0) }.width(width).height(height)
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
                    onEditingChanged = { editingPanel = it }, initialGraphExpanded = initialGraphExpanded)
            }
        }
    }

    private fun setPillExpanded(expanded: Boolean) {
        if (expanded && (!sessionActive || !gameVisible || !barReady || shouldClose || panelShowing || gameTaskHidden)) return
        if (expanded && miniDashboardOnly(showFpsState.value,
                tileRepository.recordingState.value.phase == RecordingPhase.ACTIVE)) {
            showPanel()
            return
        }
        if (pillExpanded == expanded) return
        handler.removeCallbacks(miniCollapseRunnable)
        handler.removeCallbacks(idleRunnable)
        pillExpanded = expanded
        pillExpandedState.value = expanded
        notifyUiBlocking()
        isIdleState.value = false
        if (expanded) scheduleMiniCollapse() else scheduleIdle()
        updateFpsTracking()
    }

    private fun updateGestureExclusions() {
        if (!::gameBarView.isInitialized) return
        val bounds = Rect(barTouchBounds)
        if (pillExpanded) {
            if (circleOnLeft) bounds.left += px(8) else bounds.right -= px(8)
        }
        gameBarView.systemGestureExclusionRects =
            if (sessionActive && !bounds.isEmpty) listOf(bounds) else emptyList()
    }

    private fun collapseVerticalPill() {
        setPillExpanded(false)
    }

    private val miniCollapseRunnable = Runnable { setPillExpanded(false) }

    private fun scheduleMiniCollapse() {
        handler.removeCallbacks(miniCollapseRunnable)
        if (pillExpanded && !shouldClose) handler.postDelayed(miniCollapseRunnable, MINI_TIMEOUT_MS)
    }

    private fun px(dp: Int) = (dp * context.resources.displayMetrics.density).toInt()

    private fun updateFpsTracking() {
        stopFpsTracking()
        if (sessionActive && gamePackage != null && !shouldClose && (showFpsState.value || pillExpanded)) {
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
        val metrics = wm.maximumWindowMetrics
        val bounds = metrics.bounds
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
        halfWidth = bounds.width() / 2
        safeArea = insets.top + px(4)
        safeHeight = (bounds.height() - insets.bottom - px(4)).coerceAtLeast(safeArea)
        safeLeft = insets.left
        safeRight = insets.right
    }

    private fun initActions() {
        if (!sessionActive || shouldClose || gamePackage == null) return
        updateGameTaskVisibility()
        barReady = true
        updateGameBarVisibility()
        gameBarView.animate().alpha(1f).setDuration(300).start()
        circleOnLeft = appSettings.x < 0
        dockedOnLeftState.value = circleOnLeft
        dockGameBar(appSettings.y)
        runCatching { wm.updateViewLayout(gameBarView, gameBarLayoutParam) }
        tileRepository.setFpsInfoEnabled(appSettings.showFps)
        updateFpsTracking()
        setGestureLock(appSettings.inGameOptions.current(GameOption.GESTURES) == 1)
        scheduleIdle()
    }

    private fun dockGameBar(handleTop: Int = handleCenterY - px(HANDLE_HEIGHT_DP) / 2) {
        gameBarLayoutParam.width = px(MINI_WIDTH_DP)
        gameBarLayoutParam.height = px(MINI_RECORDING_HEIGHT_DP)
        gameBarLayoutParam.gravity = Gravity.TOP or (if (circleOnLeft) Gravity.LEFT else Gravity.RIGHT)
        gameBarLayoutParam.x = if (circleOnLeft) safeLeft else safeRight
        positionGameBar(handleTop)
    }

    private fun positionGameBar(handleTop: Int) {
        val top = handleTop.coerceIn(safeArea,
            (safeHeight - px(HANDLE_HEIGHT_DP)).coerceAtLeast(safeArea))
        handleCenterY = top + px(HANDLE_HEIGHT_DP) / 2
        // Keep the native surface stable; only the scene inside it changes size.
        gameBarLayoutParam.y = (handleCenterY - gameBarLayoutParam.height / 2).coerceIn(safeArea,
            (safeHeight - gameBarLayoutParam.height).coerceAtLeast(safeArea))
        barAnchorState.intValue = handleCenterY - gameBarLayoutParam.y
    }

    private val idleRunnable = Runnable {
        isIdleState.value = true
    }

    private fun enterMapperEdit() {
        updateGameTaskVisibility()
        if (!sessionActive || !gameVisible || shouldClose || gameTaskHidden) return
        mapperEditing = true
        updateStatsOverlayVisibility()
        notifyUiBlocking()
        forceRemovePanel()
        gameBarView.animate().alpha(0f).setDuration(150).withEndAction {
            gameBarView.visibility = View.GONE
        }.start()
        mapperController.onEditStarted = null
        mapperController.onEditFinished = {
            handler.post {
                if (!sessionActive) return@post
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
        if (!shouldClose && !pillExpanded && !panelShowing) handler.postDelayed(idleRunnable, IDLE_TIMEOUT_MS)
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
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        setFitInsetsTypes(0)
        width = px(MINI_WIDTH_DP)
        height = px(MINI_RECORDING_HEIGHT_DP)
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
        width = 1
        height = 1
        gravity = Gravity.TOP or Gravity.LEFT
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        preferMinimalPostProcessing = true
    }

    companion object {
        private const val PANEL_SLIDE_PX = 400
        private const val IDLE_TIMEOUT_MS = 3000L
        private const val MINI_TIMEOUT_MS = 4000L
        private const val TASK_RECHECK_DELAY_MS = 500L
        private const val TASK_QUERY_LIMIT = 16
    }
}
