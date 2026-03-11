package io.github.xiaotong6666.scrcpy

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import kotlin.math.max

data class ScrcpyServerVideoOptions(
    val codecName: String,
    val codecId: Int,
    val maxSize: Int,
    val maxFps: Int,
    val bitRate: Long,
    val decoderName: String?,
)

data class ConfiguredScrcpyDecoder(
    val codec: MediaCodec,
    val decoderName: String,
    val appliedOptions: List<String>,
)

object ScrcpyVideoTuning {
    private const val TAG = "ScrcpyVideoTuning"
    const val CODEC_ID_H264 = 0x68323634
    const val CODEC_ID_H265 = 0x68323635

    private val softwareDecoderPrefixes = listOf(
        "omx.google",
        "c2.android",
        "omx.ffmpeg",
        "avcdecoder",
    )

    private val qualcommDecoderPrefixes = listOf("omx.qcom", "c2.qti", "c2.qcom")
    private val hisiDecoderPrefixes = listOf("omx.hisi", "c2.hisi")
    private val realtekDecoderPrefixes = listOf("omx.realtek", "c2.realtek", "c2.rtk")
    private val vendorLowLatencyPrefixes = listOf(
        qualcommDecoderPrefixes,
        hisiDecoderPrefixes,
        realtekDecoderPrefixes,
        listOf("omx.mtk", "c2.mtk", "omx.exynos", "c2.exynos", "omx.nvidia", "c2.nvidia"),
    ).flatten()

    fun chooseServerVideoOptions(context: Context): ScrcpyServerVideoOptions {
        val avcDecoder = findPreferredDecoderInfo(MediaFormat.MIMETYPE_VIDEO_AVC)
        val hevcDecoder = findPreferredDecoderInfo(MediaFormat.MIMETYPE_VIDEO_HEVC)
        val useHevc = shouldPreferHevc(avcDecoder, hevcDecoder)

        val metrics = context.resources.displayMetrics
        val longEdge = max(metrics.widthPixels, metrics.heightPixels)
        val maxSize = when {
            longEdge >= 2200 -> 1920
            longEdge >= 1600 -> 1600
            else -> 1280
        }
        val maxFps = 120
        val bitRate = when {
            useHevc && maxSize >= 1920 -> 10_000_000L
            useHevc -> 8_000_000L
            maxSize >= 1920 -> 12_000_000L
            else -> 10_000_000L
        }
        val selectedDecoder = if (useHevc) hevcDecoder else avcDecoder
        val options = ScrcpyServerVideoOptions(
            codecName = if (useHevc) "h265" else "h264",
            codecId = if (useHevc) CODEC_ID_H265 else CODEC_ID_H264,
            maxSize = maxSize,
            maxFps = maxFps,
            bitRate = bitRate,
            decoderName = selectedDecoder?.name,
        )

        Log.i(
            TAG,
            "server video options codec=${options.codecName} maxSize=${options.maxSize} " +
                "maxFps=${options.maxFps} bitRate=${options.bitRate} decoder=${options.decoderName}",
        )
        return options
    }

    fun createConfiguredDecoder(
        mimeType: String,
        width: Int,
        height: Int,
        surface: Surface? = null,
    ): ConfiguredScrcpyDecoder {
        val candidates = buildCandidateList(mimeType)
        var lastError: Throwable? = null

        for (candidate in candidates) {
            for (strategy in DecoderStrategy.entries) {
                val codec = try {
                    MediaCodec.createByCodecName(candidate.name)
                } catch (error: Exception) {
                    lastError = error
                    Log.w(TAG, "createByCodecName failed decoder=${candidate.name}", error)
                    continue
                }

                try {
                    val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
                        if (surface == null) {
                            setInteger(
                                MediaFormat.KEY_COLOR_FORMAT,
                                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                            )
                        }
                    }
                    val appliedOptions = strategy.apply(format, candidate, mimeType)
                    codec.configure(format, surface, null, 0)
                    codec.start()
                    Log.i(
                        TAG,
                        "configured decoder=${candidate.name} mime=$mimeType strategy=${strategy.name} " +
                            "options=${appliedOptions.joinToString()}",
                    )
                    return ConfiguredScrcpyDecoder(
                        codec = codec,
                        decoderName = candidate.name,
                        appliedOptions = appliedOptions,
                    )
                } catch (error: Exception) {
                    lastError = error
                    Log.w(
                        TAG,
                        "decoder configure failed decoder=${candidate.name} strategy=${strategy.name}",
                        error,
                    )
                    runCatching { codec.stop() }
                    runCatching { codec.release() }
                }
            }
        }

