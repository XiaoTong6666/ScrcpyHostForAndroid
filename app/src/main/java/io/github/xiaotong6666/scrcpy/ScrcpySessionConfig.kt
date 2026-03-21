package io.github.xiaotong6666.scrcpy

import org.json.JSONObject

data class ResolvedScrcpySessionConfig(
    val videoCodec: String,
    val videoCodecId: Int,
    val audioCodec: String,
    val audioCodecId: Int,
    val maxSize: Int,
    val maxFps: Int,
    val videoBitRate: Long,
    val audioBitRate: Int?,
    val wakeOnConnect: Boolean,
    val turnScreenOff: Boolean,
    val stayAwake: Boolean,
    val powerOffOnClose: Boolean,
    val showTouches: Boolean,
)

data class ScrcpySessionConfig(
    val videoCodec: String = VIDEO_CODEC_AUTO,
    val audioCodec: String = AUDIO_CODEC_OPUS,
    val maxSize: Int = 0,
    val maxFps: Int = 0,
    val videoBitRateMbps: Int = 0,
    val audioBitRateKbps: Int = 0,
    val wakeOnConnect: Boolean = true,
    val turnScreenOff: Boolean = false,
    val stayAwake: Boolean = false,
    val powerOffOnClose: Boolean = false,
    val showTouches: Boolean = false,
    val autoSyncClipboard: Boolean = false,
    val autoReconnect: Boolean = false,
    val autoReconnectMaxAttempts: Int = AUTO_RECONNECT_ATTEMPTS_DEFAULT,
    val autoReconnectDelaySeconds: Int = AUTO_RECONNECT_DELAY_SECONDS_DEFAULT,
) {
    fun toJsonObject(): JSONObject = JSONObject()
        .put("videoCodec", videoCodec)
        .put("audioCodec", audioCodec)
        .put("maxSize", maxSize)
        .put("maxFps", maxFps)
        .put("videoBitRateMbps", videoBitRateMbps)
        .put("audioBitRateKbps", audioBitRateKbps)
        .put("wakeOnConnect", wakeOnConnect)
        .put("turnScreenOff", turnScreenOff)
        .put("stayAwake", stayAwake)
        .put("powerOffOnClose", powerOffOnClose)
        .put("showTouches", showTouches)
        .put("autoSyncClipboard", autoSyncClipboard)
        .put("autoReconnect", autoReconnect)
        .put("autoReconnectMaxAttempts", autoReconnectMaxAttempts)
        .put("autoReconnectDelaySeconds", autoReconnectDelaySeconds)

    fun toJsonString(): String = toJsonObject().toString()

    fun resolve(videoDefaults: ScrcpyServerVideoOptions): ResolvedScrcpySessionConfig {
        val resolvedVideoCodec = if (videoCodec == VIDEO_CODEC_AUTO) videoDefaults.codecName else videoCodec
        val resolvedAudioCodec = normalizeAudioCodec(audioCodec)
        return ResolvedScrcpySessionConfig(
            videoCodec = resolvedVideoCodec,
            videoCodecId = when (resolvedVideoCodec) {
                VIDEO_CODEC_H264 -> ScrcpyVideoTuning.CODEC_ID_H264
                VIDEO_CODEC_H265 -> ScrcpyVideoTuning.CODEC_ID_H265
                else -> videoDefaults.codecId
            },
            audioCodec = resolvedAudioCodec,
            audioCodecId = when (resolvedAudioCodec) {
                AUDIO_CODEC_AAC -> ScrcpyAudioPlayer.AUDIO_CODEC_AAC
                AUDIO_CODEC_FLAC -> ScrcpyAudioPlayer.AUDIO_CODEC_FLAC
                AUDIO_CODEC_RAW -> ScrcpyAudioPlayer.AUDIO_CODEC_RAW
                else -> ScrcpyAudioPlayer.AUDIO_CODEC_OPUS
            },
            maxSize = maxSize.takeIf { it > 0 } ?: videoDefaults.maxSize,
            maxFps = maxFps.takeIf { it > 0 } ?: videoDefaults.maxFps,
            videoBitRate = videoBitRateMbps.takeIf { it > 0 }?.toLong()?.times(1_000_000L) ?: videoDefaults.bitRate,
            audioBitRate = audioBitRateKbps.takeIf { it > 0 }?.times(1_000),
            wakeOnConnect = wakeOnConnect,
            turnScreenOff = turnScreenOff,
            stayAwake = stayAwake,
            powerOffOnClose = powerOffOnClose,
            showTouches = showTouches,
        )
    }

    fun summary(audioEnabled: Boolean): String {
        val parts = mutableListOf<String>()
        parts += if (videoCodec == VIDEO_CODEC_AUTO) {
            "视频自动"
        } else {
            "视频 ${videoCodec.uppercase()}"
        }
        if (maxSize > 0) {
            parts += "${maxSize}px"
        }
        if (maxFps > 0) {
            parts += "${maxFps}fps"
        }
        if (videoBitRateMbps > 0) {
            parts += "${videoBitRateMbps}Mbps"
        }
        if (audioEnabled) {
            parts += "音频 ${audioCodec.uppercase()}"
            if (audioBitRateKbps > 0) {
                parts += "${audioBitRateKbps}kbps"
            }
        } else {
            parts += "无音频"
        }
        if (wakeOnConnect) {
            parts += "连接唤醒"
        }
        if (turnScreenOff) {
            parts += "连接熄屏"
        }
        if (powerOffOnClose) {
            parts += "退出熄屏"
        }
        if (showTouches) {
            parts += "显示触点"
        }
        if (autoSyncClipboard) {
            parts += "自动剪贴板"
        }
        if (autoReconnect) {
            parts += "自动重连 ${autoReconnectMaxAttempts}x/${autoReconnectDelaySeconds}s"
        }
        return parts.joinToString(" · ")
    }

    companion object {
        const val VIDEO_CODEC_AUTO = "auto"
        const val VIDEO_CODEC_H264 = "h264"
        const val VIDEO_CODEC_H265 = "h265"

        const val AUDIO_CODEC_OPUS = "opus"
        const val AUDIO_CODEC_AAC = "aac"
        const val AUDIO_CODEC_FLAC = "flac"
        const val AUDIO_CODEC_RAW = "raw"
        const val AUTO_RECONNECT_ATTEMPTS_DEFAULT = 3
        const val AUTO_RECONNECT_DELAY_SECONDS_DEFAULT = 2

        fun fromJsonString(raw: String?): ScrcpySessionConfig {
            val json = raw?.takeIf { it.isNotBlank() }?.let { text ->
                runCatching { JSONObject(text) }.getOrNull()
            }
            return fromJsonObject(json)
        }

        fun fromJsonObject(json: JSONObject?): ScrcpySessionConfig {
            if (json == null) {
                return ScrcpySessionConfig()
            }
            return ScrcpySessionConfig(
                videoCodec = normalizeVideoCodec(json.optString("videoCodec")),
                audioCodec = normalizeAudioCodec(json.optString("audioCodec")),
                maxSize = json.optInt("maxSize", 0).coerceAtLeast(0),
                maxFps = json.optInt("maxFps", 0).coerceAtLeast(0),
                videoBitRateMbps = json.optInt("videoBitRateMbps", 0).coerceAtLeast(0),
                audioBitRateKbps = json.optInt("audioBitRateKbps", 0).coerceAtLeast(0),
                wakeOnConnect = json.optBoolean("wakeOnConnect", true),
                turnScreenOff = json.optBoolean("turnScreenOff", false),
                stayAwake = json.optBoolean("stayAwake", false),
                powerOffOnClose = json.optBoolean("powerOffOnClose", false),
                showTouches = json.optBoolean("showTouches", false),
                autoSyncClipboard = json.optBoolean("autoSyncClipboard", false),
                autoReconnect = json.optBoolean("autoReconnect", false),
                autoReconnectMaxAttempts = json.optInt(
                    "autoReconnectMaxAttempts",
                    AUTO_RECONNECT_ATTEMPTS_DEFAULT,
                ).coerceIn(1, 10),
                autoReconnectDelaySeconds = json.optInt(
                    "autoReconnectDelaySeconds",
                    AUTO_RECONNECT_DELAY_SECONDS_DEFAULT,
                ).coerceIn(1, 60),
            )
        }

        private fun normalizeVideoCodec(raw: String?): String = when (raw?.trim()?.lowercase()) {
            VIDEO_CODEC_H264 -> VIDEO_CODEC_H264
            VIDEO_CODEC_H265 -> VIDEO_CODEC_H265
            else -> VIDEO_CODEC_AUTO
        }

        private fun normalizeAudioCodec(raw: String?): String = when (raw?.trim()?.lowercase()) {
            AUDIO_CODEC_AAC -> AUDIO_CODEC_AAC
            AUDIO_CODEC_FLAC -> AUDIO_CODEC_FLAC
            AUDIO_CODEC_RAW -> AUDIO_CODEC_RAW
            else -> AUDIO_CODEC_OPUS
        }
    }
}
