package io.github.xiaotong6666.scrcpy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

object LocalClipboardBridge {
    fun readPlainText(context: Context): String? {
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = clipboardManager.primaryClip ?: return null
        if (clip.itemCount <= 0) {
            return null
        }
        val text = clip.getItemAt(0).coerceToText(context)?.toString()?.trim().orEmpty()
        return text.ifBlank { null }
    }

    fun writePlainText(
        context: Context,
        text: String,
    ) {
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboardManager.setPrimaryClip(ClipData.newPlainText("scrcpy-remote", text))
    }
}
