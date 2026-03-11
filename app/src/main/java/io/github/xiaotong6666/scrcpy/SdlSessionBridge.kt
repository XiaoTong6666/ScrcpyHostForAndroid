package io.github.xiaotong6666.scrcpy

import java.nio.ByteBuffer

object SdlSessionBridge {
    external fun setSessionMetadata(endpoint: String, backendUrl: String)
    external fun submitDecodedFrame(frameBuffer: ByteBuffer, width: Int, height: Int, stride: Int)
    external fun clearVideoFrame()
    external fun setExternalVideoMode(enabled: Boolean)
}
