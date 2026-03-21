package io.github.xiaotong6666.scrcpy

import java.net.Socket

data class ScrcpySocketBundle(
    val videoSocket: Socket,
    val audioSocket: Socket?,
    val controlSocket: Socket?,
) {
    fun closeQuietly() {
        runCatching { videoSocket.close() }
        runCatching { audioSocket?.close() }
        runCatching { controlSocket?.close() }
    }
}
