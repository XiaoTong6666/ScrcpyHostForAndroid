package io.github.xiaotong6666.scrcpy

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
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
import org.libsdl.app.SDLActivity
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

class RemoteDisplayActivity : SDLActivity() {
    private val tag = "RemoteDisplay"
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var endpoint: String
    private lateinit var backendUrl: String
    private lateinit var host: String
    private var sessionConfig: ScrcpySessionConfig = ScrcpySessionConfig()
    private var port: Int = 5555
    private var requestAudioEnabled: Boolean = true
    private var currentStatusMessage: String = ""
    private var currentVideoChannelMessage: String = ""
    private var currentAudioChannelMessage: String = ""
    private var currentPerformanceStats: String = ""

    private lateinit var performanceStatsView: TextView
    private lateinit var headerCard: LinearLayout
    private lateinit var footerCard: LinearLayout
    private lateinit var overlayToggleButton: Button
    private lateinit var decoderSurfaceContainer: FrameLayout
    private lateinit var decoderSurfaceHost: ScrcpyStreamContainer
    private lateinit var decoderSurfaceView: SurfaceView
    private var videoStreamClient: ScrcpyVideoStreamClient? = null
    private var audioStreamClient: ScrcpyAudioStreamClient? = null
    private var controlClient: ScrcpyControlClient? = null
    private var inputController: ScrcpyInputController? = null
    private val clipboardSyncSession by lazy {
        ClipboardSyncSession(
            context = applicationContext,
            onStatus = { message -> runOnUiThread { setStatus(message) } },
            onError = { message -> runOnUiThread { setVideoStatus(message) } },
        )
    }
    private var remoteTouchGestureActive = false
    private var decoderSurfaceReady = CompletableDeferred<Surface>()
    private var decoderSurface: Surface? = null
    private var activeVideoWidth: Int = 0
    private var activeVideoHeight: Int = 0
    private var surfaceBufferWidth: Int = 0
    private var surfaceBufferHeight: Int = 0
    private var overlayCardsVisible: Boolean = false

    @Volatile
    private var activeSessionMode: ScrcpySessionMode = ScrcpySessionMode.CONTROL_REWORK

    @Volatile
    private var fallbackInProgress = false
    private var sessionHandoffInProgress = false
    private var sessionCloseRequested = false
    private var reconnectAttemptCount = 0
    private var reconnectJob: Job? = null

    private var targetRefreshRate: Float = 60f

    override fun onCreate(savedInstanceState: Bundle?) {
        val profile = DeviceProfileStore.fromJsonString(intent.getStringExtra(EXTRA_PROFILE_JSON))
        host = profile?.host ?: intent.getStringExtra(EXTRA_HOST).orEmpty()
        port = profile?.adbPort ?: intent.getIntExtra(EXTRA_PORT, 5555)
        backendUrl = profile?.backendUrl ?: intent.getStringExtra(EXTRA_BACKEND_URL).orEmpty()
        requestAudioEnabled = profile?.audioEnabled ?: intent.getBooleanExtra(EXTRA_ENABLE_AUDIO, true)
        sessionConfig = profile?.sessionConfig ?: ScrcpySessionConfig.fromJsonString(intent.getStringExtra(EXTRA_SESSION_CONFIG_JSON))
        endpoint = "$host:$port"

        super.onCreate(savedInstanceState)
        requestHighRefreshRate()

        SdlSessionBridge.setSessionMetadata(endpoint, backendUrl)
        SdlSessionBridge.clearVideoFrame()
        installDecoderSurfaceView()
        SdlSessionBridge.setExternalVideoMode(true)
        title = getString(R.string.scrcpy_remote_title, endpoint)
        addContentView(buildOverlay(), fullScreenLayoutParams())
        startScrcpyVideoPipeline()
    }

    private fun requestHighRefreshRate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val display = windowManager.defaultDisplay
            val modes = display.supportedModes
            val bestMode = modes.maxByOrNull { it.refreshRate }