        return runCatching {
            val codec = MediaCodec.createDecoderByType(mimeType)
            val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
                if (surface == null) {
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                    )
                }
            }
            codec.configure(format, surface, null, 0)
            codec.start()
            ConfiguredScrcpyDecoder(
                codec = codec,
                decoderName = codec.name,
                appliedOptions = emptyList(),
            )
        }.getOrElse { error ->
            throw IllegalStateException(
                "Unable to configure decoder for $mimeType (${width}x$height): " +
                    "${lastError?.message ?: error.message}",
                error,
            )
        }
    }

    fun decoderSupportsLowLatency(decoderInfo: MediaCodecInfo, mimeType: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                if (decoderInfo.getCapabilitiesForType(mimeType)
                        .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
                ) {
                    return true
                }
            }
        }

        return startsWithAny(decoderInfo.name, vendorLowLatencyPrefixes)
    }

    private fun shouldPreferHevc(
        avcDecoder: MediaCodecInfo?,
        hevcDecoder: MediaCodecInfo?,
    ): Boolean {
        if (hevcDecoder == null) {
            return false
        }
        if (avcDecoder == null) {
            return true
        }

        val hevcLowLatency = decoderSupportsLowLatency(hevcDecoder, MediaFormat.MIMETYPE_VIDEO_HEVC)
        val avcLowLatency = decoderSupportsLowLatency(avcDecoder, MediaFormat.MIMETYPE_VIDEO_AVC)
        return hevcLowLatency && !avcLowLatency
    }

    private fun buildCandidateList(mimeType: String): List<MediaCodecInfo> {
        val allCandidates = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .asSequence()
            .filter { !it.isEncoder }
            .filter { info ->
                runCatching { info.supportedTypes.any { type -> type.equals(mimeType, ignoreCase = true) } }
                    .getOrDefault(false)
            }
            .toList()

        val hardwareFirst = allCandidates
            .sortedWith(
                compareByDescending<MediaCodecInfo> { !isLikelySoftwareDecoder(it) }
                    .thenByDescending { decoderSupportsLowLatency(it, mimeType) }
                    .thenBy { it.name },
            )

        return if (hardwareFirst.any { !isLikelySoftwareDecoder(it) }) {
            hardwareFirst.filter { !isLikelySoftwareDecoder(it) } + hardwareFirst.filter { isLikelySoftwareDecoder(it) }
        } else {
            hardwareFirst
        }
    }

    private fun findPreferredDecoderInfo(mimeType: String): MediaCodecInfo? = buildCandidateList(mimeType).firstOrNull()

    private fun isLikelySoftwareDecoder(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return info.isSoftwareOnly
        }
        return startsWithAny(info.name, softwareDecoderPrefixes)
    }

    private fun startsWithAny(value: String?, prefixes: List<String>): Boolean {
        val normalized = value?.lowercase().orEmpty()
        return prefixes.any { normalized.startsWith(it) }
    }

    private fun safeSet(format: MediaFormat, key: String, value: Int, applied: MutableList<String>) {
        runCatching {
            format.setInteger(key, value)
            applied += "$key=$value"
        }.onFailure { error ->
            Log.w(TAG, "ignoring unsupported decoder option $key", error)
        }
    }

    private enum class DecoderStrategy {
        OFFICIAL_ONLY {
            override fun apply(
                format: MediaFormat,
                decoderInfo: MediaCodecInfo,
                mimeType: String,
            ): List<String> {
                val applied = mutableListOf<String>()
                safeSet(format, "low-latency", 1, applied)
                return applied
            }
        },
        OFFICIAL_AND_VENDOR {
            override fun apply(
                format: MediaFormat,
                decoderInfo: MediaCodecInfo,
                mimeType: String,
            ): List<String> {
                val applied = mutableListOf<String>()
                safeSet(format, "low-latency", 1, applied)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    safeSet(format, MediaFormat.KEY_PRIORITY, 0, applied)
                    safeSet(format, MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt(), applied)
                }

                val decoderName = decoderInfo.name.lowercase()
                when {
                    qualcommDecoderPrefixes.any { decoderName.startsWith(it) } -> {
                        safeSet(format, "vendor.qti-ext-dec-picture-order.enable", if (decoderName.startsWith("omx.qcom")) 0 else 1, applied)
                        safeSet(format, "vendor.qti-ext-dec-low-latency.enable", 1, applied)
                        safeSet(format, "vendor.qti-ext-output-sw-fence-enable.value", 1, applied)
                        safeSet(format, "vendor.qti-ext-output-fence.enable", 1, applied)
                        safeSet(format, "vendor.qti-ext-output-fence.fence_type", 1, applied)
                    }

                    decoderName.startsWith("omx.mtk") || decoderName.startsWith("c2.mtk") -> {
                        safeSet(format, "vdec-lowlatency", 1, applied)
                        safeSet(format, "vendor.mtk.vdec.cpu.boost.mode", 1, applied)
                        safeSet(format, "vendor.mtk.vdec.cpu.boost.mode.value", 1, applied)
                        safeSet(format, "vendor.mtk.vdec.dvfs.mode", 1, applied)
                        safeSet(format, "vendor.mtk.vdec.dvfs.level", 1, applied)
                        safeSet(format, "vendor.mtk.vdec.low-latency.mode", 1, applied)
                        safeSet(format, "vendor.mtk.vdec.ultra-low-latency", 0, applied)
                        safeSet(format, "vendor.mtk.vdec.disable-idle", 1, applied)
                        safeSet(format, "vendor.mtk.vdec.preload.frame.count", 1, applied)
                        safeSet(format, "vendor.mtk.vdec.buffer.fetch.timeout.ms", 4, applied)
                        safeSet(format, "vendor.mtk.vdec.bq.guard.interval.time", 4, applied)
                        safeSet(format, "vendor.mtk.vdec.input.max.queue.depth", 3, applied)
                        safeSet(format, "vendor.mtk.vdec.output.max.queue.depth", 3, applied)
                        safeSet(format, "vendor.mtk.vdec.vsync.adjust.enable", 0, applied)
                        safeSet(format, "vendor.mtk.vdec.nvop.skip", 1, applied)
                        safeSet(format, "vendor.mtk.vdec.skip.mode", 0, applied)
                        safeSet(format, "vendor.mtk.vdec.drop.nonref.frame", 0, applied)
                        safeSet(format, "vendor.mtk.vdec.frame-drop.policy", 0, applied)
                    }

                    decoderName.startsWith("omx.nvidia") || decoderName.startsWith("c2.nvidia") -> {
                        safeSet(format, "media.low-latency.enable", 1, applied)
                        safeSet(format, "vendor.low-latency.enable", 1, applied)
                        safeSet(format, "disable-output-reorder", 1, applied)
                        safeSet(format, "vendor.nvidia.disable-output-reorder", 1, applied)
                    }

                    hisiDecoderPrefixes.any { decoderName.startsWith(it) } -> {
                        safeSet(
                            format,
                            "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req",
                            1,
                            applied,
                        )
                        safeSet(
                            format,
                            "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-rdy",
                            -1,
                            applied,
                        )
                    }

                    decoderName.startsWith("omx.exynos") || decoderName.startsWith("c2.exynos") -> {
                        safeSet(format, "vendor.rtc-ext-dec-low-latency.enable", 1, applied)
                    }

                    decoderName.startsWith("omx.amlogic") || decoderName.startsWith("c2.amlogic") -> {
                        safeSet(format, "vendor.low-latency.enable", 1, applied)
                    }

                    vendorLowLatencyPrefixes.any { decoderName.startsWith(it) } -> {
                        safeSet(format, "vendor.low-latency.enable", 1, applied)
                    }
                }
                return applied
            }
        },
        PRIORITY_ONLY {
            override fun apply(
                format: MediaFormat,
                decoderInfo: MediaCodecInfo,
                mimeType: String,
            ): List<String> {
                val applied = mutableListOf<String>()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    safeSet(format, MediaFormat.KEY_PRIORITY, 0, applied)
                    safeSet(format, MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt(), applied)
                }
                return applied
            }
        },
        PLAIN {
            override fun apply(
                format: MediaFormat,
                decoderInfo: MediaCodecInfo,
                mimeType: String,
            ): List<String> = emptyList()
        },
        ;

        abstract fun apply(
            format: MediaFormat,
            decoderInfo: MediaCodecInfo,
            mimeType: String,
        ): List<String>
    }
}
