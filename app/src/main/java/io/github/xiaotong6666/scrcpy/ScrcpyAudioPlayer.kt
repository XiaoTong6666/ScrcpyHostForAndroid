package io.github.xiaotong6666.scrcpy

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ScrcpyAudioPlayer(private val codecId: Int) {
    private var mediaCodec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private val bufferInfo = MediaCodec.BufferInfo()

    @Volatile
    private var prepared = false

    @Volatile
    private var released = false

    fun feedPacket(
        data: ByteArray,
        ptsUs: Long,
        isConfig: Boolean,
    ) {
        if (released) {
            return
        }

        if (isConfig) {
            when (codecId) {
                AUDIO_CODEC_OPUS -> prepareOpus(data)
                AUDIO_CODEC_AAC -> prepareAac(data)
                AUDIO_CODEC_FLAC -> prepareFlac(data)
                AUDIO_CODEC_RAW -> ensureRawAudioTrack()
            }
            return
        }

        if (codecId == AUDIO_CODEC_RAW) {
            ensureRawAudioTrack()?.write(data, 0, data.size, AudioTrack.WRITE_NON_BLOCKING)
            return
        }

        if (!prepared) {
            return
        }

        val codec = mediaCodec ?: return
        val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
        if (inputIndex >= 0) {
            val buffer = codec.getInputBuffer(inputIndex) ?: return
            buffer.clear()
            buffer.put(data)
            codec.queueInputBuffer(inputIndex, 0, data.size, ptsUs, 0)
        }
        drainOutput(codec)
    }

    private fun prepareOpus(opusHead: ByteArray) {
        if (prepared || released) {
            return
        }
        runCatching {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_OPUS,
                SAMPLE_RATE,
                CHANNELS,
            )
            format.setByteBuffer("csd-0", ByteBuffer.wrap(opusHead))
            if (opusHead.size >= 12) {
                val preSkip = ((opusHead[11].toInt() and 0xFF) shl 8) or (opusHead[10].toInt() and 0xFF)
                val codecDelayNs = preSkip.toLong() * 1_000_000_000L / SAMPLE_RATE
                format.setByteBuffer("csd-1", longBuffer(codecDelayNs))
                format.setByteBuffer("csd-2", longBuffer(OPUS_SEEK_PREROLL_NS))
            }
            startCodecAndTrack(format)
        }.onFailure { error ->
            Log.w(TAG, "prepareOpus failed", error)
        }
    }

    private fun prepareAac(aacConfig: ByteArray) {
        if (prepared || released) {
            return
        }
        runCatching {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                SAMPLE_RATE,
                CHANNELS,
            )
            format.setByteBuffer("csd-0", ByteBuffer.wrap(aacConfig))
            startCodecAndTrack(format)
        }.onFailure { error ->
            Log.w(TAG, "prepareAac failed", error)
        }
    }

    private fun prepareFlac(flacConfig: ByteArray) {
        if (prepared || released) {
            return
        }
        runCatching {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_FLAC,
                SAMPLE_RATE,
                CHANNELS,
            )
            if (flacConfig.isNotEmpty()) {
                format.setByteBuffer("csd-0", ByteBuffer.wrap(flacConfig))
            }
            startCodecAndTrack(format)
        }.onFailure { error ->
            Log.w(TAG, "prepareFlac failed", error)
        }
    }

    private fun startCodecAndTrack(format: MediaFormat) {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: error("Missing audio mime type")
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        val track = buildAudioTrack()
        codec.start()
        track.play()
        mediaCodec = codec
        audioTrack = track
        prepared = true
        Log.i(TAG, "audio started mime=$mime codecId=0x${codecId.toUInt().toString(16)}")
    }

    private fun ensureRawAudioTrack(): AudioTrack? {
        if (released) {
            return null
        }
        if (audioTrack == null) {
            audioTrack = buildAudioTrack().also { it.play() }
            prepared = true
        }
        return audioTrack
    }

    private fun buildAudioTrack(): AudioTrack {
        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(1)
        return AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build(),
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build(),
            (minBufferSize * 4).coerceAtLeast(65_536),
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
    }

    private fun drainOutput(codec: MediaCodec) {
        val track = audioTrack ?: return
        var index = codec.dequeueOutputBuffer(bufferInfo, 0L)
        while (index >= 0) {
            val output = codec.getOutputBuffer(index) ?: break
            val size = bufferInfo.size
            if (size > 0) {
                val pcm = ByteArray(size)
                output.position(bufferInfo.offset)
                output.get(pcm)
                track.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
            }
            codec.releaseOutputBuffer(index, false)
            index = codec.dequeueOutputBuffer(bufferInfo, 0L)
        }
    }

    fun release() {
        if (released) {
            return
        }
        released = true
        prepared = false
        runCatching { mediaCodec?.stop() }
        runCatching { mediaCodec?.release() }
        runCatching { audioTrack?.stop() }
        runCatching { audioTrack?.release() }
        mediaCodec = null
        audioTrack = null
    }

    private fun longBuffer(value: Long): ByteBuffer = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).apply {
        putLong(value)
        flip()
    }

    companion object {
        private const val TAG = "ScrcpyAudioPlayer"
        const val AUDIO_DISABLED = 0
        const val AUDIO_ERROR = 1
        const val AUDIO_CODEC_OPUS = 0x6f707573
        const val AUDIO_CODEC_AAC = 0x00616163
        const val AUDIO_CODEC_FLAC = 0x666c6163
        const val AUDIO_CODEC_RAW = 0x00726177
        private const val SAMPLE_RATE = 48_000
        private const val CHANNELS = 2
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val OPUS_SEEK_PREROLL_NS = 80_000_000L
    }
}
