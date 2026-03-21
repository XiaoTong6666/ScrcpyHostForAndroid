package io.github.xiaotong6666.scrcpy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.abs
import kotlin.math.roundToInt

class FloatingDisplayService : Service() {
    private enum class ResizeHandle {
        LEFT,
        RIGHT,
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tag = "FloatingDisplay"

    private lateinit var windowManager: WindowManager
    private lateinit var overlayRoot: LinearLayout
    private lateinit var headerView: LinearLayout
    private lateinit var bodyHost: ScrcpyStreamContainer
    private lateinit var surfaceView: SurfaceView
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var miniModeButton: Button
    private lateinit var collapseButton: Button
    private lateinit var closeButton: Button
    private lateinit var surfaceContainer: FrameLayout
    private lateinit var primaryActionsRow: HorizontalScrollView
    private lateinit var secondaryActionsRow: HorizontalScrollView
    private lateinit var overlayParams: WindowManager.LayoutParams

    private var host: String = ""
    private var port: Int = 5555
    private var backendUrl: String = "local://bridge"
    private var enableAudio: Boolean = true
    private var sessionConfig: ScrcpySessionConfig = ScrcpySessionConfig()
    private var sessionMode: ScrcpySessionMode = ScrcpySessionMode.CONTROL_REWORK
    private var fallbackInProgress = false
    private var sessionHandoffInProgress = false
    private var sessionCloseRequested = false
    private var reconnectAttemptCount = 0
    private var reconnectJob: Job? = null

    private var surfaceReady = CompletableDeferred<Surface>()
    private var decoderSurface: Surface? = null
    private var videoWidth: Int = 0
    private var videoHeight: Int = 0
    private var bufferWidth: Int = 0
    private var bufferHeight: Int = 0
    private var surfaceContainerHeightPx: Int = 0
    private var pendingResizeHandle: ResizeHandle? = null
    private var activeResizeHandle: ResizeHandle? = null
    private var resizeDownRawX = 0f
    private var resizeDownRawY = 0f
    private var resizeStartWidthPx = 0
    private var resizeStartHeightPx = 0
    private var resizeStartWindowX = 0
    private var resizeLongPressRunnable: Runnable? = null
    private var isMiniMode = false

