package io.github.xiaotong6666.scrcpy

import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.Trace
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ScrcpyVideoStreamClient(
    private val context: Context,
    private val streamHost: String,
    private val streamPort: Int,
    private val ultraLowLatency: Boolean,
    private val tfps: Int = 120,
    private var connectedSocket: Socket? = null,
    private var renderSurface: Surface? = null,
    private val displayRefreshRateHz: Float = 60f,
    private val appVsyncOffsetNanos: Long = 0L,
    private val onStatus: (String) -> Unit,
    private val onVideoConfig: (codecName: String, width: Int, height: Int) -> Unit,
    private val onPerformanceStats: ((String) -> Unit)? = null,
    private val onError: (String) -> Unit,
    private val onEnded: (() -> Unit)? = null,
) {
    private val tag = "ScrcpyVideoStream"
    private val stopRequested = AtomicBoolean(false)
    private var worker: Thread? = null
    private var outputWorker: Thread? = null

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var decoder: MediaCodec? = null

    @Volatile
    private var renderScheduler: SurfaceRenderScheduler? = null
    private var rgbaBuffer: ByteBuffer? = null
    private var packetScratch: ByteArray? = null
    private var packetCount: Long = 0
    private var frameCount: Long = 0
    private var droppedOutputFrames: Long = 0
    private var inputNoBufferCount: Long = 0
    private var outputTryAgainLaterCount: Long = 0
    private var codecLabel: String = "Video"
    private var hardwareDecoderName: String = ""
    private var decodeLatencyEwmaMs: Double = 0.0
    private var dequeueWaitEwmaMs: Double = 0.0
    private val inputEnqueueNsByPtsUs = ConcurrentHashMap<Long, Long>()

    private var dequeuedSurfaceFrameCount: Long = 0

    @Volatile
    private var decoderCrashWarned: Boolean = false

    private var lastPresentMs = 0.0
    private var presentIntervalEwmaMs = 16.6
    private var schedulerDropCount: Long = 0
    private var lateTryDropCount: Long = 0

    private var actualWidth: Int = 0
    private var actualHeight: Int = 0
    private var bytesReceivedWindow: Long = 0
    private var windowStartMs: Long = 0
    private var receivedFramesWindow: Long = 0
    private var currentBitrateMbps: Double = 0.0
    private var currentReceivedFps: Double = 0.0
    private var pacingVarianceEwmaMs: Double = 0.0

    private var renderWindowStartMs: Long = 0
    private var renderedFramesWindow: Int = 0
    private var currentFps: Double = 0.0

    @Volatile
    private var dummySurfaceTexture: SurfaceTexture? = null

    @Volatile
    private var dummySurface: Surface? = null

    private fun getDummySurface(): Surface {
        var ds = dummySurface
        if (ds == null || !ds.isValid) {
            dummySurfaceTexture?.release()
            val st = SurfaceTexture(0)
            dummySurfaceTexture = st
            ds = Surface(st)
            dummySurface = ds
        }
        return ds
    }

    fun setSurface(surface: Surface?) {
        val oldSurface = renderSurface
        if (oldSurface === surface) {
            return
        }
        renderSurface = surface
        val activeDecoder = decoder
        if (activeDecoder != null) {
            val targetSurface = if (surface != null && surface.isValid) surface else getDummySurface()
            runCatching {
                activeDecoder.setOutputSurface(targetSurface)
            }.onFailure { error ->
                Log.e(tag, "setOutputSurface failed", error)
            }
        }
    }

    fun start() {
        if (worker != null) {
            return
        }
        stopRequested.set(false)
        packetCount = 0
        frameCount = 0
        droppedOutputFrames = 0
        inputNoBufferCount = 0
        outputTryAgainLaterCount = 0
        decodeLatencyEwmaMs = 0.0
        dequeueWaitEwmaMs = 0.0
        inputEnqueueNsByPtsUs.clear()
        hardwareDecoderName = ""
        schedulerDropCount = 0
        lateTryDropCount = 0
        lastPresentMs = 0.0
        presentIntervalEwmaMs = 16.6
        actualWidth = 0
        actualHeight = 0
        bytesReceivedWindow = 0
        windowStartMs = 0
        receivedFramesWindow = 0
        currentBitrateMbps = 0.0
        currentReceivedFps = 0.0
        pacingVarianceEwmaMs = 0.0
        renderWindowStartMs = 0
        renderedFramesWindow = 0
        currentFps = 0.0

        worker = Thread(::runStreamLoop, "scrcpy-video-stream").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        stopRequested.set(true)
        socket?.closeQuietly()
        outputWorker?.interrupt()
        worker?.interrupt()
        outputWorker?.join(1_500)
        outputWorker = null
        renderScheduler?.stop()
        renderScheduler = null
        worker?.join(1_500)
        worker = null
        socket = null
        decoder = null
    }

    private fun runStreamLoop() {
        var localSocket: Socket? = null
        var localDecoder: MediaCodec? = null
        try {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) }
            Log.i(tag, "runStreamLoop start host=$streamHost port=$streamPort")
            onStatus(context.getString(R.string.connecting_video_stream, streamHost, streamPort))
            localSocket = (
                connectedSocket ?: Socket().apply {
                    tcpNoDelay = true
                    soTimeout = 0
                    connect(InetSocketAddress(streamHost, streamPort), 8_000)
                }
                ).apply {
                tcpNoDelay = true
                soTimeout = 0
            }
            connectedSocket = null
            socket = localSocket
            Log.i(tag, "video socket connected local=${localSocket.localAddress}:${localSocket.localPort}")

            val input = DataInputStream(BufferedInputStream(localSocket.getInputStream(), 256 * 1024))
            val codecId = input.readInt()
            val announcedWidth = input.readInt()
            val announcedHeight = input.readInt()
            val mimeType = codecId.toMimeType()
            val codecName = mimeType.toCodecLabel()
            codecLabel = codecName
            Log.i(
                tag,
                "video header codecId=0x${codecId.toString(16)} codec=$codecName size=${announcedWidth}x$announcedHeight",
            )

            onStatus(context.getString(R.string.video_stream_connected_init_decoder, codecName))
            val configuredDecoder = ScrcpyVideoTuning.createConfiguredDecoder(
                mimeType = mimeType,
                width = announcedWidth,
                height = announcedHeight,
                surface = renderSurface ?: getDummySurface(),
            )
            localDecoder = configuredDecoder.codec
            decoder = localDecoder
            hardwareDecoderName = configuredDecoder.decoderName
            Log.i(
                tag,
                "decoder ready name=${configuredDecoder.decoderName} options=" +
                    configuredDecoder.appliedOptions.joinToString(),
            )
            val localRenderScheduler = if (renderSurface != null && !ultraLowLatency) {
                SurfaceRenderScheduler(localDecoder).also { scheduler ->
                    scheduler.start()
                    renderScheduler = scheduler
                }
            } else {
                null
            }
            val localOutputWorker = Thread(
                {
                    runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) }
                    drainDecoderOutputLoop(localDecoder, localRenderScheduler)
                },
                "scrcpy-video-output",
            ).apply {
                isDaemon = true
                start()
            }
            outputWorker = localOutputWorker
            onVideoConfig(codecName, announcedWidth, announcedHeight)

            while (!stopRequested.get()) {
                val ptsAndFlags = try {
                    input.readLong()
                } catch (_: EOFException) {
                    break
                }
                val packetSize = try {
                    input.readInt()
                } catch (_: EOFException) {
                    break
                }
                if (packetSize < 0) {
                    throw IllegalStateException("Invalid scrcpy packet size: $packetSize")
                }

                val packet = ensurePacketScratch(packetSize)
                traceSection("scrcpy.read_packet") {
                    input.readFully(packet, 0, packetSize)
                }

                val nowMs = System.nanoTime() / 1_000_000
                if (windowStartMs == 0L) {
                    windowStartMs = nowMs
                }
                bytesReceivedWindow += packetSize
                receivedFramesWindow += 1
                if (nowMs - windowStartMs >= 1000) {
                    val durationSeconds = (nowMs - windowStartMs) / 1000.0
                    currentBitrateMbps = (bytesReceivedWindow * 8.0) / (durationSeconds * 1_000_000.0)
                    currentReceivedFps = receivedFramesWindow / durationSeconds
                    bytesReceivedWindow = 0
                    receivedFramesWindow = 0
                    windowStartMs = nowMs
                }

                packetCount += 1
                if (packetCount == 1L || packetCount % 120 == 0L) {
                    val isConfig = (ptsAndFlags and SCRCPY_PACKET_FLAG_CONFIG) != 0L
                    Log.i(tag, "packet#$packetCount size=$packetSize config=$isConfig")
                }
                queuePacket(localDecoder, packet, packetSize, ptsAndFlags)
            }

            if (!stopRequested.get()) {
                runCatching { queueEndOfStream(localDecoder) }
                runCatching { localOutputWorker.join(1_500) }
                onStatus(context.getString(R.string.video_stream_ended))
                onEnded?.invoke()
            }
        } catch (_: EOFException) {
            if (!stopRequested.get()) {
                Log.w(tag, "video stream EOF")
                runCatching { localDecoder?.let { queueEndOfStream(it) } }
                runCatching { outputWorker?.join(1_500) }
                onStatus(context.getString(R.string.video_stream_closed))
                onEnded?.invoke()
            }
        } catch (error: Exception) {
            if (!stopRequested.get()) {
                Log.e(tag, "video stream error", error)
                onError(error.message ?: context.getString(R.string.video_stream_error_exit))
            }
        } finally {
            Log.i(
                tag,
                "runStreamLoop end packets=$packetCount frames=$frameCount dropped=$droppedOutputFrames " +
                    "inputNoBuffer=$inputNoBufferCount outputTryAgainLater=$outputTryAgainLaterCount",
            )
            stopRequested.set(true)
            outputWorker?.interrupt()
            runCatching { outputWorker?.join(1_000) }
            outputWorker = null
            renderScheduler?.stop()
            renderScheduler = null
            decoder = null
            socket = null
            localSocket?.closeQuietly()
            localDecoder?.releaseQuietly()
            dummySurface?.release()
            dummySurface = null
            dummySurfaceTexture?.release()
            dummySurfaceTexture = null
        }
    }

    private fun queuePacket(codec: MediaCodec, packet: ByteArray, packetSize: Int, ptsAndFlags: Long) {
        val flags = if ((ptsAndFlags and SCRCPY_PACKET_FLAG_CONFIG) != 0L) {
            MediaCodec.BUFFER_FLAG_CODEC_CONFIG
        } else {
            0
        }
        val presentationTimeUs = ptsAndFlags and SCRCPY_PACKET_PTS_MASK

        while (!stopRequested.get()) {
            val inputBufferIndex = runCatching {
                traceSection("scrcpy.dequeue_input") { codec.dequeueInputBuffer(10_000) }
            }.getOrElse { error ->
                if (error is IllegalStateException && !stopRequested.get()) {
                    Log.w(tag, "dequeueInputBuffer IllegalStateException", error)
                }
                break
            }
            if (inputBufferIndex < 0) {
                inputNoBufferCount += 1
                continue
            }
            val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                ?: throw IllegalStateException("Decoder input buffer is null")
            inputBuffer.clear()
            inputBuffer.put(packet, 0, packetSize)
            if (flags == 0 && presentationTimeUs >= 0) {
                inputEnqueueNsByPtsUs[presentationTimeUs] = System.nanoTime()
            }
            runCatching {
                traceSection("scrcpy.queue_input") {
                    codec.queueInputBuffer(inputBufferIndex, 0, packetSize, presentationTimeUs, flags)
                }
            }.onFailure { error ->
                if (error is IllegalStateException && !stopRequested.get()) {
                    Log.w(tag, "queueInputBuffer IllegalStateException", error)
                }
            }
            return
        }
    }

    private fun queueEndOfStream(codec: MediaCodec) {
        while (true) {
            val inputBufferIndex = codec.dequeueInputBuffer(2_000)
            if (inputBufferIndex < 0) {
                return
            }
            codec.queueInputBuffer(
                inputBufferIndex,
                0,
                0,
                0,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
            )
            return
        }
    }

    private fun drainDecoderOutputLoop(
        codec: MediaCodec,
        scheduler: SurfaceRenderScheduler?,
    ) {
        val bufferInfo = BufferInfo()

        while (!stopRequested.get()) {
            val dequeueStartedNs = System.nanoTime()
            val outputBufferIndex = try {
                traceSection("scrcpy.dequeue_output") {
                    codec.dequeueOutputBuffer(bufferInfo, 10_000)
                }
            } catch (error: IllegalStateException) {
                if (!stopRequested.get()) {
                    if (!decoderCrashWarned) {
                        Log.w(tag, "dequeueOutputBuffer IllegalStateException (surface likely lost), waiting for new surface...", error)
                        decoderCrashWarned = true
                    }
                    runCatching { Thread.sleep(50) }
                }
                continue
            }
            decoderCrashWarned = false
            updateDequeueWaitStats((System.nanoTime() - dequeueStartedNs) / 1_000_000.0)
            when {
                outputBufferIndex >= 0 -> {
                    updateDecodeLatencyStats(bufferInfo.presentationTimeUs)
                    if (renderSurface != null) {
                        if (bufferInfo.size <= 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0) {
                            runCatching { codec.releaseOutputBuffer(outputBufferIndex, false) }
                            continue
                        }
                        if (ultraLowLatency) {
                            runCatching { codec.releaseOutputBuffer(outputBufferIndex, System.nanoTime()) }
                            val nowMs = System.nanoTime() / 1_000_000.0
                            if (lastPresentMs > 0) {
                                val intervalMs = nowMs - lastPresentMs
                                presentIntervalEwmaMs = presentIntervalEwmaMs * 0.95 + intervalMs * 0.05
                                val diff = intervalMs - presentIntervalEwmaMs
                                pacingVarianceEwmaMs = pacingVarianceEwmaMs * 0.95 + Math.abs(diff) * 0.05
                            }
                            lastPresentMs = nowMs
                            frameCount += 1
                            renderedFramesWindow += 1
                            val nowMsLong = nowMs.toLong()
                            if (renderWindowStartMs == 0L) renderWindowStartMs = nowMsLong
                            if (nowMsLong - renderWindowStartMs >= 1000) {
                                currentFps = (renderedFramesWindow * 1000.0) / (nowMsLong - renderWindowStartMs)
                                renderedFramesWindow = 0
                                renderWindowStartMs = nowMsLong
                            }
                            updateRenderStatsDisplay()
                        } else {
                            scheduler?.offer(
                                DecodedOutputFrame(
                                    bufferIndex = outputBufferIndex,
                                    presentationTimeUs = bufferInfo.presentationTimeUs,
                                    flags = bufferInfo.flags,
                                    size = bufferInfo.size,
                                    dequeuedAtNs = System.nanoTime(),
                                ),
                            )
                        }
                    } else {
                        if (bufferInfo.size > 0) {
                            val image = runCatching { codec.getOutputImage(outputBufferIndex) }.getOrNull()
                            if (image != null) {
                                image.use {
                                    submitImageFrame(it)
                                }
                            }
                        }
                        runCatching { codec.releaseOutputBuffer(outputBufferIndex, false) }
                    }
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                }

                outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    val codedWidth = format.getInteger(MediaFormat.KEY_WIDTH)
                    val codedHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
                    val visibleSize = getVisibleSize(format)
                    actualWidth = visibleSize.first
                    actualHeight = visibleSize.second
                    Log.i(
                        tag,
                        "output format changed coded=${codedWidth}x$codedHeight visible=${visibleSize.first}x${visibleSize.second}",
                    )
                    onVideoConfig(codecLabel, visibleSize.first, visibleSize.second)
                    onStatus(
                        context.getString(R.string.decode_output_ready, visibleSize.first, visibleSize.second),
                    )
                }

                outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    outputTryAgainLaterCount += 1
                    continue
                }

                else -> break
            }
        }
    }

    private fun submitImageFrame(image: Image) {
        val crop = image.cropRect ?: Rect(0, 0, image.width, image.height)
        val width = crop.width()
        val height = crop.height()
        val targetBuffer = ensureRgbaBuffer(width, height)
        targetBuffer.clear()

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        for (row in 0 until height) {
            val absoluteRow = crop.top + row
            val yRowBase = absoluteRow * yPlane.rowStride
            val chromaRowBase = (absoluteRow / 2) * uPlane.rowStride
            val vChromaRowBase = (absoluteRow / 2) * vPlane.rowStride
            for (col in 0 until width) {
                val absoluteCol = crop.left + col
                val chromaCol = absoluteCol / 2

                val y = yBuffer.get(yRowBase + absoluteCol * yPlane.pixelStride).toInt() and 0xFF
                val u = uBuffer.get(chromaRowBase + chromaCol * uPlane.pixelStride).toInt() and 0xFF
                val v = vBuffer.get(vChromaRowBase + chromaCol * vPlane.pixelStride).toInt() and 0xFF

                val c = (y - 16).coerceAtLeast(0)
                val d = u - 128
                val e = v - 128
                val red = clampColor((298 * c + 409 * e + 128) shr 8)
                val green = clampColor((298 * c - 100 * d - 208 * e + 128) shr 8)
                val blue = clampColor((298 * c + 516 * d + 128) shr 8)

                targetBuffer.put(red.toByte())
                targetBuffer.put(green.toByte())
                targetBuffer.put(blue.toByte())
                targetBuffer.put(0xFF.toByte())
            }
        }

        targetBuffer.flip()
        SdlSessionBridge.submitDecodedFrame(targetBuffer, width, height, width * 4)
        frameCount += 1
        if (frameCount == 1L || frameCount % 120 == 0L) {
            Log.i(tag, "decoded frame#$frameCount ${width}x$height")
        }
    }

    private fun ensureRgbaBuffer(width: Int, height: Int): ByteBuffer {
        val requiredCapacity = width * height * 4
        val buffer = rgbaBuffer
        if (buffer == null || buffer.capacity() < requiredCapacity) {
            val replacement = ByteBuffer.allocateDirect(requiredCapacity)
            rgbaBuffer = replacement
            return replacement
        }
        return buffer
    }

    private fun ensurePacketScratch(packetSize: Int): ByteArray {
        val buffer = packetScratch
        if (buffer == null || buffer.size < packetSize) {
            val replacement = ByteArray(packetSize)
            packetScratch = replacement
            return replacement
        }
        return buffer
    }

    private fun clampColor(value: Int): Int = value.coerceIn(0, 255)

    private fun updateDecodeLatencyStats(ptsUs: Long) {
        val enqueueNs = inputEnqueueNsByPtsUs.remove(ptsUs) ?: return
        val nowNs = System.nanoTime()
        val latencyMs = (nowNs - enqueueNs) / 1_000_000.0
        val boundedLatency = latencyMs.coerceIn(0.0, 500.0)
        decodeLatencyEwmaMs = decodeLatencyEwmaMs * 0.9 + boundedLatency * 0.1
    }

    private fun updateDequeueWaitStats(waitMs: Double) {
        dequeueWaitEwmaMs = dequeueWaitEwmaMs * 0.95 + waitMs * 0.05
    }

    private fun updateRenderStatsDisplay() {
        val currentFrames = frameCount
        if (currentFrames % 60L == 0L) {
            val statsStr = buildString {
                val displayFps = if (currentFps > 0.0) currentFps else displayRefreshRateHz.toDouble()
                append(context.getString(R.string.stats_resolution_fps, actualWidth, actualHeight, displayFps)).appendLine()
                append(context.getString(R.string.stats_decoder_label, codecLabel, hardwareDecoderName)).appendLine()
                append(context.getString(R.string.stats_incoming_bitrate_label, currentBitrateMbps)).appendLine()
                append(context.getString(R.string.stats_decode_time_label, decodeLatencyEwmaMs)).appendLine()
                append(context.getString(R.string.stats_received_frames_label, packetCount)).appendLine()
                append(context.getString(R.string.stats_rendered_frames_label, frameCount)).appendLine()
                append(context.getString(R.string.stats_dropped_frames_label, (schedulerDropCount + lateTryDropCount).toInt())).appendLine()
                append(context.getString(R.string.stats_jitter_label, pacingVarianceEwmaMs))
            }
            onPerformanceStats?.invoke(statsStr.trimEnd())
        }
    }

    private fun updateEwma(current: Double, sample: Double, alpha: Double = 0.2): Double = if (current == 0.0) sample else (current * (1.0 - alpha)) + (sample * alpha)

    private fun getVisibleSize(format: MediaFormat): Pair<Int, Int> {
        val codedWidth = format.getInteger(MediaFormat.KEY_WIDTH)
        val codedHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
        if (
            format.containsKey(KEY_CROP_LEFT) &&
            format.containsKey(KEY_CROP_RIGHT) &&
            format.containsKey(KEY_CROP_TOP) &&
            format.containsKey(KEY_CROP_BOTTOM)
        ) {
            val cropWidth = format.getInteger(KEY_CROP_RIGHT) - format.getInteger(KEY_CROP_LEFT) + 1
            val cropHeight = format.getInteger(KEY_CROP_BOTTOM) - format.getInteger(KEY_CROP_TOP) + 1
            if (cropWidth > 0 && cropHeight > 0) {
                return cropWidth to cropHeight
            }
        }
        return codedWidth to codedHeight
    }

    private data class DecodedOutputFrame(
        val bufferIndex: Int,
        val presentationTimeUs: Long,
        val flags: Int,
        val size: Int,
        val dequeuedAtNs: Long,
    )

    private inner class SurfaceRenderScheduler(
        private val codec: MediaCodec,
    ) : Choreographer.FrameCallback {
        private val pendingFrames = ArrayDeque<DecodedOutputFrame>()
        private val queueLock = Any()
        private val handlerThread = HandlerThread(
            "scrcpy-video-vsync",
            Process.THREAD_PRIORITY_URGENT_DISPLAY,
        )

        @Volatile
        private var handler: Handler? = null

        @Volatile
        private var choreographer: Choreographer? = null

        @Volatile
        private var stopped = false
        private var renderLatencyEwmaMs = 0.0
        private var presentIntervalEwmaMs = 0.0
        private var queueDepthEwma = 0.0
        private var lastPresentNs = 0L
        private var lastRenderedFrameTimeNs = 0L
        private var vsyncSkipCount = 0L
        private var emptyVsyncCount = 0L
        private val refreshRateHz = displayRefreshRateHz.takeIf { it > 1f } ?: 60f
        private val vsyncPeriodNs = (1_000_000_000L / refreshRateHz).toLong().coerceAtLeast(1L)
        private val expectedFrameDeltaNs = (vsyncPeriodNs * 8L) / 10L

        fun start() {
            val ready = CountDownLatch(1)
            handlerThread.start()
            handler = Handler(handlerThread.looper)
            handler?.post {
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) }
                choreographer = Choreographer.getInstance()
                choreographer?.postFrameCallback(this)
                ready.countDown()
            }
            ready.await(1, TimeUnit.SECONDS)
        }

        fun offer(frame: DecodedOutputFrame) {
            val droppedFrames = mutableListOf<DecodedOutputFrame>()
            synchronized(queueLock) {
                pendingFrames.addLast(frame)
                queueDepthEwma = updateEwma(queueDepthEwma, pendingFrames.size.toDouble(), alpha = 0.15)
                while (pendingFrames.size > MAX_PENDING_SURFACE_FRAMES) {
                    droppedFrames += pendingFrames.removeFirst()
                }
            }
            droppedFrames.forEach {
                droppedOutputFrames += 1
                schedulerDropCount += 1
                releaseFrame(it, render = false, frameTimeNanos = 0L)
            }
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (stopped || stopRequested.get()) {
                return
            }

            val adjustedFrameTimeNs = (frameTimeNanos - appVsyncOffsetNanos).coerceAtLeast(0L)
            val actualFrameDeltaNs = adjustedFrameTimeNs - lastRenderedFrameTimeNs
            if (actualFrameDeltaNs < expectedFrameDeltaNs) {
                vsyncSkipCount += 1
                choreographer?.postFrameCallback(this)
                return
            }

            val frameToRender: DecodedOutputFrame? = synchronized(queueLock) {
                val next = if (pendingFrames.isEmpty()) {
                    null
                } else {
                    pendingFrames.removeFirst()
                }
                queueDepthEwma = updateEwma(queueDepthEwma, pendingFrames.size.toDouble(), alpha = 0.15)
                next
            }
            if (frameToRender != null) {
                releaseFrame(frameToRender, render = true, frameTimeNanos = adjustedFrameTimeNs)
                lastRenderedFrameTimeNs = adjustedFrameTimeNs
            } else {
                emptyVsyncCount += 1
            }

            choreographer?.postFrameCallback(this)
        }

        fun stop() {
            stopped = true
            val localHandler = handler
            if (localHandler == null) {
                runCatching { handlerThread.quitSafely() }
                return
            }

            val finished = CountDownLatch(1)
            localHandler.post {
                choreographer?.removeFrameCallback(this)
                val leftovers = synchronized(queueLock) {
                    val drained = mutableListOf<DecodedOutputFrame>()
                    while (pendingFrames.isNotEmpty()) {
                        drained += pendingFrames.removeFirst()
                    }
                    drained
                }
                leftovers.forEach {
                    droppedOutputFrames += 1
                    schedulerDropCount += 1
                    releaseFrame(it, render = false, frameTimeNanos = 0L)
                }
                finished.countDown()
                handlerThread.quitSafely()
            }
            finished.await(500, TimeUnit.MILLISECONDS)
            handlerThread.join(500)
        }

        private fun releaseFrame(
            frame: DecodedOutputFrame,
            render: Boolean,
            frameTimeNanos: Long,
        ) {
            try {
                if (render) {
                    traceSection("scrcpy.release_output") {
                        runCatching {
                            codec.releaseOutputBuffer(frame.bufferIndex, frameTimeNanos)
                        }.getOrElse {
                            lateTryDropCount += 1
                            codec.releaseOutputBuffer(frame.bufferIndex, true)
                        }
                    }
                    val nowNs = System.nanoTime()
                    val renderLatencyMs = (nowNs - frame.dequeuedAtNs) / 1_000_000.0
                    renderLatencyEwmaMs = updateEwma(renderLatencyEwmaMs, renderLatencyMs)
                    if (lastPresentNs != 0L) {
                        val intervalMs = (nowNs - lastPresentNs) / 1_000_000.0
                        presentIntervalEwmaMs = updateEwma(
                            presentIntervalEwmaMs,
                            intervalMs,
                        )
                        val diff = intervalMs - presentIntervalEwmaMs
                        pacingVarianceEwmaMs = updateEwma(pacingVarianceEwmaMs, Math.abs(diff))
                    }
                    lastPresentNs = nowNs
                    frameCount += 1
                    renderedFramesWindow += 1
                    val nowMs = nowNs / 1_000_000
                    if (renderWindowStartMs == 0L) renderWindowStartMs = nowMs
                    if (nowMs - renderWindowStartMs >= 1000) {
                        currentFps = (renderedFramesWindow * 1000.0) / (nowMs - renderWindowStartMs)
                        renderedFramesWindow = 0
                        renderWindowStartMs = nowMs
                    }
                    if (frameCount == 1L || frameCount % 120 == 0L) {
                        val formattedPresentMsMs = "%.2f".format(presentIntervalEwmaMs)
                        val formattedDecodeMs = "%.2f".format(decodeLatencyEwmaMs)
                        val formattedWaitMs = "%.2f".format(dequeueWaitEwmaMs)
                        updateRenderStatsDisplay()
                        Log.v(
                            tag,
                            "rendered frame#$frameCount surface decodeMs=$formattedDecodeMs " +
                                "waitMs=$formattedWaitMs paceMs=$formattedPresentMsMs drops=$schedulerDropCount",
                        )
                    }
                } else {
                    codec.releaseOutputBuffer(frame.bufferIndex, false)
                }
            } catch (error: Exception) {
                if (!stopRequested.get()) {
                    Log.w(tag, "releaseFrame failed render=$render index=${frame.bufferIndex}", error)
                }
            }
        }
    }

    private fun Int.toMimeType(): String = when (this) {
        CODEC_ID_H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
        CODEC_ID_H265 -> MediaFormat.MIMETYPE_VIDEO_HEVC
        else -> throw IllegalArgumentException("Unsupported scrcpy codec id: 0x${toString(16)}")
    }

    private fun String.toCodecLabel(): String = when (this) {
        MediaFormat.MIMETYPE_VIDEO_AVC -> "H.264"
        MediaFormat.MIMETYPE_VIDEO_HEVC -> "H.265"
        else -> this
    }

    private fun Socket.closeQuietly() {
        runCatching { close() }
    }

    private fun MediaCodec.releaseQuietly() {
        runCatching { stop() }
        runCatching { release() }
    }

    companion object {
        private const val CODEC_ID_H264 = 0x68323634
        private const val CODEC_ID_H265 = 0x68323635
        private const val SCRCPY_PACKET_FLAG_CONFIG = Long.MIN_VALUE
        private const val SCRCPY_PACKET_PTS_MASK = 0x3FFF_FFFF_FFFF_FFFFL
        private const val MAX_PENDING_SURFACE_FRAMES = 2
        private const val KEY_CROP_LEFT = "crop-left"
        private const val KEY_CROP_RIGHT = "crop-right"
        private const val KEY_CROP_TOP = "crop-top"
        private const val KEY_CROP_BOTTOM = "crop-bottom"
    }

    private inline fun <T> traceSection(name: String, block: () -> T): T {
        if (!Trace.isEnabled()) {
            return block()
        }
        Trace.beginSection(name)
        return try {
            block()
        } finally {
            Trace.endSection()
        }
    }
}
