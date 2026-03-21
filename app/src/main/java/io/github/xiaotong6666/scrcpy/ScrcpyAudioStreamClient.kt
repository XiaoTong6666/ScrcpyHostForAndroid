package io.github.xiaotong6666.scrcpy

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class ScrcpyAudioStreamClient(
    private val context: Context,
    private val streamHost: String,
    private val streamPort: Int,
    private var connectedSocket: Socket? = null,
    private val onStatus: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val stopRequested = AtomicBoolean(false)
    private var worker: Thread? = null

    @Volatile
    private var socket: Socket? = null

    fun start() {
        if (worker != null) {
            return
        }
        stopRequested.set(false)
        worker = Thread(::runStreamLoop, "scrcpy-audio-stream").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        stopRequested.set(true)
        runCatching { socket?.close() }
        worker?.interrupt()
        worker?.join(1_000)
        worker = null
        socket = null
    }

    private fun runStreamLoop() {
        var localSocket: Socket? = null
        var player: ScrcpyAudioPlayer? = null
        try {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
            onStatus(context.getString(R.string.connecting_audio_stream, streamHost, streamPort))
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

            val input = DataInputStream(BufferedInputStream(localSocket.getInputStream(), 64 * 1024))
            val codecId = input.readInt()
            when (codecId) {
                ScrcpyAudioPlayer.AUDIO_DISABLED -> {
                    onStatus(context.getString(R.string.audio_stream_disabled))
                    return
                }

                ScrcpyAudioPlayer.AUDIO_ERROR -> {
                    onStatus(context.getString(R.string.audio_stream_unavailable))
                    return
                }
            }

            player = ScrcpyAudioPlayer(codecId)
            onStatus(context.getString(R.string.audio_stream_connected))

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
                if (packetSize <= 0) {
                    continue
                }
                val payload = ByteArray(packetSize)
                input.readFully(payload)
                val isConfig = (ptsAndFlags and PACKET_FLAG_CONFIG) != 0L
                val ptsUs = ptsAndFlags and PACKET_PTS_MASK
                player.feedPacket(
                    data = payload,
                    ptsUs = ptsUs,
                    isConfig = isConfig,
                )
            }

            if (!stopRequested.get()) {
                onStatus(context.getString(R.string.audio_stream_ended))
            }
        } catch (error: Exception) {
            if (!stopRequested.get()) {
                Log.e(TAG, "audio stream failed", error)
                onError(error.message ?: context.getString(R.string.audio_stream_error_exit))
            }
        } finally {
            runCatching { player?.release() }
            runCatching { localSocket?.close() }
        }
    }

    companion object {
        private const val TAG = "ScrcpyAudioStream"
        private const val PACKET_FLAG_CONFIG = 1L shl 63
        private const val PACKET_PTS_MASK = (1L shl 62) - 1
    }
}
