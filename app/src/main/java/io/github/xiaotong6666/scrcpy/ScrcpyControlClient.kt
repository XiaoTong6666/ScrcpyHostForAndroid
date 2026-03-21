package io.github.xiaotong6666.scrcpy

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class ScrcpyControlClient(
    private val context: Context,
    private val host: String,
    private val port: Int,
    private var connectedSocket: Socket? = null,
    private val onStatus: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onClipboardText: (String) -> Unit = {},
) {
    private val tag = "ScrcpyControlClient"
    private val lock = Any()
    private val packetQueue = LinkedBlockingQueue<ByteArray>()
    private val errorReported = AtomicBoolean(false)
    private val clipboardSequence = AtomicLong(1L)
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: BufferedOutputStream? = null
    private var readerThread: Thread? = null
    private var writerThread: Thread? = null

    fun connect() {
        synchronized(lock) {
            if (output != null && input != null) {
                Log.i(tag, "connect skipped: already connected")
                return
            }
            if (port !in 1..65535) {
                throw IllegalArgumentException("Invalid control port: $port")
            }

            val readySocket = connectedSocket ?: run {
                var lastError: Exception? = null
                (1..8).asSequence().mapNotNull { attempt ->
                    runCatching {
                        Socket().apply {
                            tcpNoDelay = true
                            connect(InetSocketAddress(host, port), 1_500)
                        }
                    }.onFailure { error ->
                        lastError = error as? Exception
                        Log.w(tag, "control connect attempt $attempt/8 failed host=$host port=$port: ${error.message}")
                        if (attempt < 8) {
                            Thread.sleep(100L * attempt)
                        }
                    }.getOrNull()
                }.firstOrNull() ?: throw (lastError ?: IllegalStateException("control connect failed"))
            }
            connectedSocket = null

            readySocket.apply {
                tcpNoDelay = true
            }
            errorReported.set(false)
            socket = readySocket
            input = DataInputStream(BufferedInputStream(readySocket.getInputStream()))
            output = BufferedOutputStream(readySocket.getOutputStream())
            startReaderThreadLocked()
            startWriterThreadLocked()
            Log.i(tag, "control socket connected host=$host port=$port")
        }
        onStatus(context.getString(R.string.control_channel_connected))
    }

    fun close() {
        Log.i(tag, "close()")
        val readerToJoin: Thread?
        val writerToJoin: Thread?
        synchronized(lock) {
            packetQueue.clear()
            readerToJoin = readerThread
            writerToJoin = writerThread
            readerThread = null
            writerThread = null
            runCatching { readerToJoin?.interrupt() }
            runCatching { writerToJoin?.interrupt() }
            runCatching { input?.close() }
            runCatching { output?.flush() }
            runCatching { output?.close() }
            runCatching { socket?.close() }
            input = null
            output = null
            socket = null
        }
        if (readerToJoin != null && Thread.currentThread() !== readerToJoin) {
            runCatching { readerToJoin.join(500) }
        }
        if (writerToJoin != null && Thread.currentThread() !== writerToJoin) {
            runCatching { writerToJoin.join(500) }
        }
    }

    fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) {
            return false
        }

        return writePacket(
            buildKeyPacket(
                action = event.action,
                keyCode = event.keyCode,
                repeatCount = event.repeatCount,
                metaState = event.metaState,
            ),
        )
    }

    fun sendKeyPress(
        keyCode: Int,
        metaState: Int = 0,
    ): Boolean {
        val downPacket = buildKeyPacket(
            action = KeyEvent.ACTION_DOWN,
            keyCode = keyCode,
            repeatCount = 0,
            metaState = metaState,
        )
        val upPacket = buildKeyPacket(
            action = KeyEvent.ACTION_UP,
            keyCode = keyCode,
            repeatCount = 0,
            metaState = metaState,
        )
        return writePacket(downPacket) && writePacket(upPacket)
    }

    fun sendTouchEvent(
        action: Int,
        pointerId: Long,
        x: Int,
        y: Int,
        videoWidth: Int,
        videoHeight: Int,
        pressure: Float,
        actionButton: Int,
        buttons: Int,
    ): Boolean {
        val packet = ByteBuffer.allocate(TOUCH_PACKET_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .put(TYPE_INJECT_TOUCH_EVENT.toByte())
            .put(action.toByte())
            .putLong(pointerId)
            .putInt(x)
            .putInt(y)
            .putShort(videoWidth.toShort())
            .putShort(videoHeight.toShort())
            .putShort(floatToUnsignedFixedPoint16(pressure).toShort())
            .putInt(actionButton)
            .putInt(buttons)
            .array()
        return writePacket(packet)
    }

    fun sendScrollEvent(
        x: Int,
        y: Int,
        videoWidth: Int,
        videoHeight: Int,
        hScroll: Float,
        vScroll: Float,
        buttons: Int,
    ): Boolean {
        val packet = ByteBuffer.allocate(SCROLL_PACKET_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .put(TYPE_INJECT_SCROLL_EVENT.toByte())
            .putInt(x)
            .putInt(y)
            .putShort(videoWidth.toShort())
            .putShort(videoHeight.toShort())
            .putShort(floatToSignedFixedPoint16((hScroll / 16f).coerceIn(-1f, 1f)).toShort())
            .putShort(floatToSignedFixedPoint16((vScroll / 16f).coerceIn(-1f, 1f)).toShort())
            .putInt(buttons)
            .array()
        return writePacket(packet)
    }

    fun sendSetDisplayPower(on: Boolean): Boolean = writePacket(
        byteArrayOf(
            TYPE_SET_DISPLAY_POWER.toByte(),
            if (on) 1 else 0,
        ),
    )

    fun sendBackOrScreenOn(action: Int): Boolean = writePacket(
        byteArrayOf(
            TYPE_BACK_OR_SCREEN_ON.toByte(),
            action.toByte(),
        ),
    )

    fun sendBackOrScreenOnPress(): Boolean = sendBackOrScreenOn(KeyEvent.ACTION_DOWN) && sendBackOrScreenOn(KeyEvent.ACTION_UP)

    fun expandNotificationPanel(): Boolean = writePacket(byteArrayOf(TYPE_EXPAND_NOTIFICATION_PANEL.toByte()))

    fun expandSettingsPanel(): Boolean = writePacket(byteArrayOf(TYPE_EXPAND_SETTINGS_PANEL.toByte()))

    fun collapsePanels(): Boolean = writePacket(byteArrayOf(TYPE_COLLAPSE_PANELS.toByte()))

    fun rotateDevice(): Boolean = writePacket(byteArrayOf(TYPE_ROTATE_DEVICE.toByte()))

    fun requestClipboard(copyKey: Int = COPY_KEY_NONE): Boolean = writePacket(
        byteArrayOf(
            TYPE_GET_CLIPBOARD.toByte(),
            copyKey.toByte(),
        ),
    )

    fun sendClipboard(
        text: String,
        paste: Boolean = false,
    ): Boolean {
        val rawText = text.trim()
        if (rawText.isBlank()) {
            return false
        }
        val payload = truncateUtf8(rawText, CLIPBOARD_TEXT_MAX_LENGTH)
        val sequence = clipboardSequence.getAndIncrement()
        val packet = ByteBuffer.allocate(1 + 8 + 1 + 4 + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(TYPE_SET_CLIPBOARD.toByte())
            .putLong(sequence)
            .put((if (paste) 1 else 0).toByte())
            .putInt(payload.size)
            .put(payload)
            .array()
        return writePacket(packet)
    }

    private fun writePacket(packet: ByteArray): Boolean {
        synchronized(lock) {
            if (output == null || writerThread == null) {
                return false
            }
        }
        packetQueue.offer(packet)
        return true
    }

    private fun startReaderThreadLocked() {
        if (readerThread != null) {
            return
        }
        readerThread = Thread(::runReaderLoop, "scrcpy-control-reader").apply {
            isDaemon = true
            start()
        }
    }

    private fun startWriterThreadLocked() {
        if (writerThread != null) {
            return
        }
        writerThread = Thread(::runWriterLoop, "scrcpy-control-writer").apply {
            isDaemon = true
            start()
        }
    }

    private fun runReaderLoop() {
        while (!Thread.currentThread().isInterrupted) {
            val target = synchronized(lock) { input } ?: break
            try {
                when (target.readUnsignedByte()) {
                    DEVICE_MESSAGE_TYPE_CLIPBOARD -> {
                        val length = target.readInt().coerceAtLeast(0)
                        val payload = ByteArray(length)
                        target.readFully(payload)
                        onClipboardText(String(payload, StandardCharsets.UTF_8))
                    }

                    DEVICE_MESSAGE_TYPE_ACK_CLIPBOARD -> {
                        target.readLong()
                    }

                    DEVICE_MESSAGE_TYPE_UHID_OUTPUT -> {
                        target.readUnsignedShort()
                        val length = target.readUnsignedShort()
                        skipFully(target, length)
                    }

                    else -> {
                        throw IllegalStateException("Unknown device message type")
                    }
                }
            } catch (_: EOFException) {
                val shouldReport = !Thread.currentThread().isInterrupted
                if (shouldReport) {
                    reportChannelFailure(EOFException("Control channel closed"))
                }
                break
            } catch (_: InterruptedException) {
                break
            } catch (error: Exception) {
                val closedByUs = synchronized(lock) { input == null || socket == null }
                if (closedByUs) {
                    break
                }
                Log.e(tag, "control reader failed", error)
                reportChannelFailure(error)
                break
            }
        }
    }

    private fun runWriterLoop() {
        while (!Thread.currentThread().isInterrupted) {
            val packet = try {
                packetQueue.take()
            } catch (_: InterruptedException) {
                break
            }

            val target = synchronized(lock) { output } ?: break
            try {
                target.write(packet)
                target.flush()
            } catch (error: Exception) {
                Log.e(tag, "writePacket failed size=${packet.size}", error)
                reportChannelFailure(error)
                break
            }
        }
    }

    private fun reportChannelFailure(error: Exception) {
        if (!errorReported.compareAndSet(false, true)) {
            return
        }
        close()
        onError(error.message ?: context.getString(R.string.control_channel_write_failed))
    }

    private fun buildKeyPacket(
        action: Int,
        keyCode: Int,
        repeatCount: Int,
        metaState: Int,
    ): ByteArray = ByteBuffer.allocate(KEY_PACKET_SIZE)
        .order(ByteOrder.BIG_ENDIAN)
        .put(TYPE_INJECT_KEYCODE.toByte())
        .put(action.toByte())
        .putInt(keyCode)
        .putInt(repeatCount)
        .putInt(metaState)
        .array()

    private fun truncateUtf8(
        text: String,
        maxBytes: Int,
    ): ByteArray {
        var candidate = text
        while (candidate.isNotEmpty()) {
            val bytes = candidate.toByteArray(StandardCharsets.UTF_8)
            if (bytes.size <= maxBytes) {
                return bytes
            }
            candidate = candidate.dropLast(1)
        }
        return ByteArray(0)
    }

    private fun skipFully(
        input: DataInputStream,
        length: Int,
    ) {
        var remaining = length
        while (remaining > 0) {
            val skipped = input.skipBytes(remaining)
            if (skipped <= 0) {
                throw EOFException("Unable to skip $remaining byte(s)")
            }
            remaining -= skipped
        }
    }

    private fun floatToUnsignedFixedPoint16(value: Float): Int {
        val clamped = value.coerceIn(0f, 1f)
        val encoded = (clamped * 65536.0f).toInt()
        return encoded.coerceAtMost(0xFFFF)
    }

    private fun floatToSignedFixedPoint16(value: Float): Int {
        val clamped = value.coerceIn(-1f, 1f)
        val encoded = (clamped * 32768.0f).toInt()
        return encoded.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
    }

    companion object {
        private const val TYPE_INJECT_KEYCODE = 0
        private const val TYPE_BACK_OR_SCREEN_ON = 4
        private const val TYPE_EXPAND_NOTIFICATION_PANEL = 5
        private const val TYPE_EXPAND_SETTINGS_PANEL = 6
        private const val TYPE_COLLAPSE_PANELS = 7
        private const val TYPE_GET_CLIPBOARD = 8
        private const val TYPE_SET_CLIPBOARD = 9
        private const val TYPE_INJECT_TOUCH_EVENT = 2
        private const val TYPE_INJECT_SCROLL_EVENT = 3
        private const val TYPE_SET_DISPLAY_POWER = 10
        private const val TYPE_ROTATE_DEVICE = 11
        private const val KEY_PACKET_SIZE = 14
        private const val TOUCH_PACKET_SIZE = 32
        private const val SCROLL_PACKET_SIZE = 21
        private const val DEVICE_MESSAGE_TYPE_CLIPBOARD = 0
        private const val DEVICE_MESSAGE_TYPE_ACK_CLIPBOARD = 1
        private const val DEVICE_MESSAGE_TYPE_UHID_OUTPUT = 2
        private const val COPY_KEY_NONE = 0
        private const val CLIPBOARD_TEXT_MAX_LENGTH = (1 shl 18) - 14
    }
}