    private var videoStreamClient: ScrcpyVideoStreamClient? = null
    private var audioStreamClient: ScrcpyAudioStreamClient? = null
    private var controlClient: ScrcpyControlClient? = null
    private var inputController: ScrcpyInputController? = null
    private val clipboardSyncSession by lazy {
        ClipboardSyncSession(
            context = applicationContext,
            onStatus = { message -> setStatus(message) },
            onError = { message -> setStatus(message) },
        )
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        }
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.floating_window_notification)))
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.overlay_permission_required, Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        val profile = DeviceProfileStore.fromJsonString(intent?.getStringExtra(EXTRA_PROFILE_JSON))
        host = profile?.host ?: intent?.getStringExtra(EXTRA_HOST).orEmpty()
        port = profile?.adbPort ?: (intent?.getIntExtra(EXTRA_PORT, 5555) ?: 5555)
        backendUrl = profile?.backendUrl ?: intent?.getStringExtra(EXTRA_BACKEND_URL).orEmpty().ifBlank { "local://bridge" }
        enableAudio = profile?.audioEnabled ?: (intent?.getBooleanExtra(EXTRA_ENABLE_AUDIO, true) ?: true)
        sessionConfig = profile?.sessionConfig ?: ScrcpySessionConfig.fromJsonString(intent?.getStringExtra(EXTRA_SESSION_CONFIG_JSON))
        sessionCloseRequested = false
        reconnectAttemptCount = 0

        if (host.isBlank() || port !in 1..65535) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!::overlayRoot.isInitialized) {
            installOverlayWindow()
        }
        applyRestoredFloatingWindowState()
        titleView.text = "$host:$port"
        statusView.text = getString(R.string.waiting_for_scrcpy_session)
        startSession()
        return START_STICKY
    }

    override fun onDestroy() {
        reconnectJob?.cancel()
        reconnectJob = null
        videoStreamClient?.stop()
        audioStreamClient?.stop()
        clipboardSyncSession.detach()
        controlClient?.close()
        persistFloatingWindowState()
        scope.cancel()
        runCatching {
            if (::overlayRoot.isInitialized) {
                windowManager.removeView(overlayRoot)
            }
        }
        if (!sessionHandoffInProgress) {
            runCatching {
                runBlocking {
                    BackendBridgeClient.stopScrcpySession(
                        context = applicationContext,
                        backendBaseUrl = backendUrl,
                    )
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun installOverlayWindow() {
        surfaceContainerHeightPx = dp(360)
        overlayParams = WindowManager.LayoutParams(
            dp(320),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(18)
            y = dp(96)
        }

        overlayRoot = object : LinearLayout(this) {
            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = handleOverlayResizeIntercept(ev) || super.onInterceptTouchEvent(ev)

            override fun onTouchEvent(event: MotionEvent): Boolean = if (handleOverlayResizeTouch(event)) {
                true
            } else {
                super.onTouchEvent(event)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            background = overlayBackground()
            elevation = dp(10).toFloat()
        }

        headerView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(10), dp(10))
            setBackgroundColor(Color.parseColor("#102238"))
        }
        titleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            text = getString(R.string.floating_window_title)
        }
        statusView = TextView(this).apply {
            setTextColor(Color.parseColor("#A8C7E8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            text = getString(R.string.waiting_for_scrcpy_session)
        }
        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(titleView)
            addView(statusView)
        }
        miniModeButton = Button(this).apply {
            text = getString(R.string.minimize_floating_window)
            minimumWidth = 0
            minWidth = 0
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener {
                applyMiniMode(!isMiniMode, persist = true)
            }
        }
        collapseButton = Button(this).apply {
            text = getString(R.string.expand_fullscreen)
            minimumWidth = 0
            minWidth = 0
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener {
                sessionCloseRequested = true
                sessionHandoffInProgress = true
                persistFloatingWindowState()
                val fullIntent = RemoteDisplayActivity.createIntent(
                    context = this@FloatingDisplayService,
                    profile = DeviceProfile(
                        name = "$host:$port",
                        host = host,
                        adbPort = port,
                        backendUrl = backendUrl,
                        audioEnabled = enableAudio,
                        sessionConfig = sessionConfig,
                    ),
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(fullIntent)
                stopSelf()
            }
        }
        closeButton = Button(this).apply {
            text = getString(R.string.close_floating_window)
            minimumWidth = 0
            minWidth = 0
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener {
                sessionCloseRequested = true
                persistFloatingWindowState()
                stopSelf()
            }
        }
        headerView.addView(titleColumn)
        headerView.addView(miniModeButton)
        headerView.addView(collapseButton)
        headerView.addView(closeButton)
        enableHeaderDrag()

        surfaceContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        bodyHost = ScrcpyStreamContainer(this).apply {
            setScaleMode(ScrcpyStreamContainer.ScaleMode.FIT)
        }
        surfaceView = SurfaceView(this).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    decoderSurface = holder.surface
                    if (!surfaceReady.isCompleted) {
                        surfaceReady.complete(holder.surface)
                    } else {
                        videoStreamClient?.setSurface(holder.surface)
                    }
                    updateVideoLayout(videoWidth, videoHeight)
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    updateVideoLayout(videoWidth, videoHeight)
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    videoStreamClient?.setSurface(null)
                    decoderSurface = null
                    surfaceReady = CompletableDeferred()
                }
            })
        }
        bodyHost.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        surfaceContainer.addView(
            bodyHost,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ).apply {
                width = ViewGroup.LayoutParams.WRAP_CONTENT
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            },
        )
        enableRemoteInput()

        overlayRoot.addView(
            headerView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        overlayRoot.addView(
            surfaceContainer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                surfaceContainerHeightPx,
            ),
        )
        primaryActionsRow = buildControlActionRow(ScrcpyRemoteActionLayout.primaryRow)
        overlayRoot.addView(
            primaryActionsRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        secondaryActionsRow = buildControlActionRow(ScrcpyRemoteActionLayout.secondaryRow)
        overlayRoot.addView(
            secondaryActionsRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        windowManager.addView(overlayRoot, overlayParams)
    }

    private fun enableRemoteInput() {
        bodyHost.isClickable = true
        bodyHost.isFocusable = true
        bodyHost.setOnTouchListener { _, event ->
            val controller = inputController
            if (controller != null && controller.handleMotionEvent(bodyHost, event)) {
                true
            } else {
                false
            }
        }
        bodyHost.setOnGenericMotionListener { _, event ->
            val controller = inputController
            if (controller != null && controller.handleGenericMotionEvent(bodyHost, event)) {
                true
            } else {
                false
            }
        }
    }

    private fun enableHeaderDrag() {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        headerView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = overlayParams.x
                    startY = overlayParams.y
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    overlayParams.x = startX + (event.rawX - downX).toInt()
                    overlayParams.y = startY + (event.rawY - downY).toInt()
                    windowManager.updateViewLayout(overlayRoot, overlayParams)
                    true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                -> {
                    persistFloatingWindowState()
                    true
                }

                else -> false
            }
        }
    }

    private fun handleOverlayResizeIntercept(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val handle = resolveResizeHandle(event) ?: run {
                    cancelResizeTracking()
                    return false
                }
                beginResizeTracking(handle, event)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                return pendingResizeHandle != null || activeResizeHandle != null
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                val wasTracking = pendingResizeHandle != null || activeResizeHandle != null
                cancelResizeTracking()
                return wasTracking
            }
        }
        return false
    }

    private fun handleOverlayResizeTouch(event: MotionEvent): Boolean {
        val handle = activeResizeHandle ?: pendingResizeHandle ?: return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (activeResizeHandle == null) {
                    val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
                    if (abs(event.rawX - resizeDownRawX) > touchSlop || abs(event.rawY - resizeDownRawY) > touchSlop) {
                        cancelResizeTracking()
                    }
                    true
                } else {
                    resizeOverlayWindow(
                        handle = handle,
                        deltaX = event.rawX - resizeDownRawX,
                        deltaY = event.rawY - resizeDownRawY,
                        startWidth = resizeStartWidthPx,
                        startHeight = resizeStartHeightPx,
                        startX = resizeStartWindowX,
                    )
                    true
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                if (activeResizeHandle != null) {
                    persistFloatingWindowState()
                }
                cancelResizeTracking()
                true
            }

            else -> true
        }
    }

    private fun beginResizeTracking(
        handle: ResizeHandle,
        event: MotionEvent,
    ) {
        pendingResizeHandle = handle
        activeResizeHandle = null
        resizeDownRawX = event.rawX
        resizeDownRawY = event.rawY
        resizeStartWidthPx = overlayParams.width
        resizeStartHeightPx = surfaceContainerHeightPx.takeIf { it > 0 } ?: surfaceContainer.height.takeIf { it > 0 } ?: dp(360)
        resizeStartWindowX = overlayParams.x
        resizeLongPressRunnable?.let { overlayRoot.removeCallbacks(it) }
        resizeLongPressRunnable = Runnable {
            if (pendingResizeHandle == handle) {
                activeResizeHandle = handle
                overlayRoot.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }.also {
            overlayRoot.postDelayed(it, ViewConfiguration.getLongPressTimeout().toLong())
        }
    }

    private fun cancelResizeTracking() {
        resizeLongPressRunnable?.let { overlayRoot.removeCallbacks(it) }
        resizeLongPressRunnable = null
        pendingResizeHandle = null
        activeResizeHandle = null
    }

    private fun resolveResizeHandle(event: MotionEvent): ResizeHandle? {
        if (isMiniMode) {
            return null
        }
        val cornerSize = dp(28)
        val width = overlayRoot.width.takeIf { it > 0 } ?: return null
        val height = overlayRoot.height.takeIf { it > 0 } ?: return null
        if (event.y < height - cornerSize) {
            return null
        }
        return when {
            event.x <= cornerSize -> ResizeHandle.LEFT
            event.x >= width - cornerSize -> ResizeHandle.RIGHT
            else -> null
        }
    }

    private fun resizeOverlayWindow(
        handle: ResizeHandle,
        deltaX: Float,
        deltaY: Float,
        startWidth: Int,
        startHeight: Int,
        startX: Int,
    ) {
        if (startWidth <= 0 || startHeight <= 0) {
            return
        }

        val ratio = startHeight.toFloat() / startWidth.toFloat()
        val horizontalDelta = if (handle == ResizeHandle.RIGHT) deltaX else -deltaX
        val verticalToWidthDelta = deltaY / ratio
        val widthDelta = (horizontalDelta + verticalToWidthDelta) * 0.5f

        val minWidth = dp(220)
        val maxSurfaceHeight = (resources.displayMetrics.heightPixels * 0.72f).roundToInt()
        val screenPadding = dp(12)
        val rightEdge = startX + startWidth
        val maxWidthByScreen = if (handle == ResizeHandle.RIGHT) {
            resources.displayMetrics.widthPixels - startX - screenPadding
        } else {
            rightEdge - screenPadding
        }.coerceAtLeast(minWidth)

        var newWidth = (startWidth + widthDelta).roundToInt().coerceIn(minWidth, maxWidthByScreen)
        var scale = newWidth.toFloat() / startWidth.toFloat()
        var newHeight = (startHeight * scale).roundToInt()
        if (newHeight > maxSurfaceHeight) {
            scale = maxSurfaceHeight.toFloat() / startHeight.toFloat()
            newWidth = (startWidth * scale).roundToInt().coerceAtLeast(minWidth)
            newHeight = maxSurfaceHeight
        }

        surfaceContainerHeightPx = newHeight
        overlayParams.width = newWidth
        if (handle == ResizeHandle.LEFT) {
            overlayParams.x = (rightEdge - newWidth).coerceAtLeast(screenPadding)
        }
        (surfaceContainer.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.height = newHeight
            surfaceContainer.layoutParams = params
        }
        windowManager.updateViewLayout(overlayRoot, overlayParams)
    }

    private fun applyRestoredFloatingWindowState() {
        val savedState = DeviceProfileStore.loadFloatingWindowState(
            context = applicationContext,
            host = host,
            port = port,
            backendUrl = backendUrl,
        )
        overlayParams.x = savedState?.windowX ?: dp(18)
        overlayParams.y = savedState?.windowY ?: dp(96)
        overlayParams.width = savedState?.windowWidth?.takeIf { it > 0 } ?: dp(320)
        surfaceContainerHeightPx = savedState?.contentHeight?.takeIf { it > 0 } ?: dp(360)
        (surfaceContainer.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.height = surfaceContainerHeightPx
            surfaceContainer.layoutParams = params
        }
        applyMiniMode(savedState?.miniMode ?: false, persist = false)
        windowManager.updateViewLayout(overlayRoot, overlayParams)
    }

    private fun applyMiniMode(
        miniMode: Boolean,
        persist: Boolean,
    ) {
        isMiniMode = miniMode
        surfaceContainer.visibility = if (miniMode) View.GONE else View.VISIBLE
        primaryActionsRow.visibility = if (miniMode) View.GONE else View.VISIBLE
        secondaryActionsRow.visibility = if (miniMode) View.GONE else View.VISIBLE
        statusView.visibility = if (miniMode) View.GONE else View.VISIBLE
        miniModeButton.text = getString(
            if (miniMode) {
                R.string.restore_floating_window
            } else {
                R.string.minimize_floating_window
            },
        )
        overlayRoot.requestLayout()
        windowManager.updateViewLayout(overlayRoot, overlayParams)
        if (persist) {
            persistFloatingWindowState()
        }
    }

    private fun persistFloatingWindowState() {
        if (!::overlayRoot.isInitialized || host.isBlank() || port !in 1..65535) {
            return
        }
        DeviceProfileStore.saveFloatingWindowState(
            context = applicationContext,
            host = host,
            port = port,
            backendUrl = backendUrl,
            state = FloatingWindowState(
                windowX = overlayParams.x,
                windowY = overlayParams.y,
                windowWidth = overlayParams.width,
                contentHeight = surfaceContainerHeightPx.takeIf { it > 0 } ?: dp(360),
                miniMode = isMiniMode,
            ),
        )
    }

    private fun startSession() {
        fallbackInProgress = false
        sessionMode = ScrcpySessionMode.CONTROL_REWORK
        scope.launch {
            startSessionWithMode(ScrcpySessionMode.CONTROL_REWORK, allowFallback = true)
        }
    }

    private suspend fun startSessionWithMode(
        mode: ScrcpySessionMode,
        allowFallback: Boolean,
    ) {
        setStatus(getString(R.string.requesting_scrcpy_session, mode.wireValue))
        val result = BackendBridgeClient.startScrcpySession(
            context = applicationContext,
            backendBaseUrl = backendUrl,
            host = host,
            port = port,
            audioEnabled = enableAudio,
            sessionConfig = sessionConfig,
            sessionMode = mode,
        )
        if (!result.isSuccess || result.value == null) {
            if (allowFallback && mode == ScrcpySessionMode.CONTROL_REWORK) {
                setStatus(getString(R.string.control_fallback))
                fallbackInProgress = true
                startSessionWithMode(ScrcpySessionMode.VIDEO_ONLY_FALLBACK, allowFallback = false)
            } else {
                setStatus(result.message)
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                scheduleAutoReconnect(result.message)
            }
            return
        }

        sessionMode = result.value.sessionMode
        val streamHost = if (backendUrl.trim().startsWith("local://")) "127.0.0.1" else result.value.target.substringBefore(":")
        val sockets = openSessionSockets(
            host = streamHost,
            port = result.value.streamPort,
            audioEnabled = result.value.audioEnabled,
            controlEnabled = result.value.sessionMode.controlEnabled,
        )
        if (!sockets.isSuccess || sockets.value == null) {
            setStatus(sockets.message)
            Toast.makeText(this, sockets.message, Toast.LENGTH_LONG).show()
            scheduleAutoReconnect(sockets.message)
            return
        }

        val socketBundle = sockets.value
        val outputSurface = try {
            awaitSurface()
        } catch (error: Exception) {
            socketBundle.closeQuietly()
            setStatus(error.message ?: getString(R.string.cannot_create_video_surface))
            scheduleAutoReconnect(error.message ?: getString(R.string.cannot_create_video_surface))
            return
        }

        clipboardSyncSession.detach()
        controlClient?.close()
        controlClient = if (result.value.sessionMode.controlEnabled && socketBundle.controlSocket != null) {
            ScrcpyControlClient(
                context = applicationContext,
                host = streamHost,
                port = result.value.controlPort,
                connectedSocket = socketBundle.controlSocket,
                onStatus = { message -> setStatus(message) },
                onError = { message ->
                    setStatus(message)
                    handleSessionFailure(message)
                },
                onClipboardText = { text ->
                    scope.launch(Dispatchers.Main.immediate) {
                        clipboardSyncSession.onRemoteClipboardText(text)
                    }
                },
            ).also { it.connect() }
        } else {
            null
        }
        controlClient?.let { client ->
            clipboardSyncSession.attach(
                client = client,
                automaticSyncEnabled = sessionConfig.autoSyncClipboard,
            )
        }
        inputController = controlClient?.let { ScrcpyInputController(it) }
        inputController?.setDisplayMode(ScrcpyInputController.DisplayMode.FIT_CENTER)
        applyPostConnectOptions()

        audioStreamClient?.stop()
        audioStreamClient = if (result.value.audioEnabled && socketBundle.audioSocket != null) {
            ScrcpyAudioStreamClient(
                context = applicationContext,
                streamHost = streamHost,
                streamPort = result.value.audioPort,
                connectedSocket = socketBundle.audioSocket,
                onStatus = { message -> setStatus(message) },
                onError = { message -> setStatus(message) },
            ).also { it.start() }
        } else {
            null
        }

        videoStreamClient?.stop()
        videoStreamClient = ScrcpyVideoStreamClient(
            context = applicationContext,
            streamHost = streamHost,
            streamPort = result.value.streamPort,
            ultraLowLatency = false,
            connectedSocket = socketBundle.videoSocket,
            renderSurface = outputSurface,
            onStatus = { message -> setStatus(message) },
            onVideoConfig = { _, width, height ->
                scope.launch(Dispatchers.Main.immediate) {
                    markSessionEstablished()
                    videoWidth = width
                    videoHeight = height
                    inputController?.updateVideoSize(width, height)
                    updateVideoLayout(width, height)
                    setStatus(getString(R.string.video_stream_decoded))
                }
            },
            onError = { message ->
                setStatus(message)
                handleSessionFailure(message)
            },
            onEnded = {
                handleSessionFailure(getString(R.string.video_stream_closed))
            },
        ).also { it.start() }
    }

    private suspend fun openSessionSockets(
        host: String,
        port: Int,
        audioEnabled: Boolean,
        controlEnabled: Boolean,
    ): BridgeCallResult<ScrcpySocketBundle> = withContext(Dispatchers.IO) {
        runCatching {
            val videoSocket = connectSocket(host, port, attempts = 40)
            val audioSocket = if (audioEnabled) {
                try {
                    connectSocket(host, port, attempts = 60)
                } catch (error: Exception) {
                    runCatching { videoSocket.close() }
                    throw error
                }
            } else {
                null
            }
            val controlSocket = if (controlEnabled) {
                try {
                    connectSocket(host, port, attempts = 80)
                } catch (error: Exception) {
                    runCatching { videoSocket.close() }
                    runCatching { audioSocket?.close() }
                    throw error
                }
            } else {
                null
            }

            BridgeCallResult(
                isSuccess = true,
                value = ScrcpySocketBundle(
                    videoSocket = videoSocket,
                    audioSocket = audioSocket,
                    controlSocket = controlSocket,
                ),
                message = "connected",
            )
        }.getOrElse { error ->
            BridgeCallResult(
                isSuccess = false,
                message = error.message ?: getString(R.string.cannot_establish_socket),
            )
        }
    }

    private fun connectSocket(
        host: String,
        port: Int,
        attempts: Int,
    ): Socket {
        var lastError: Exception? = null
        repeat(attempts) { index ->
            try {
                return Socket().apply {
                    tcpNoDelay = true
                    connect(InetSocketAddress(host, port), 1_000)
                }
            } catch (error: Exception) {
                lastError = error
                if (index + 1 < attempts) {
                    Thread.sleep(50L)
                }
            }
        }
        throw (lastError ?: IllegalStateException("socket connect failed"))
    }

    private suspend fun awaitSurface(): Surface = withContext(Dispatchers.Main.immediate) {
        if (::surfaceView.isInitialized) {
            val surface = surfaceView.holder.surface
            if (surface != null && surface.isValid) {
                decoderSurface = surface
                return@withContext surface
            }
        }
        surfaceReady.await()
    }

    private fun updateVideoLayout(
        width: Int,
        height: Int,
    ) {
        if (width <= 0 || height <= 0) {
            return
        }
        bodyHost.setVideoAspectRatio(width, height)
        bodyHost.setScaleMode(ScrcpyStreamContainer.ScaleMode.FIT)
        if (bufferWidth != width || bufferHeight != height) {
            runCatching { surfaceView.holder.setFixedSize(width, height) }
            bufferWidth = width
            bufferHeight = height
        }
    }

    private fun maybeFallbackToVideoOnly(reason: String): Boolean {
        if (sessionCloseRequested || sessionHandoffInProgress) {
            return false
        }
        if (sessionMode != ScrcpySessionMode.CONTROL_REWORK || fallbackInProgress) {
            return false
        }
        fallbackInProgress = true
        scope.launch {
            videoStreamClient?.stop()
            videoStreamClient = null
            clipboardSyncSession.detach()
            controlClient?.close()
            controlClient = null
            inputController = null
            runCatching {
                BackendBridgeClient.stopScrcpySession(
                    context = applicationContext,
                    backendBaseUrl = backendUrl,
                )
            }
            setStatus(reason)
            startSessionWithMode(ScrcpySessionMode.VIDEO_ONLY_FALLBACK, allowFallback = false)
        }
        return true
    }

    private fun applyPostConnectOptions() {
        val client = controlClient
        if (sessionConfig.wakeOnConnect && !sessionConfig.turnScreenOff) {
            client?.sendSetDisplayPower(true)
        }
        if (!sessionConfig.turnScreenOff) {
            return
        }
        if (client == null) {
            setStatus(getString(R.string.turn_screen_off_requires_control))
            return
        }
        if (client.sendSetDisplayPower(false)) {
            setStatus(getString(R.string.turn_screen_off_requested))
        } else {
            setStatus(getString(R.string.turn_screen_off_failed))
        }
    }

    private fun markSessionEstablished() {
        reconnectAttemptCount = 0
        reconnectJob?.cancel()
        reconnectJob = null
        sessionCloseRequested = false
    }

    private fun handleSessionFailure(reason: String) {
        if (maybeFallbackToVideoOnly(reason)) {
            return
        }
        scheduleAutoReconnect(reason)
    }

    private fun scheduleAutoReconnect(reason: String): Boolean {
        if (!sessionConfig.autoReconnect || sessionHandoffInProgress || sessionCloseRequested) {
            return false
        }
        if (reconnectJob?.isActive == true) {
            return true
        }

        val maxAttempts = sessionConfig.autoReconnectMaxAttempts.coerceAtLeast(1)
        if (reconnectAttemptCount >= maxAttempts) {
            setStatus(getString(R.string.auto_reconnect_exhausted))
            return false
        }

        val nextAttempt = reconnectAttemptCount + 1
        reconnectAttemptCount = nextAttempt
        val delaySeconds = sessionConfig.autoReconnectDelaySeconds.coerceIn(1, 60)
        reconnectJob = scope.launch {
            videoStreamClient?.stop()
            videoStreamClient = null
            audioStreamClient?.stop()
            audioStreamClient = null
            clipboardSyncSession.detach()
            controlClient?.close()
            controlClient = null
            inputController = null
            runCatching {
                BackendBridgeClient.stopScrcpySession(
                    context = applicationContext,
                    backendBaseUrl = backendUrl,
                )
            }
            setStatus(getString(R.string.auto_reconnect_scheduled, nextAttempt, maxAttempts, delaySeconds))
            delay(delaySeconds * 1_000L)
            if (sessionHandoffInProgress || sessionCloseRequested || !sessionConfig.autoReconnect) {
                return@launch
            }
            setStatus(getString(R.string.auto_reconnect_attempting, nextAttempt, maxAttempts))
            startSessionWithMode(ScrcpySessionMode.CONTROL_REWORK, allowFallback = true)
        }
        return true
    }

    private fun buildControlActionRow(actions: List<ScrcpyRemoteAction>): HorizontalScrollView = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        setPadding(dp(10), dp(8), dp(10), 0)
        addView(
            LinearLayout(this@FloatingDisplayService).apply {
                orientation = LinearLayout.HORIZONTAL
                actions.forEachIndexed { index, action ->
                    addView(buildControlActionButton(action))
                    if (index < actions.lastIndex) {
                        addView(
                            View(this@FloatingDisplayService).apply {
                                layoutParams = LinearLayout.LayoutParams(dp(8), 1)
                            },
                        )
                    }
                }
            },
        )
    }

    private fun buildControlActionButton(action: ScrcpyRemoteAction): Button = Button(this).apply {
        text = getString(action.labelRes)
        minimumWidth = 0
        minWidth = 0
        setOnClickListener { performRemoteAction(action) }
    }

    private fun performRemoteAction(action: ScrcpyRemoteAction) {
        val client = controlClient
        if (client == null) {
            setStatus(getString(R.string.remote_action_requires_control))
            return
        }

        when (action) {
            ScrcpyRemoteAction.BACK -> reportRemoteActionResult(action, client.sendBackOrScreenOnPress())

            ScrcpyRemoteAction.HOME -> reportRemoteActionResult(action, client.sendKeyPress(KeyEvent.KEYCODE_HOME))

            ScrcpyRemoteAction.RECENTS -> reportRemoteActionResult(action, client.sendKeyPress(KeyEvent.KEYCODE_APP_SWITCH))

            ScrcpyRemoteAction.NOTIFICATIONS -> reportRemoteActionResult(action, client.expandNotificationPanel())

            ScrcpyRemoteAction.SETTINGS -> reportRemoteActionResult(action, client.expandSettingsPanel())

            ScrcpyRemoteAction.ROTATE -> reportRemoteActionResult(action, client.rotateDevice())

            ScrcpyRemoteAction.POWER -> reportRemoteActionResult(action, client.sendKeyPress(KeyEvent.KEYCODE_POWER))

            ScrcpyRemoteAction.SCREENSHOT -> reportRemoteActionResult(action, client.sendKeyPress(KeyEvent.KEYCODE_SYSRQ))

            ScrcpyRemoteAction.SEND_CLIPBOARD -> {
                val text = LocalClipboardBridge.readPlainText(this)
                if (text == null) {
                    setStatus(getString(R.string.local_clipboard_empty))
                    return
                }
                if (client.sendClipboard(text, paste = false)) {
                    setStatus(getString(R.string.remote_action_sent, getString(action.labelRes)))
                } else {
                    setStatus(getString(R.string.remote_clipboard_send_failed))
                }
            }

            ScrcpyRemoteAction.RECEIVE_CLIPBOARD -> {
                if (client.requestClipboard()) {
                    setStatus(getString(R.string.remote_clipboard_requesting))
                } else {
                    setStatus(getString(R.string.remote_clipboard_request_failed))
                }
            }
        }
    }

    private fun reportRemoteActionResult(
        action: ScrcpyRemoteAction,
        success: Boolean,
    ) {
        if (success) {
            setStatus(getString(R.string.remote_action_sent, getString(action.labelRes)))
        } else {
            setStatus(getString(R.string.remote_action_failed, getString(action.labelRes)))
        }
    }

    private fun setStatus(text: String) {
        Log.i(tag, text)
        if (::statusView.isInitialized) {
            statusView.post { statusView.text = text }
        }
    }

    private fun overlayBackground(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(24).toFloat()
        setColor(Color.parseColor("#D8101827"))
        setStroke(dp(1), Color.parseColor("#4067A0C7"))
    }

    private fun buildNotification(contentText: String): Notification {
        val launchIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentImmutableFlag(),
        )
        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
        return builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.floating_window_notification_title))
            .setContentText(contentText)
            .setContentIntent(launchIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.floating_window_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun pendingIntentImmutableFlag(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        PendingIntent.FLAG_IMMUTABLE
    } else {
        0
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics,
    ).toInt()

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "floating_display"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_PROFILE_JSON = "extra_profile_json"
        private const val EXTRA_SESSION_CONFIG_JSON = "extra_session_config_json"
        private const val EXTRA_HOST = "extra_host"
        private const val EXTRA_PORT = "extra_port"
        private const val EXTRA_BACKEND_URL = "extra_backend_url"
        private const val EXTRA_ENABLE_AUDIO = "extra_enable_audio"

        fun createIntent(
            context: Context,
            profile: DeviceProfile,
        ): Intent = createIntent(
            context = context,
            host = profile.host,
            port = profile.adbPort,
            backendUrl = profile.backendUrl,
            enableAudio = profile.audioEnabled,
            sessionConfig = profile.sessionConfig,
            profileJson = DeviceProfileStore.toJsonString(profile),
        )

        fun createIntent(
            context: Context,
            host: String,
            port: Int,
            backendUrl: String,
            enableAudio: Boolean,
            sessionConfig: ScrcpySessionConfig = ScrcpySessionConfig(),
            profileJson: String? = null,
        ): Intent = Intent(context, FloatingDisplayService::class.java).apply {
            putExtra(EXTRA_PROFILE_JSON, profileJson)
            putExtra(EXTRA_HOST, host)
            putExtra(EXTRA_PORT, port)
            putExtra(EXTRA_BACKEND_URL, backendUrl)
            putExtra(EXTRA_ENABLE_AUDIO, enableAudio)
            putExtra(EXTRA_SESSION_CONFIG_JSON, sessionConfig.toJsonString())
        }

        fun launch(
            context: Context,
            profile: DeviceProfile,
        ) {
            val intent = createIntent(context, profile)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