            if (bestMode != null && bestMode.refreshRate > 60f) {
                targetRefreshRate = bestMode.refreshRate
                val params = window.attributes
                params.preferredDisplayModeId = bestMode.modeId
                window.attributes = params
                Log.i(tag, "requestHighRefreshRate: Set preferredDisplayModeId to ${bestMode.modeId} (${bestMode.refreshRate} Hz)")
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val params = window.attributes
                    params.preferredRefreshRate = targetRefreshRate
                    window.attributes = params
                }
            }
        }
    }

    override fun onDestroy() {
        reconnectJob?.cancel()
        reconnectJob = null
        videoStreamClient?.stop()
        videoStreamClient = null
        audioStreamClient?.stop()
        audioStreamClient = null
        clipboardSyncSession.detach()
        controlClient?.close()
        controlClient = null
        inputController = null
        SdlSessionBridge.clearVideoFrame()
        SdlSessionBridge.setExternalVideoMode(false)
        decoderSurface = null
        activityScope.cancel()
        if (!sessionHandoffInProgress) {
            Thread {
                runCatching {
                    runBlocking {
                        BackendBridgeClient.stopScrcpySession(
                            context = applicationContext,
                            backendBaseUrl = backendUrl,
                        )
                    }
                }
            }.start()
        }
        super.onDestroy()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val shouldHandleRemotely = (inputController?.shouldHandleRemotely(event) == true) &&
            (remoteTouchGestureActive || !isTouchInsideOverlay(event))
        val inputView = inputTargetView()
        val handled = if (shouldHandleRemotely) {
            inputController?.handleMotionEvent(inputView, event) == true
        } else {
            false
        }

        updateRemoteTouchGestureState(event, handled)
        return if (handled) {
            true
        } else {
            super.dispatchTouchEvent(event)
        }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val shouldHandleRemotely = (inputController?.shouldHandleRemotely(event) == true) &&
            !isTouchInsideOverlay(event)
        val inputView = inputTargetView()
        val handled = if (shouldHandleRemotely) {
            inputController?.handleGenericMotionEvent(inputView, event) == true
        } else {
            false
        }
        return if (handled) {
            true
        } else {
            super.dispatchGenericMotionEvent(event)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handled = inputController?.handleKeyEvent(event) == true
        return if (handled) {
            true
        } else {
            super.dispatchKeyEvent(event)
        }
    }

    private fun startScrcpyVideoPipeline() {
        startScrcpyVideoPipelineWithMode(
            mode = ScrcpySessionMode.CONTROL_REWORK,
            allowFallback = true,
        )
    }

    private fun startScrcpyVideoPipelineWithMode(
        mode: ScrcpySessionMode,
        allowFallback: Boolean,
    ) {
        Log.i(tag, "startScrcpyVideoPipeline endpoint=$endpoint backend=$backendUrl")
        setStatus(getString(R.string.requesting_scrcpy_session, mode.wireValue))
        activityScope.launch {
            val sessionResult = BackendBridgeClient.startScrcpySession(
                context = applicationContext,
                backendBaseUrl = backendUrl,
                host = host,
                port = port,
                audioEnabled = requestAudioEnabled,
                sessionConfig = sessionConfig,
                sessionMode = mode,
            )
            if (!sessionResult.isSuccess || sessionResult.value == null) {
                Log.e(tag, "start session failed: ${sessionResult.message}")
                if (allowFallback && mode == ScrcpySessionMode.CONTROL_REWORK) {
                    Log.w(tag, "control mode start failed, fallback to video-only: ${sessionResult.message}")
                    setStatus(getString(R.string.control_fallback))
                    setVideoStatus(sessionResult.message)
                    startScrcpyVideoPipelineWithMode(
                        mode = ScrcpySessionMode.VIDEO_ONLY_FALLBACK,
                        allowFallback = false,
                    )
                } else {
                    setStatus(getString(R.string.session_start_failed))
                    setVideoStatus(sessionResult.message)
                    scheduleAutoReconnect(sessionResult.message)
                }
                return@launch
            }

            val session = sessionResult.value
            activeSessionMode = session.sessionMode
            fallbackInProgress = false
            val streamHost =
                if (backendUrl.trim().startsWith("local://")) {
                    "127.0.0.1"
                } else {
                    runCatching { URL(backendUrl).host }
                        .getOrElse { error ->
                            setStatus(getString(R.string.invalid_backend_address))
                            setVideoStatus(error.message ?: backendUrl)
                            return@launch
                        }
                }
            setStatus(getString(R.string.scrcpy_server_started))
            setVideoStatus("tcp://$streamHost:${session.streamPort}")
            Log.i(
                tag,
                "session ready target=${session.target} stream=$streamHost:${session.streamPort} " +
                    "audio=$streamHost:${session.audioPort} control=$streamHost:${session.controlPort}",
            )

            val socketBundleResult = openSessionSockets(
                host = streamHost,
                port = session.streamPort,
                audioEnabled = session.audioEnabled,
                controlEnabled = session.sessionMode.controlEnabled,
            )
            if (!socketBundleResult.isSuccess || socketBundleResult.value == null) {
                setStatus(getString(R.string.session_connection_failed))
                setVideoStatus(socketBundleResult.message)
                handleSessionFailure(socketBundleResult.message)
                return@launch
            }
            val socketBundle = socketBundleResult.value
            val videoOutputSurface = try {
                awaitVideoOutputSurface()
            } catch (error: Exception) {
                socketBundle.closeQuietly()
                setStatus(getString(R.string.video_surface_unavailable))
                setVideoStatus(error.message ?: getString(R.string.cannot_create_video_surface))
                handleSessionFailure(error.message ?: getString(R.string.cannot_create_video_surface))
                return@launch
            }

            clipboardSyncSession.detach()
            controlClient?.close()
            controlClient = if (session.sessionMode.controlEnabled && session.controlPort > 0 && socketBundle.controlSocket != null) {
                ScrcpyControlClient(
                    context = applicationContext,
                    host = streamHost,
                    port = session.controlPort,
                    connectedSocket = socketBundle.controlSocket,
                    onStatus = { message ->
                        runOnUiThread { setStatus(message + "（${session.sessionMode.wireValue}）") }
                    },
                    onError = { message ->
                        runOnUiThread {
                            setStatus(getString(R.string.control_channel_error_mode, session.sessionMode.wireValue))
                            setVideoStatus(message)
                        }
                        handleSessionFailure(getString(R.string.control_channel_error_msg, message))
                    },
                    onClipboardText = { text ->
                        runOnUiThread { clipboardSyncSession.onRemoteClipboardText(text) }
                    },
                )
            } else {
                null
            }
            runCatching { controlClient?.connect() }
                .onFailure { error ->
                    clipboardSyncSession.detach()
                    socketBundle.closeQuietly()
                    setStatus(getString(R.string.control_channel_error))
                    setVideoStatus(error.message ?: getString(R.string.cannot_connect_control_channel))
                    handleSessionFailure(error.message ?: getString(R.string.cannot_connect_control_channel))
                    return@launch
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
            audioStreamClient = if (session.audioEnabled && session.audioPort > 0 && socketBundle.audioSocket != null) {
                ScrcpyAudioStreamClient(
                    context = applicationContext,
                    streamHost = streamHost,
                    streamPort = session.audioPort,
                    connectedSocket = socketBundle.audioSocket,
                    onStatus = { message ->
                        runOnUiThread { setAudioStatus(message) }
                    },
                    onError = { message ->
                        runOnUiThread { setAudioStatus(message) }
                    },
                ).also { client ->
                    setAudioStatus(getString(R.string.audio_stream_starting))
                    client.start()
                }
            } else {
                setAudioStatus(getString(R.string.audio_stream_off))
                null
            }

            videoStreamClient?.stop()
            videoStreamClient = ScrcpyVideoStreamClient(
                context = applicationContext,
                streamHost = streamHost,
                streamPort = session.streamPort,
                ultraLowLatency = false,
                connectedSocket = socketBundle.videoSocket,
                renderSurface = videoOutputSurface,
                displayRefreshRateHz = currentDisplayRefreshRateHz(),
                appVsyncOffsetNanos = currentAppVsyncOffsetNanos(),
                onStatus = { message ->
                    runOnUiThread { setStatus(message) }
                },
                onPerformanceStats = { stats ->
                    runOnUiThread {
                        currentPerformanceStats = stats
                        updatePerformanceStatsText()
                    }
                },
                onVideoConfig = { codecName, width, height ->
                    runOnUiThread {
                        markSessionEstablished()
                        activeVideoWidth = width
                        activeVideoHeight = height
                        applyOrientationForVideo(width, height)
                        updateDecoderSurfaceLayout(width, height)
                        inputController?.setDisplayMode(ScrcpyInputController.DisplayMode.FIT_CENTER)
                        inputController?.updateVideoSize(width, height)
                        setStatus(getString(R.string.video_stream_decoded))
                        setVideoStatus("Active")
                    }
                },
                onError = { message ->
                    runOnUiThread {
                        setStatus(getString(R.string.video_stream_error_mode, session.sessionMode.wireValue))
                        setVideoStatus(message)
                    }
                    handleSessionFailure(getString(R.string.video_stream_error_msg, message))
                },
                onEnded = {
                    handleSessionFailure(getString(R.string.video_stream_closed))
                },
            ).also { client ->
                if (controlClient == null) {
                    setStatus(getString(R.string.video_channel_connected_mode, session.sessionMode.wireValue))
                }
                client.start()
            }
        }
    }

    private suspend fun openSessionSockets(
        host: String,
        port: Int,
        audioEnabled: Boolean,
        controlEnabled: Boolean,
    ): BridgeCallResult<ScrcpySocketBundle> = withContext(Dispatchers.IO) {
        runCatching {
            if (port !in 1..65535) {
                return@runCatching BridgeCallResult(
                    isSuccess = false,
                    message = getString(R.string.invalid_scrcpy_port, port),
                )
            }

            val videoSocket = connectSocketWithRetries(
                host = host,
                port = port,
                label = "video",
                attempts = 40,
                backoffMillis = 50L,
            )
            val audioSocket = if (audioEnabled) {
                try {
                    connectSocketWithRetries(
                        host = host,
                        port = port,
                        label = "audio",
                        attempts = 60,
                        backoffMillis = 50L,
                    )
                } catch (error: Exception) {
                    runCatching { videoSocket.close() }
                    throw error
                }
            } else {
                null
            }
            val controlSocket = if (controlEnabled) {
                try {
                    connectSocketWithRetries(
                        host = host,
                        port = port,
                        label = "control",
                        attempts = 80,
                        backoffMillis = 50L,
                    )
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
                value = ScrcpySocketBundle(videoSocket = videoSocket, audioSocket = audioSocket, controlSocket = controlSocket),
                message = "scrcpy sockets connected",
            )
        }.getOrElse { error ->
            Log.e(tag, "openSessionSockets failed host=$host port=$port control=$controlEnabled", error)
            BridgeCallResult(
                isSuccess = false,
                message = error.message ?: getString(R.string.cannot_establish_socket),
            )
        }
    }

    private fun connectSocketWithRetries(
        host: String,
        port: Int,
        label: String,
        attempts: Int,
        backoffMillis: Long,
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
                Log.w(
                    tag,
                    "connectSocketWithRetries $label attempt ${index + 1}/$attempts failed host=$host port=$port: ${error.message}",
                )
                if (index + 1 < attempts) {
                    Thread.sleep(backoffMillis)
                }
            }
        }
        throw (lastError ?: IllegalStateException("scrcpy $label socket connect failed"))
    }

    private fun maybeFallbackToVideoOnly(reason: String): Boolean {
        if (sessionCloseRequested || sessionHandoffInProgress) return false
        if (activeSessionMode != ScrcpySessionMode.CONTROL_REWORK) return false
        if (fallbackInProgress) return false
        fallbackInProgress = true
        Log.w(tag, "fallback to video-only mode, reason=$reason")
        activityScope.launch {
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
            setStatus(getString(R.string.control_rework_failed))
            setVideoStatus(reason)
            startScrcpyVideoPipelineWithMode(
                mode = ScrcpySessionMode.VIDEO_ONLY_FALLBACK,
                allowFallback = false,
            )
        }
        return true
    }

    private fun installDecoderSurfaceView() {
        val hostLayout = SDLActivity.getContentView() as? ViewGroup
            ?: throw IllegalStateException("SDL content view is not a ViewGroup")
        hostLayout.setBackgroundColor(Color.BLACK)
        decoderSurfaceContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                updateDecoderSurfaceLayout(activeVideoWidth, activeVideoHeight)
            }
        }
        decoderSurfaceHost = ScrcpyStreamContainer(this).apply {
            setScaleMode(ScrcpyStreamContainer.ScaleMode.FIT)
        }
        decoderSurfaceView = SurfaceView(this).apply {
            setZOrderOnTop(false)
            setZOrderMediaOverlay(false)
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    decoderSurface = holder.surface
                    applySurfaceFrameRate(holder.surface)
                    if (!decoderSurfaceReady.isCompleted) {
                        decoderSurfaceReady.complete(holder.surface)
                    } else {
                        videoStreamClient?.setSurface(holder.surface)
                    }
                    updateDecoderSurfaceLayout(activeVideoWidth, activeVideoHeight)
                    Log.i(this@RemoteDisplayActivity.tag, "decoder surface available ${width}x$height")
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    applySurfaceFrameRate(holder.surface)
                    updateDecoderSurfaceLayout(activeVideoWidth, activeVideoHeight)
                    Log.i(this@RemoteDisplayActivity.tag, "decoder surface resized ${width}x$height")
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    videoStreamClient?.setSurface(null)
                    decoderSurface = null
                    decoderSurfaceReady = CompletableDeferred()
                    surfaceBufferWidth = 0
                    surfaceBufferHeight = 0
                    Log.i(this@RemoteDisplayActivity.tag, "decoder surface destroyed")
                }
            })
        }
        decoderSurfaceHost.addView(
            decoderSurfaceView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        decoderSurfaceContainer.addView(
            decoderSurfaceHost,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        hostLayout.addView(
            decoderSurfaceContainer,
            1,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun applySurfaceFrameRate(surface: Surface) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    surface.setFrameRate(
                        targetRefreshRate,
                        Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                        Surface.CHANGE_FRAME_RATE_ALWAYS,
                    )
                } else {
                    surface.setFrameRate(
                        targetRefreshRate,
                        Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    )
                }
                Log.i(tag, "applySurfaceFrameRate: Forced surface frame rate to $targetRefreshRate Hz")
            } catch (error: Exception) {
                Log.w(tag, "Failed to apply surface frame rate", error)
            }
        }
    }

    private suspend fun awaitVideoOutputSurface(): Surface = withContext(Dispatchers.Main.immediate) {
        if (::decoderSurfaceView.isInitialized) {
            val surface = decoderSurfaceView.holder.surface
            if (surface != null && surface.isValid) {
                decoderSurface = surface
                return@withContext surface
            }
        }
        decoderSurfaceReady.await()
    }

    private fun updateDecoderSurfaceLayout(videoWidth: Int, videoHeight: Int) {
        if (!::decoderSurfaceHost.isInitialized || !::decoderSurfaceView.isInitialized) {
            return
        }
        if (videoWidth <= 0 || videoHeight <= 0) {
            return
        }

        decoderSurfaceHost.setVideoAspectRatio(videoWidth, videoHeight)
        decoderSurfaceHost.setScaleMode(ScrcpyStreamContainer.ScaleMode.FIT)
        if (surfaceBufferWidth != videoWidth || surfaceBufferHeight != videoHeight) {
            runCatching { decoderSurfaceView.holder.setFixedSize(videoWidth, videoHeight) }
            surfaceBufferWidth = videoWidth
            surfaceBufferHeight = videoHeight
            Log.i(tag, "decoder fixed buffer ${videoWidth}x$videoHeight mode=FIT")
        }
    }

    private fun inputTargetView(): View = if (::decoderSurfaceContainer.isInitialized) {
        decoderSurfaceContainer
    } else {
        window.decorView
    }

    private fun applyOrientationForVideo(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val targetOrientation = when {
            width > height -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            height > width -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
        if (requestedOrientation != targetOrientation) {
            requestedOrientation = targetOrientation
            Log.i(tag, "applyOrientationForVideo ${width}x$height -> orientation=$targetOrientation")
        }
    }

    private fun currentDisplayRefreshRateHz(): Float = runCatching { windowManager.defaultDisplay.refreshRate }
        .getOrDefault(60f)
        .takeIf { it > 1f }
        ?: 60f

    private fun currentAppVsyncOffsetNanos(): Long = runCatching { windowManager.defaultDisplay.appVsyncOffsetNanos }
        .getOrDefault(0L)
        .coerceAtLeast(0L)

    private fun buildOverlay(): FrameLayout {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = false
        }

        root.addView(buildHeaderCard(), topLayoutParams())
        root.addView(buildFooterCard(), bottomLayoutParams())
        root.addView(buildOverlayToggleButton(), topRightLayoutParams())

        setOverlayCardsVisible(false)
        return root
    }

    private fun buildOverlayToggleButton(): Button = Button(this).apply {
        overlayToggleButton = this
        text = getString(R.string.info_button)
        setOnClickListener { toggleOverlayCards() }
    }

    private fun buildHeaderCard(): LinearLayout = LinearLayout(this).apply {
        headerCard = this
        orientation = LinearLayout.VERTICAL
        background = overlayBackground()
        setPadding(dp(18), dp(16), dp(18), dp(16))

        currentStatusMessage = getString(R.string.waiting_for_scrcpy_session)
        currentVideoChannelMessage = getString(R.string.not_established)
        currentAudioChannelMessage = getString(R.string.audio_stream_off)

        addView(makeLabel("Performance metrics"))
        performanceStatsView = TextView(this@RemoteDisplayActivity).apply {
            setTextColor("#4caf50".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4), 0, dp(8))
        }
        addView(performanceStatsView)
        updatePerformanceStatsText()
    }

    private fun updatePerformanceStatsText() {
        if (!::performanceStatsView.isInitialized) return
        if (currentPerformanceStats.isEmpty()) {
            currentPerformanceStats = getString(R.string.stats_waiting_video)
        }
        val text = buildString {
            append(getString(R.string.stats_endpoint_label, endpoint)).appendLine()
            append(getString(R.string.stats_backend_label, backendUrl)).appendLine()
            append(getString(R.string.stats_status_label, currentStatusMessage)).appendLine()
            append(getString(R.string.stats_audio_label, currentAudioChannelMessage)).appendLine()
            if (currentPerformanceStats == getString(R.string.stats_waiting_video)) {
                append(getString(R.string.stats_video_label, currentVideoChannelMessage)).appendLine()
            }
            append(currentPerformanceStats)
        }
        performanceStatsView.text = text
    }

    private fun buildFooterCard(): LinearLayout = LinearLayout(this).apply {
        footerCard = this
        orientation = LinearLayout.VERTICAL
        background = overlayBackground()
        setPadding(dp(18), dp(16), dp(18), dp(16))

        addView(
            Button(this@RemoteDisplayActivity).apply {
                text = getString(R.string.return_to_connect_page)
                setOnClickListener {
                    sessionCloseRequested = true
                    finish()
                }
            },
        )
        addView(
            Button(this@RemoteDisplayActivity).apply {
                text = getString(R.string.switch_to_floating_window)
                setOnClickListener { launchFloatingWindowMode() }
            },
        )
        addView(makeLabel(getString(R.string.remote_controls)).apply { setPadding(0, dp(12), 0, dp(4)) })
        addView(makeHintValue(getString(R.string.remote_controls_hint)))
        addView(buildControlActionRow(ScrcpyRemoteActionLayout.primaryRow))
        addView(buildControlActionRow(ScrcpyRemoteActionLayout.secondaryRow))
    }

    private fun launchFloatingWindowMode() {
        if (!Settings.canDrawOverlays(this)) {
            setStatus(getString(R.string.overlay_permission_required))
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
            return
        }
        sessionCloseRequested = true
        sessionHandoffInProgress = true
        FloatingDisplayService.launch(
            context = this,
            profile = DeviceProfile(
                name = endpoint,
                host = host,
                adbPort = port,
                backendUrl = backendUrl,
                audioEnabled = requestAudioEnabled,
                sessionConfig = sessionConfig,
            ),
        )
        finish()
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
            setVideoStatus(getString(R.string.turn_screen_off_requires_control))
            return
        }
        if (client.sendSetDisplayPower(false)) {
            setStatus(getString(R.string.turn_screen_off_requested))
        } else {
            setVideoStatus(getString(R.string.turn_screen_off_failed))
        }
    }

    private fun toggleOverlayCards() {
        setOverlayCardsVisible(!overlayCardsVisible)
    }

    private fun setOverlayCardsVisible(visible: Boolean) {
        overlayCardsVisible = visible
        if (::headerCard.isInitialized) {
            headerCard.visibility = if (visible) View.VISIBLE else View.GONE
        }
        if (::footerCard.isInitialized) {
            footerCard.visibility = if (visible) View.VISIBLE else View.GONE
        }
        if (::overlayToggleButton.isInitialized) {
            overlayToggleButton.text = if (visible) getString(R.string.hide_button) else getString(R.string.info_button)
        }
    }

    private fun isTouchInsideOverlay(event: MotionEvent): Boolean {
        val pointerIndex = event.actionIndex.coerceIn(0, event.pointerCount - 1)
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)
        return isInsideViewBounds(overlayToggleButton, x, y) ||
            (
                overlayCardsVisible &&
                    (isInsideViewBounds(headerCard, x, y) || isInsideViewBounds(footerCard, x, y))
                )
    }

    private fun isInsideViewBounds(view: View, x: Float, y: Float): Boolean {
        if (view.visibility != View.VISIBLE) {
            return false
        }
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val left = location[0].toFloat()
        val top = location[1].toFloat()
        val right = left + view.width
        val bottom = top + view.height
        return x in left..right && y in top..bottom
    }

    private fun updateRemoteTouchGestureState(event: MotionEvent, handled: Boolean) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> remoteTouchGestureActive = handled

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> remoteTouchGestureActive = false
        }
    }

    private fun setStatus(text: String) {
        Log.i(tag, "status: $text")
        currentStatusMessage = text
        runOnUiThread { updatePerformanceStatsText() }
    }

    private fun setVideoStatus(text: String) {
        Log.i(tag, "video-status: $text")
        currentVideoChannelMessage = text
        runOnUiThread { updatePerformanceStatsText() }
    }

    private fun setAudioStatus(text: String) {
        Log.i(tag, "audio-status: $text")
        currentAudioChannelMessage = text
        runOnUiThread { updatePerformanceStatsText() }
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
            setVideoStatus(reason)
            return false
        }

        val nextAttempt = reconnectAttemptCount + 1
        reconnectAttemptCount = nextAttempt
        val delaySeconds = sessionConfig.autoReconnectDelaySeconds.coerceIn(1, 60)
        reconnectJob = activityScope.launch {
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
            setVideoStatus(reason)
            delay(delaySeconds * 1_000L)
            if (sessionHandoffInProgress || sessionCloseRequested || !sessionConfig.autoReconnect) {
                return@launch
            }
            setStatus(getString(R.string.auto_reconnect_attempting, nextAttempt, maxAttempts))
            startScrcpyVideoPipelineWithMode(
                mode = ScrcpySessionMode.CONTROL_REWORK,
                allowFallback = true,
            )
        }
        return true
    }

    private fun buildControlActionRow(actions: List<ScrcpyRemoteAction>): HorizontalScrollView = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        setPadding(0, dp(4), 0, 0)
        addView(
            LinearLayout(this@RemoteDisplayActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                actions.forEachIndexed { index, action ->
                    addView(buildControlActionButton(action))
                    if (index < actions.lastIndex) {
                        addView(
                            View(this@RemoteDisplayActivity).apply {
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
                    setVideoStatus(getString(R.string.remote_clipboard_send_failed))
                }
            }

            ScrcpyRemoteAction.RECEIVE_CLIPBOARD -> {
                if (client.requestClipboard()) {
                    setStatus(getString(R.string.remote_clipboard_requesting))
                } else {
                    setVideoStatus(getString(R.string.remote_clipboard_request_failed))
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
            setVideoStatus(getString(R.string.remote_action_failed, getString(action.labelRes)))
        }
    }

    private fun makeLabel(text: String): TextView = TextView(this).apply {
        setText(text)
        setTextColor(Color.parseColor("#B0E4FF"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    }

    private fun makeHintValue(text: String): TextView = TextView(this).apply {
        setText(text)
        setTextColor(Color.parseColor("#A8C7E8"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setPadding(0, dp(2), 0, dp(8))
    }

    private fun makeValue(text: String): TextView = TextView(this).apply {
        setText(text)
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        setPadding(0, dp(4), 0, dp(8))
    }

    private fun makeHint(text: String): TextView = TextView(this).apply {
        setText(text)
        setTextColor(Color.parseColor("#D6E8F5"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(0, dp(4), 0, dp(12))
    }

    private fun overlayBackground(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(24).toFloat()
        setColor(Color.parseColor("#B2101827"))
        setStroke(dp(1), Color.parseColor("#4067A0C7"))
    }

    private fun topLayoutParams(): FrameLayout.LayoutParams = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        gravity = Gravity.TOP
        setMargins(dp(18), dp(18), dp(18), 0)
    }

    private fun topRightLayoutParams(): FrameLayout.LayoutParams = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.END
        setMargins(dp(18), dp(18), dp(18), 0)
    }

    private fun bottomLayoutParams(): FrameLayout.LayoutParams = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        gravity = Gravity.BOTTOM
        setMargins(dp(18), 0, dp(18), dp(18))
    }

    private fun fullScreenLayoutParams(): FrameLayout.LayoutParams = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics,
    ).toInt()

    companion object {
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
        ): Intent = Intent(context, RemoteDisplayActivity::class.java).apply {
            putExtra(EXTRA_PROFILE_JSON, profileJson)
            putExtra(EXTRA_HOST, host)
            putExtra(EXTRA_PORT, port)
            putExtra(EXTRA_BACKEND_URL, backendUrl)
            putExtra(EXTRA_ENABLE_AUDIO, enableAudio)
            putExtra(EXTRA_SESSION_CONFIG_JSON, sessionConfig.toJsonString())
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }
}
