package io.github.xiaotong6666.scrcpy

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class ScrcpyControlClient(
    private val context: Context,
    private val host: String,
    private val port: Int,
    private var connectedSocket: Socket? = null,
    private val onStatus: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val tag = "ScrcpyControlClient"
    private val lock = Any()
    private val packetQueue = LinkedBlockingQueue<ByteArray>()
    private val errorReported = AtomicBoolean(false)
    private var socket: Socket? = null
    private var output: BufferedOutputStream? = null
    private var writerThread: Thread? = null

    fun connect() {
        synchronized(lock) {
            if (output != null) {
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
            output = BufferedOutputStream(readySocket.getOutputStream())
            startWriterThreadLocked()
            Log.i(tag, "control socket connected host=$host port=$port")
        }
        onStatus(context.getString(R.string.control_channel_connected))
    }

    fun close() {
        Log.i(tag, "close()")
        val threadToJoin: Thread?
        synchronized(lock) {
            packetQueue.clear()
            threadToJoin = writerThread
            writerThread = null
            runCatching { threadToJoin?.interrupt() }
            runCatching { output?.flush() }
            runCatching { output?.close() }
            runCatching { socket?.close() }
            output = null
            socket = null
        }
        if (threadToJoin != null && Thread.currentThread() !== threadToJoin) {
            runCatching { threadToJoin.join(500) }
        }
    }

    fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) {
            return false
        }

        val packet = ByteBuffer.allocate(KEY_PACKET_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .put(TYPE_INJECT_KEYCODE.toByte())
            .put(event.action.toByte())
            .putInt(event.keyCode)
            .putInt(event.repeatCount)
            .putInt(event.metaState)
            .array()
        return writePacket(packet)
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

    private fun writePacket(packet: ByteArray): Boolean {
        synchronized(lock) {
            if (output == null || writerThread == null) {
                return false
            }
        }
        packetQueue.offer(packet)
        return true
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
                reportWriterFailure(error)
                break
            }
        }
    }

    private fun reportWriterFailure(error: Exception) {
        if (!errorReported.compareAndSet(false, true)) {
            return
        }
        close()
        onError(error.message ?: context.getString(R.string.control_channel_write_failed))
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
        private const val TYPE_INJECT_TOUCH_EVENT = 2
        private const val TYPE_INJECT_SCROLL_EVENT = 3
        private const val KEY_PACKET_SIZE = 14
        private const val TOUCH_PACKET_SIZE = 32
        private const val SCROLL_PACKET_SIZE = 21
    }
}
