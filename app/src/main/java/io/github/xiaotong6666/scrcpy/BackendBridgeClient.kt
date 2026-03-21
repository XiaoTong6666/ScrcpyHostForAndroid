package io.github.xiaotong6666.scrcpy

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class BridgeCallResult<T>(
    val isSuccess: Boolean,
    val value: T? = null,
    val message: String,
)

data class ScrcpySessionInfo(
    val target: String,
    val streamPort: Int,
    val audioPort: Int,
    val controlPort: Int,
    val videoCodecId: Int,
    val videoCodec: String,
    val audioCodecId: Int,
    val audioCodec: String,
    val audioEnabled: Boolean,
    val sessionMode: ScrcpySessionMode,
)

enum class ScrcpySessionMode(
    val wireValue: String,
    val controlEnabled: Boolean,
) {
    CONTROL_REWORK("control-rework", true),
    VIDEO_ONLY_FALLBACK("video-only-fallback", false),
    ;

    companion object {
        fun fromWireValue(value: String): ScrcpySessionMode = entries.firstOrNull { it.wireValue == value } ?: CONTROL_REWORK
    }
}

object BackendBridgeClient {
    private const val LOCAL_BRIDGE_SCHEME = "local://"
    private const val TAG = "BackendBridgeClient"

    suspend fun requestAdbConnect(
        context: Context,
        backendBaseUrl: String,
        host: String,
        port: Int,
    ): BridgeCallResult<Unit> {
        if (isLocalBridge(backendBaseUrl)) {
            Log.i(TAG, "requestAdbConnect -> local bridge: $host:$port")
            return LocalAdbBridge.requestAdbConnect(context, host, port)
        }
        Log.i(TAG, "requestAdbConnect -> http bridge: $backendBaseUrl, target=$host:$port")
        return postJson(
            context = context,
            backendBaseUrl = backendBaseUrl,
            path = "/api/adb/connect",
            body = JSONObject()
                .put("host", host)
                .put("port", port),
        ) { payload ->
            val ok = payload.optBoolean("ok", false)
            BridgeCallResult(
                isSuccess = ok,
                value = if (ok) Unit else null,
                message = payload.optString("message", context.getString(R.string.backend_no_status_returned)),
            )
        }
    }

    suspend fun pairAdb(
        context: Context,
        backendBaseUrl: String,
        host: String,
        port: Int,
        pairingCode: String,
    ): BridgeCallResult<Unit> {
        if (isLocalBridge(backendBaseUrl)) {
            Log.i(TAG, "pairAdb -> local bridge: $host:$port")
            return LocalAdbBridge.pair(context, host, port, pairingCode)
        }
        return BridgeCallResult(
            isSuccess = false,
            message = context.getString(R.string.backend_pair_not_supported),
        )
    }

    suspend fun discoverConnectService(
        context: Context,
        backendBaseUrl: String,
    ): BridgeCallResult<AdbMdnsService> {
        if (isLocalBridge(backendBaseUrl)) {
            Log.i(TAG, "discoverConnectService -> local bridge")
            return LocalAdbBridge.discoverConnectService(context)
        }
        return BridgeCallResult(
            isSuccess = false,
            message = context.getString(R.string.backend_discovery_not_supported),
        )
    }

    suspend fun discoverPairingService(
        context: Context,
        backendBaseUrl: String,
    ): BridgeCallResult<AdbMdnsService> {
        if (isLocalBridge(backendBaseUrl)) {
            Log.i(TAG, "discoverPairingService -> local bridge")
            return LocalAdbBridge.discoverPairingService(context)
        }
        return BridgeCallResult(
            isSuccess = false,
            message = context.getString(R.string.backend_discovery_not_supported),
        )
    }

