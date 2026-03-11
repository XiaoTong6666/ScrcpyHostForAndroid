package io.github.xiaotong6666.scrcpy

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
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
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    private var port: Int = 5555
    private var currentStatusMessage: String = ""
    private var currentVideoChannelMessage: String = ""
    private var currentPerformanceStats: String = ""

    private lateinit var performanceStatsView: TextView
    private lateinit var headerCard: LinearLayout
    private lateinit var footerCard: LinearLayout
    private lateinit var overlayToggleButton: Button
    private lateinit var decoderSurfaceContainer: FrameLayout
    private lateinit var decoderSurfaceHost: ScrcpyStreamContainer
    private lateinit var decoderSurfaceView: SurfaceView
    private var videoStreamClient: ScrcpyVideoStreamClient? = null
    private var controlClient: ScrcpyControlClient? = null
    private var inputController: ScrcpyInputController? = null
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

    private var targetRefreshRate: Float = 60f

    override fun onCreate(savedInstanceState: Bundle?) {
        host = intent.getStringExtra(EXTRA_HOST).orEmpty()
        port = intent.getIntExtra(EXTRA_PORT, 5555)
        backendUrl = intent.getStringExtra(EXTRA_BACKEND_URL).orEmpty()
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
        videoStreamClient?.stop()
        videoStreamClient = null
        controlClient?.close()
        controlClient = null
        inputController = null
        SdlSessionBridge.clearVideoFrame()
        SdlSessionBridge.setExternalVideoMode(false)
        decoderSurface = null
        activityScope.cancel()
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
                "session ready target=${session.target} stream=$streamHost:${session.streamPort} control=$streamHost:${session.controlPort}",
            )

            val socketBundleResult = openSessionSockets(
                host = streamHost,
                port = session.streamPort,
                controlEnabled = session.sessionMode.controlEnabled,
            )
            if (!socketBundleResult.isSuccess || socketBundleResult.value == null) {
                setStatus(getString(R.string.session_connection_failed))
                setVideoStatus(socketBundleResult.message)
                maybeFallbackToVideoOnly(socketBundleResult.message)
                return@launch
            }
            val socketBundle = socketBundleResult.value
            val videoOutputSurface = try {
                awaitVideoOutputSurface()
            } catch (error: Exception) {
                socketBundle.closeQuietly()
                setStatus(getString(R.string.video_surface_unavailable))
                setVideoStatus(error.message ?: getString(R.string.cannot_create_video_surface))
                maybeFallbackToVideoOnly(error.message ?: getString(R.string.cannot_create_video_surface))
                return@launch
            }

            controlClient?.close()
            controlClient = if (session.sessionMode.controlEnabled && session.controlPort > 0 && socketBundle.controlSocket != null) {
                ScrcpyControlClient(
                    context = applicationContext,
                    host = session.target.substringBefore(":"),
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
                        maybeFallbackToVideoOnly(getString(R.string.control_channel_error_msg, message))
                    },
                )
            } else {
                null
            }
            runCatching { controlClient?.connect() }
                .onFailure { error ->
                    socketBundle.closeQuietly()
                    setStatus(getString(R.string.control_channel_error))
                    setVideoStatus(error.message ?: getString(R.string.cannot_connect_control_channel))
                    maybeFallbackToVideoOnly(error.message ?: getString(R.string.cannot_connect_control_channel))
                    return@launch
                }
            inputController = controlClient?.let { ScrcpyInputController(it) }
            inputController?.setDisplayMode(ScrcpyInputController.DisplayMode.FIT_CENTER)

            videoStreamClient?.stop()
            videoStreamClient = ScrcpyVideoStreamClient(
                context = applicationContext,
                streamHost = session.target.substringBefore(":"),
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
                    maybeFallbackToVideoOnly(getString(R.string.video_stream_error_msg, message))
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
                    throw error
                }
            } else {
                null
            }

            BridgeCallResult(
                isSuccess = true,
                value = ScrcpySocketBundle(videoSocket = videoSocket, controlSocket = controlSocket),
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

    private fun maybeFallbackToVideoOnly(reason: String) {
        if (activeSessionMode != ScrcpySessionMode.CONTROL_REWORK) return
        if (fallbackInProgress) return
        fallbackInProgress = true
        Log.w(tag, "fallback to video-only mode, reason=$reason")
        activityScope.launch {
            videoStreamClient?.stop()
            videoStreamClient = null
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
                setOnClickListener { finish() }
            },
        )
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

    private fun makeLabel(text: String): TextView = TextView(this).apply {
        setText(text)
        setTextColor(Color.parseColor("#B0E4FF"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
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
        private const val EXTRA_HOST = "extra_host"
        private const val EXTRA_PORT = "extra_port"
        private const val EXTRA_BACKEND_URL = "extra_backend_url"

        fun createIntent(
            context: Context,
            host: String,
            port: Int,
            backendUrl: String,
        ): Intent = Intent(context, RemoteDisplayActivity::class.java).apply {
            putExtra(EXTRA_HOST, host)
            putExtra(EXTRA_PORT, port)
            putExtra(EXTRA_BACKEND_URL, backendUrl)
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }
}

private data class ScrcpySocketBundle(
    val videoSocket: Socket,
    val controlSocket: Socket?,
) {
    fun closeQuietly() {
        runCatching { videoSocket.close() }
        runCatching { controlSocket?.close() }
    }
}
