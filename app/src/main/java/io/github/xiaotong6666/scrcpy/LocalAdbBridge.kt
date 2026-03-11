package io.github.xiaotong6666.scrcpy

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.random.Random

private const val LOCAL_SCRCPY_SERVER_DEST = "/data/local/tmp/scrcpy-server.jar"
private const val LOCAL_ADB_BRIDGE_TAG = "LocalAdbBridge"

object LocalAdbBridge {
    private val bridgeLock = Mutex()
    private var activeSession: LocalScrcpySession? = null

    suspend fun requestAdbConnect(
        context: Context,
        host: String,
        port: Int,
    ): BridgeCallResult<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            Log.i(LOCAL_ADB_BRIDGE_TAG, "requestAdbConnect(host=$host, port=$port)")
            val runtime = ensureRuntimeFiles(context)
            val target = "$host:$port"
            val startServerResult = runtime.ensureServer()
            if (startServerResult.exitCode != 0) {
                return@runCatching BridgeCallResult(
                    isSuccess = false,
                    message = startServerResult.output.ifBlank { "adb start-server failed" },
                )
            }
            val connectResult = runtime.runAdb("connect", target)
            val output = connectResult.output.lowercase()
            val ok = connectResult.exitCode == 0 &&
                (output.contains("connected to") || output.contains("already connected to"))
            if (!ok) {
                return@runCatching BridgeCallResult(
                    isSuccess = false,
                    message = connectResult.output.ifBlank { "adb connect failed: $target" },
                )
            }
            BridgeCallResult(
                isSuccess = true,
                value = Unit,
                message = connectResult.output.ifBlank { "connected to $target" },
            )
        }.getOrElse { error ->
            Log.e(LOCAL_ADB_BRIDGE_TAG, "requestAdbConnect failed", error)
            BridgeCallResult(
                isSuccess = false,
                message = error.message ?: context.getString(R.string.local_adb_connection_failed),
            )
        }
    }

    suspend fun startSession(
        context: Context,
        host: String,
        port: Int,
        sessionMode: ScrcpySessionMode,
    ): BridgeCallResult<ScrcpySessionInfo> = withContext(Dispatchers.IO) {
        bridgeLock.withLock {
            runCatching {
                Log.i(
                    LOCAL_ADB_BRIDGE_TAG,
                    "startSession(host=$host, port=$port, mode=${sessionMode.wireValue})",
                )
                stopSessionLocked()
                val runtime = ensureRuntimeFiles(context)
                val target = "$host:$port"
                val startServerResult = runtime.ensureServer()
                if (startServerResult.exitCode != 0) {
                    return@runCatching BridgeCallResult(
                        isSuccess = false,
                        message = startServerResult.output.ifBlank { "adb start-server failed" },
                    )
                }

                val connectResult = runtime.runAdb("connect", target)
                val output = connectResult.output.lowercase()
                val connected = connectResult.exitCode == 0 &&
                    (output.contains("connected to") || output.contains("already connected to"))
                if (!connected) {
                    return@runCatching BridgeCallResult(
                        isSuccess = false,
                        message = connectResult.output.ifBlank { "adb connect failed: $target" },
                    )
                }

                val waitResult = runtime.runAdb("-s", target, "wait-for-device")
                if (waitResult.exitCode != 0) {
                    return@runCatching BridgeCallResult(
                        isSuccess = false,
                        message = waitResult.output.ifBlank { "adb wait-for-device failed: $target" },
                    )
                }

                val pushResult = runtime.runAdb(
                    "-s",
                    target,
                    "push",
                    runtime.scrcpyServer.absolutePath,
                    LOCAL_SCRCPY_SERVER_DEST,
                )
                if (pushResult.exitCode != 0) {
                    return@runCatching BridgeCallResult(
                        isSuccess = false,
                        message = pushResult.output.ifBlank { "adb push scrcpy-server failed" },
                    )
                }

                val scid = Random.nextInt(Int.MAX_VALUE)
                val socketName = "scrcpy_${String.format("%08x", scid)}"
                val adbLocalPort = allocateLocalTcpPort()
                val streamPort = adbLocalPort
                val controlEnabled = sessionMode.controlEnabled
                val controlPort = if (controlEnabled) adbLocalPort else 0
                val videoOptions = ScrcpyVideoTuning.chooseServerVideoOptions(context)

                runtime.runAdb("-s", target, "forward", "--remove", "tcp:$adbLocalPort")
                val forwardVideoResult = runtime.runAdb(
                    "-s",
                    target,
                    "forward",
                    "tcp:$adbLocalPort",
                    "localabstract:$socketName",
                )
                if (forwardVideoResult.exitCode != 0) {
                    return@runCatching BridgeCallResult(
                        isSuccess = false,
                        message = forwardVideoResult.output.ifBlank { "adb video forward failed" },
                    )
                }

                val serverArgs = listOf(
                    BuildConfig.SCRCPY_SERVER_VERSION,
                    "scid=${String.format("%08x", scid)}",
                    "log_level=debug",
                    "video=true",
                    "audio=false",
                    "control=$controlEnabled",
                    "tunnel_forward=true",
                    "cleanup=true",
                    "send_device_meta=false",
                    "send_dummy_byte=false",
                    "send_codec_meta=true",
                    "send_frame_meta=true",
                    "video_codec=${videoOptions.codecName}",
                    "max_fps=${videoOptions.maxFps}",
                    "max_size=${videoOptions.maxSize}",
                    "video_bit_rate=${videoOptions.bitRate}",
                )
                val remoteCmd = buildString {
                    append("CLASSPATH=")
                    append(LOCAL_SCRCPY_SERVER_DEST)
                    append(" app_process / com.genymobile.scrcpy.Server ")
                    append(serverArgs.joinToString(" "))
                }

                val process = ProcessBuilder(
                    runtime.adb.absolutePath,
                    "-s",
                    target,
                    "shell",
                    remoteCmd,
                )
                    .let {
                        runtime.configureAdbEnvironment(it.environment())
                        it
                    }
                    .redirectErrorStream(true)
                    .start()
                Log.i(LOCAL_ADB_BRIDGE_TAG, "scrcpy-server shell spawned for $target")

                val outputBuffer = StringBuilder()
                val outputThread = thread(
                    name = "local-scrcpy-server-output",
                    isDaemon = true,
                ) {
                    try {
                        process.inputStream.bufferedReader().use { reader ->
                            while (true) {
                                val line = reader.readLine() ?: break
                                Log.i(LOCAL_ADB_BRIDGE_TAG, "[scrcpy-server] $line")
                                synchronized(outputBuffer) {
                                    if (outputBuffer.isNotEmpty()) outputBuffer.append('\n')
                                    outputBuffer.append(line)
                                }
                            }
                        }
                    } catch (_: InterruptedIOException) {
                        Log.i(LOCAL_ADB_BRIDGE_TAG, "scrcpy-server output reader interrupted")
                    } catch (_: IOException) {
                        // Happens when process/socket is closed during teardown.
                        Log.i(LOCAL_ADB_BRIDGE_TAG, "scrcpy-server output reader closed")
                    } catch (error: Exception) {
                        Log.e(LOCAL_ADB_BRIDGE_TAG, "scrcpy-server output reader crashed", error)
                    }
                }

                Thread.sleep(if (controlEnabled) 2200 else 1500)
                if (!process.isAlive) {
                    runtime.runAdb("-s", target, "forward", "--remove", "tcp:$adbLocalPort")
                    val message = synchronized(outputBuffer) { outputBuffer.toString() }
                    return@runCatching BridgeCallResult(
                        isSuccess = false,
                        message = message.ifBlank { "scrcpy-server exited immediately" },
                    )
                }
                Log.i(
                    LOCAL_ADB_BRIDGE_TAG,
                    "session ready target=$target streamPort=$streamPort controlPort=$controlPort " +
                        "mode=${sessionMode.wireValue} socketName=$socketName scid=${String.format("%08x", scid)} " +
                        "codec=${videoOptions.codecName} maxSize=${videoOptions.maxSize} " +
                        "maxFps=${videoOptions.maxFps} bitRate=${videoOptions.bitRate}",
                )

                activeSession = LocalScrcpySession(
                    runtime = runtime,
                    target = target,
                    adbLocalPort = adbLocalPort,
                    deviceSocketName = socketName,
                    process = process,
                    outputThread = outputThread,
                )

                BridgeCallResult(
                    isSuccess = true,
                    value = ScrcpySessionInfo(
                        target = target,
                        streamPort = streamPort,
                        controlPort = controlPort,
                        videoCodecId = videoOptions.codecId,
                        videoCodec = videoOptions.codecName,
                        sessionMode = sessionMode,
                    ),
                    message = "scrcpy session started for $target",
                )
            }.getOrElse { error ->
                Log.e(LOCAL_ADB_BRIDGE_TAG, "startSession failed", error)
                BridgeCallResult(
                    isSuccess = false,
                    message = error.message ?: context.getString(R.string.start_local_scrcpy_session_failed),
                )
            }
        }
    }

    suspend fun stopSession(context: Context? = null): BridgeCallResult<Unit> = withContext(Dispatchers.IO) {
        bridgeLock.withLock {
            runCatching {
                Log.i(LOCAL_ADB_BRIDGE_TAG, "stopSession()")
                stopSessionLocked()
                BridgeCallResult(
                    isSuccess = true,
                    value = Unit,
                    message = "scrcpy session stopped",
                )
            }.getOrElse { error ->
                Log.e(LOCAL_ADB_BRIDGE_TAG, "stopSession failed", error)
                BridgeCallResult(
                    isSuccess = false,
                    message = error.message ?: context?.getString(R.string.stop_local_scrcpy_session_failed) ?: "Stop local scrcpy session failed",
                )
            }
        }
    }

    private fun stopSessionLocked() {
        Log.i(LOCAL_ADB_BRIDGE_TAG, "stopSessionLocked()")
        activeSession?.stop()
        activeSession = null
    }

    private fun ensureRuntimeFiles(context: Context): LocalRuntimeFiles {
        val runtimeDir = File(context.filesDir, "runtime").apply { mkdirs() }
        val adbDir = File(runtimeDir, "adb").apply { mkdirs() }
        val scrcpyDir = File(runtimeDir, "scrcpy").apply { mkdirs() }

        val adbFile = File(adbDir, "adb")
        val scrcpyServerFile = File(scrcpyDir, "scrcpy-server.jar")

        if (!adbFile.isFile()) {
            val assetPath = selectAdbAssetPath(context)
            Log.i(LOCAL_ADB_BRIDGE_TAG, "extract embedded adb from assets/$assetPath")
            context.assets.open(assetPath).use { input ->
                adbFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            adbFile.setReadable(true, true)
            adbFile.setExecutable(true, true)
        }

        if (!scrcpyServerFile.isFile()) {
            Log.i(LOCAL_ADB_BRIDGE_TAG, "extract embedded scrcpy-server from assets/scrcpy/scrcpy-server")
            context.assets.open("scrcpy/scrcpy-server").use { input ->
                scrcpyServerFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            scrcpyServerFile.setReadable(true, true)
        }

        Log.i(
            LOCAL_ADB_BRIDGE_TAG,
            "runtime ready adb=${adbFile.absolutePath} server=${scrcpyServerFile.absolutePath}",
        )
        return LocalRuntimeFiles(runtimeDir = runtimeDir, adb = adbFile, scrcpyServer = scrcpyServerFile)
    }

    private fun selectAdbAssetPath(context: Context): String {
        val availableAssets = context.assets.list("termux-adb")?.toSet().orEmpty()
        val preferred = Build.SUPPORTED_ABIS.toList()
        val chosenAbi = preferred.firstOrNull { it in availableAssets }
            ?: if ("arm64-v8a" in availableAssets) {
                "arm64-v8a"
            } else if ("armeabi-v7a" in availableAssets) {
                "armeabi-v7a"
            } else {
                throw IOException("No embedded adb binary found in assets/termux-adb")
            }
        return "termux-adb/$chosenAbi/adb"
    }

    private fun allocateLocalTcpPort(): Int {
        ServerSocket(0).use { server ->
            return server.localPort
        }
    }
}

private data class LocalRuntimeFiles(
    val runtimeDir: File,
    val adb: File,
    val scrcpyServer: File,
) {
    private val adbServerSocketFile = File(runtimeDir, "adb-server.sock")

    fun ensureServer(): AdbExecResult {
        val firstTry = runAdb("start-server")
        if (firstTry.exitCode == 0) return firstTry

        Log.w(
            LOCAL_ADB_BRIDGE_TAG,
            "adb start-server failed once (exit=${firstTry.exitCode}), retry after socket cleanup",
        )
        runCatching { adbServerSocketFile.delete() }
        return runAdb("start-server")
    }

    fun runAdb(vararg args: String): AdbExecResult {
        val cmd = listOf(adb.absolutePath) + args.toList()
        val startedAt = SystemClock.elapsedRealtime()
        Log.i(LOCAL_ADB_BRIDGE_TAG, "exec: ${cmd.joinToString(" ")}")
        val process = ProcessBuilder(cmd)
            .apply { configureAdbEnvironment(environment()) }
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        val exitCode = process.waitFor()
        val durationMs = SystemClock.elapsedRealtime() - startedAt
        Log.i(
            LOCAL_ADB_BRIDGE_TAG,
            "exec done ($durationMs ms, exit=$exitCode): ${cmd.joinToString(" ")}\n$output",
        )
        return AdbExecResult(
            exitCode = exitCode,
            output = output,
        )
    }

    fun configureAdbEnvironment(env: MutableMap<String, String>) {
        val tmpDir = File(runtimeDir, "tmp").apply { mkdirs() }
        env["HOME"] = runtimeDir.absolutePath
        env["TMPDIR"] = tmpDir.absolutePath
        env["TERMUX_EXEC__PROC_SELF_EXE"] = adb.absolutePath
        env["ADB_SERVER_SOCKET"] = "localfilesystem:${adbServerSocketFile.absolutePath}"
        env["ADB_MDNS_OPENSCREEN"] = "0"
        env["ADB_MDNS_AUTO_CONNECT"] = ""
        env["ADB_LIBUSB"] = "0"
    }
}

private data class AdbExecResult(
    val exitCode: Int,
    val output: String,
)

private data class LocalScrcpySession(
    val runtime: LocalRuntimeFiles,
    val target: String,
    val adbLocalPort: Int,
    val deviceSocketName: String,
    val process: Process,
    val outputThread: Thread,
) {
    fun stop() {
        Log.i(
            LOCAL_ADB_BRIDGE_TAG,
            "LocalScrcpySession.stop(target=$target, adbForward=$adbLocalPort, socketName=$deviceSocketName)",
        )
        if (process.isAlive) {
            process.destroy()
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
        runCatching { outputThread.join(500) }
        runCatching { runtime.runAdb("-s", target, "forward", "--remove", "tcp:$adbLocalPort") }
    }
}

private class LocalTcpProxy(
    private val bindPort: Int,
    private val upstreamPort: Int,
) {
    private val running = AtomicBoolean(false)
    private val sockets = CopyOnWriteArrayList<Socket>()
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val server = ServerSocket(bindPort, 50)
        serverSocket = server
        Log.i(LOCAL_ADB_BRIDGE_TAG, "LocalTcpProxy.start(bindPort=$bindPort, upstreamPort=$upstreamPort)")
        acceptThread = thread(
            name = "local-proxy-$bindPort",
            isDaemon = true,
        ) {
            while (running.get()) {
                val client = runCatching { server.accept() }.getOrElse { break }
                Log.i(LOCAL_ADB_BRIDGE_TAG, "LocalTcpProxy.accept(bindPort=$bindPort)")
                sockets.add(client)
                thread(
                    name = "local-proxy-conn-$bindPort",
                    isDaemon = true,
                ) {
                    handleConnection(client)
                }
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        Log.i(LOCAL_ADB_BRIDGE_TAG, "LocalTcpProxy.stop(bindPort=$bindPort)")
        runCatching { serverSocket?.close() }
        sockets.forEach { socket ->
            runCatching { socket.close() }
        }
        sockets.clear()
        runCatching { acceptThread?.join(300) }
        serverSocket = null
        acceptThread = null
    }

    private fun handleConnection(client: Socket) {
        client.use { local ->
            val upstream = runCatching { Socket("127.0.0.1", upstreamPort) }.getOrElse {
                Log.w(
                    LOCAL_ADB_BRIDGE_TAG,
                    "LocalTcpProxy upstream connect failed bindPort=$bindPort upstreamPort=$upstreamPort: ${it.message}",
                )
                return
            }
            Log.i(LOCAL_ADB_BRIDGE_TAG, "LocalTcpProxy.upstream connected bindPort=$bindPort upstreamPort=$upstreamPort")
            sockets.add(upstream)
            upstream.use { remote ->
                val down = thread(isDaemon = true) { pipe(local, remote) }
                val up = thread(isDaemon = true) { pipe(remote, local) }
                down.join()
                up.join()
            }
            sockets.remove(upstream)
        }
        sockets.remove(client)
    }

    private fun pipe(source: Socket, target: Socket) {
        val buffer = ByteArray(64 * 1024)
        try {
            val input = source.getInputStream()
            val output = target.getOutputStream()
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                output.flush()
            }
        } catch (_: IOException) {
        } finally {
            runCatching { target.shutdownOutput() }
        }
    }
}
