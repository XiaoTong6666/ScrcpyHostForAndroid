package io.github.xiaotong6666.scrcpy

import android.content.ClipboardManager
import android.content.Context

class ClipboardSyncSession(
    private val context: Context,
    private val onStatus: (String) -> Unit = {},
    private val onError: (String) -> Unit = {},
) {
    private val clipboardManager by lazy {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }

    private var controlClient: ScrcpyControlClient? = null
    private var automaticSyncEnabled = false
    private var suppressLocalClipboardText: String? = null
    private var lastLocalClipboardText: String? = null

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (!automaticSyncEnabled) {
            return@OnPrimaryClipChangedListener
        }
        val client = controlClient ?: return@OnPrimaryClipChangedListener
        val text = LocalClipboardBridge.readPlainText(context) ?: return@OnPrimaryClipChangedListener
        if (text == suppressLocalClipboardText) {
            suppressLocalClipboardText = null
            lastLocalClipboardText = text
            return@OnPrimaryClipChangedListener
        }
        if (text == lastLocalClipboardText) {
            return@OnPrimaryClipChangedListener
        }
        if (client.sendClipboard(text, paste = false)) {
            lastLocalClipboardText = text
            onStatus(context.getString(R.string.local_clipboard_synced_remote))
        } else {
            onError(context.getString(R.string.remote_clipboard_send_failed))
        }
    }

    fun attach(
        client: ScrcpyControlClient,
        automaticSyncEnabled: Boolean,
    ) {
        detach()
        controlClient = client
        this.automaticSyncEnabled = automaticSyncEnabled
        if (automaticSyncEnabled) {
            clipboardManager?.addPrimaryClipChangedListener(clipboardListener)
            if (!client.requestClipboard()) {
                onError(context.getString(R.string.remote_clipboard_request_failed))
            }
        }
    }

    fun detach() {
        clipboardManager?.removePrimaryClipChangedListener(clipboardListener)
        controlClient = null
        automaticSyncEnabled = false
    }

    fun onRemoteClipboardText(text: String) {
        val normalized = text.trim()
        if (normalized.isBlank()) {
            return
        }
        val localText = LocalClipboardBridge.readPlainText(context)
        if (localText == normalized) {
            suppressLocalClipboardText = null
            return
        }
        suppressLocalClipboardText = normalized
        LocalClipboardBridge.writePlainText(context, normalized)
        onStatus(context.getString(R.string.remote_clipboard_synced))
    }
}
