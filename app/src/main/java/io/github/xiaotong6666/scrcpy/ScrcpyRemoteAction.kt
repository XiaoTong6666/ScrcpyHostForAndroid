package io.github.xiaotong6666.scrcpy

import androidx.annotation.StringRes

enum class ScrcpyRemoteAction(
    @param:StringRes val labelRes: Int,
) {
    BACK(R.string.remote_action_back),
    HOME(R.string.remote_action_home),
    RECENTS(R.string.remote_action_recents),
    NOTIFICATIONS(R.string.remote_action_notifications),
    SETTINGS(R.string.remote_action_settings),
    ROTATE(R.string.remote_action_rotate),
    POWER(R.string.remote_action_power),
    SCREENSHOT(R.string.remote_action_screenshot),
    SEND_CLIPBOARD(R.string.remote_action_send_clipboard),
    RECEIVE_CLIPBOARD(R.string.remote_action_receive_clipboard),
}

object ScrcpyRemoteActionLayout {
    val primaryRow = listOf(
        ScrcpyRemoteAction.BACK,
        ScrcpyRemoteAction.HOME,
        ScrcpyRemoteAction.RECENTS,
        ScrcpyRemoteAction.NOTIFICATIONS,
        ScrcpyRemoteAction.SETTINGS,
    )

    val secondaryRow = listOf(
        ScrcpyRemoteAction.ROTATE,
        ScrcpyRemoteAction.POWER,
        ScrcpyRemoteAction.SCREENSHOT,
        ScrcpyRemoteAction.SEND_CLIPBOARD,
        ScrcpyRemoteAction.RECEIVE_CLIPBOARD,
    )
}