    suspend fun startScrcpySession(
        context: Context,
        backendBaseUrl: String,
        host: String,
        port: Int,
        audioEnabled: Boolean = true,
        sessionConfig: ScrcpySessionConfig = ScrcpySessionConfig(),
        sessionMode: ScrcpySessionMode = ScrcpySessionMode.CONTROL_REWORK,
    ): BridgeCallResult<ScrcpySessionInfo> {
        if (isLocalBridge(backendBaseUrl)) {
            Log.i(TAG, "startScrcpySession -> local bridge: $host:$port mode=${sessionMode.wireValue}")
            return LocalAdbBridge.startSession(context, host, port, audioEnabled, sessionConfig, sessionMode)
        }
        val resolvedConfig = sessionConfig.resolve(ScrcpyVideoTuning.chooseServerVideoOptions(context))
        Log.i(
            TAG,
            "startScrcpySession -> http bridge: $backendBaseUrl, target=$host:$port, mode=${sessionMode.wireValue}",
        )
        return postJson(
            context = context,
            backendBaseUrl = backendBaseUrl,
            path = "/api/scrcpy/session/start",
            body = JSONObject()
                .put("host", host)
                .put("port", port)
                .put("audioEnabled", audioEnabled)
                .put("sessionMode", sessionMode.wireValue)
                .put("videoCodec", sessionConfig.videoCodec)
                .put("audioCodec", sessionConfig.audioCodec)
                .put("maxSize", sessionConfig.maxSize)
                .put("maxFps", sessionConfig.maxFps)
                .put("videoBitRate", resolvedConfig.videoBitRate)
                .put("audioBitRate", resolvedConfig.audioBitRate ?: 0)
                .put("wakeOnConnect", sessionConfig.wakeOnConnect)
                .put("turnScreenOff", sessionConfig.turnScreenOff)
                .put("stayAwake", sessionConfig.stayAwake)
                .put("powerOffOnClose", sessionConfig.powerOffOnClose)
                .put("showTouches", sessionConfig.showTouches)
                .put("autoReconnect", sessionConfig.autoReconnect)
                .put("autoReconnectMaxAttempts", sessionConfig.autoReconnectMaxAttempts)
                .put("autoReconnectDelaySeconds", sessionConfig.autoReconnectDelaySeconds),
        ) { payload ->
            val ok = payload.optBoolean("ok", false)
            val sessionInfo = if (ok) {
                val resolvedMode = ScrcpySessionMode.fromWireValue(
                    payload.optString("sessionMode", sessionMode.wireValue),
                )
                ScrcpySessionInfo(
                    target = payload.optString("target", "$host:$port"),
                    streamPort = payload.optInt("streamPort", 0),
                    audioPort = payload.optInt("audioPort", 0),
                    controlPort = payload.optInt("controlPort", 0),
                    videoCodecId = payload.optInt("videoCodecId", 0),
                    videoCodec = payload.optString("videoCodec", "unknown"),
                    audioCodecId = payload.optInt("audioCodecId", 0),
                    audioCodec = payload.optString("audioCodec", "unknown"),
                    audioEnabled = payload.optBoolean("audioEnabled", audioEnabled),
                    sessionMode = resolvedMode,
                )
            } else {
                null
            }
            val hasRequiredPorts = sessionInfo?.let {
                it.streamPort > 0 &&
                    (!it.audioEnabled || it.audioPort > 0) &&
                    (!it.sessionMode.controlEnabled || it.controlPort > 0)
            } ?: false
            BridgeCallResult(
                isSuccess = ok && hasRequiredPorts,
                value = sessionInfo?.takeIf { hasRequiredPorts },
                message = payload.optString("message", context.getString(R.string.backend_no_session_returned)),
            )
        }
    }

    suspend fun stopScrcpySession(
        context: Context,
        backendBaseUrl: String,
    ): BridgeCallResult<Unit> {
        if (isLocalBridge(backendBaseUrl)) {
            Log.i(TAG, "stopScrcpySession -> local bridge")
            return LocalAdbBridge.stopSession()
        }
        Log.i(TAG, "stopScrcpySession -> http bridge: $backendBaseUrl")
        return postJson(
            context = context,
            backendBaseUrl = backendBaseUrl,
            path = "/api/scrcpy/session/stop",
            body = JSONObject(),
        ) { payload ->
            val ok = payload.optBoolean("ok", false)
            BridgeCallResult(
                isSuccess = ok,
                value = if (ok) Unit else null,
                message = payload.optString("message", context.getString(R.string.backend_no_stop_result_returned)),
            )
        }
    }

    private suspend fun <T> postJson(
        context: Context,
        backendBaseUrl: String,
        path: String,
        body: JSONObject,
        mapper: (JSONObject) -> BridgeCallResult<T>,
    ): BridgeCallResult<T> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedBaseUrl = backendBaseUrl.trimEnd('/')
            val connection = URL("$normalizedBaseUrl$path").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 5_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")

            connection.outputStream.use { output ->
                output.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            val responseBody = (
                if (responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                )?.bufferedReader()?.use { it.readText() }.orEmpty()

            val payload = if (responseBody.isNotBlank()) JSONObject(responseBody) else JSONObject()
            mapper(payload)
        }.getOrElse { error ->
            Log.e(TAG, "postJson failed path=$path base=$backendBaseUrl", error)
            BridgeCallResult(
                isSuccess = false,
                message = error.message ?: context.getString(R.string.cannot_connect_adb_backend),
            )
        }
    }

    private fun isLocalBridge(backendBaseUrl: String): Boolean = backendBaseUrl.trim().startsWith(LOCAL_BRIDGE_SCHEME)
}
