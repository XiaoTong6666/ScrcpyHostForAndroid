package io.github.xiaotong6666.scrcpy

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xiaotong6666.scrcpy.ui.theme.ScrcpyandroidTheme
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ScrcpyandroidTheme {
                ScrcpyHostApp(
                    onLaunchRemote = { profile ->
                        startActivity(RemoteDisplayActivity.createIntent(this, profile))
                    },
                    onLaunchFloating = { profile ->
                        FloatingDisplayService.launch(this, profile)
                    },
                    onRequestOverlayPermission = {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName"),
                            ),
                        )
                    },
                )
            }
        }
    }
}

@Composable
fun ScrcpyHostApp(
    onLaunchRemote: (DeviceProfile) -> Unit = {},
    onLaunchFloating: (DeviceProfile) -> Unit = {},
    onRequestOverlayPermission: () -> Unit = {},
) {
    val localContext = LocalContext.current
    val appContext = localContext.applicationContext
    val scope = rememberCoroutineScope()
    val profiles = remember { mutableStateListOf<DeviceProfile>() }

    var editingProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    var host by rememberSaveable { mutableStateOf("192.168.1.5") }
    var portText by rememberSaveable { mutableStateOf("5555") }
    var backendUrl by rememberSaveable { mutableStateOf("local://bridge") }
    var enableAudio by rememberSaveable { mutableStateOf(true) }
    var profileName by rememberSaveable { mutableStateOf("") }

    var videoCodec by rememberSaveable { mutableStateOf(ScrcpySessionConfig.VIDEO_CODEC_AUTO) }
    var audioCodec by rememberSaveable { mutableStateOf(ScrcpySessionConfig.AUDIO_CODEC_OPUS) }
    var maxSizeText by rememberSaveable { mutableStateOf("") }
    var maxFpsText by rememberSaveable { mutableStateOf("") }
    var videoBitRateText by rememberSaveable { mutableStateOf("") }
    var audioBitRateText by rememberSaveable { mutableStateOf("") }
    var wakeOnConnect by rememberSaveable { mutableStateOf(true) }
    var turnScreenOff by rememberSaveable { mutableStateOf(false) }
    var stayAwake by rememberSaveable { mutableStateOf(false) }
    var powerOffOnClose by rememberSaveable { mutableStateOf(false) }
    var showTouches by rememberSaveable { mutableStateOf(false) }
    var autoSyncClipboard by rememberSaveable { mutableStateOf(false) }
    var autoReconnect by rememberSaveable { mutableStateOf(false) }
    var autoReconnectAttemptsText by rememberSaveable {
        mutableStateOf(ScrcpySessionConfig.AUTO_RECONNECT_ATTEMPTS_DEFAULT.toString())
    }
    var autoReconnectDelayText by rememberSaveable {
        mutableStateOf(ScrcpySessionConfig.AUTO_RECONNECT_DELAY_SECONDS_DEFAULT.toString())
    }
    var showAdvancedConfig by rememberSaveable { mutableStateOf(false) }

    var pairHost by rememberSaveable { mutableStateOf("") }
    var pairPortText by rememberSaveable { mutableStateOf("") }
    var pairCode by rememberSaveable { mutableStateOf("") }

    var isWorking by rememberSaveable { mutableStateOf(false) }
    var statusText by rememberSaveable { mutableStateOf(appContext.getString(R.string.waiting_for_connection)) }
    var errorText by rememberSaveable { mutableStateOf<String?>(null) }

    fun reloadProfiles() {
        profiles.clear()
        profiles.addAll(DeviceProfileStore.load(appContext))
    }

    fun populateFromProfile(profile: DeviceProfile) {
        editingProfileId = profile.id
        profileName = profile.name
        host = profile.host
        portText = profile.adbPort.toString()
        backendUrl = profile.backendUrl
        enableAudio = profile.audioEnabled
        videoCodec = profile.sessionConfig.videoCodec
        audioCodec = profile.sessionConfig.audioCodec
        maxSizeText = profile.sessionConfig.maxSize.takeIf { it > 0 }?.toString().orEmpty()
        maxFpsText = profile.sessionConfig.maxFps.takeIf { it > 0 }?.toString().orEmpty()
        videoBitRateText = profile.sessionConfig.videoBitRateMbps.takeIf { it > 0 }?.toString().orEmpty()
        audioBitRateText = profile.sessionConfig.audioBitRateKbps.takeIf { it > 0 }?.toString().orEmpty()
        wakeOnConnect = profile.sessionConfig.wakeOnConnect
        turnScreenOff = profile.sessionConfig.turnScreenOff
        stayAwake = profile.sessionConfig.stayAwake
        powerOffOnClose = profile.sessionConfig.powerOffOnClose
        showTouches = profile.sessionConfig.showTouches
        autoSyncClipboard = profile.sessionConfig.autoSyncClipboard
        autoReconnect = profile.sessionConfig.autoReconnect
        autoReconnectAttemptsText = profile.sessionConfig.autoReconnectMaxAttempts.toString()
        autoReconnectDelayText = profile.sessionConfig.autoReconnectDelaySeconds.toString()
        errorText = null
    }

    fun parseOptionalNumber(
        raw: String,
        label: String,
        maxValue: Int,
    ): Int? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) {
            return 0
        }
        val value = trimmed.toIntOrNull()
        if (value == null || value < 0 || value > maxValue) {
            errorText = appContext.getString(R.string.invalid_numeric_setting, label)
            return null
        }
        return value
    }

    fun parsePositiveNumberOrDefault(
        raw: String,
        label: String,
        maxValue: Int,
        defaultValue: Int,
    ): Int? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) {
            return defaultValue
        }
        val value = trimmed.toIntOrNull()
        if (value == null || value !in 1..maxValue) {
            errorText = appContext.getString(R.string.invalid_numeric_setting, label)
            return null
        }
        return value
    }

    fun buildSessionConfig(): ScrcpySessionConfig? {
        val parsedMaxSize = parseOptionalNumber(maxSizeText, appContext.getString(R.string.max_size_input_label), 8192) ?: return null
        val parsedMaxFps = parseOptionalNumber(maxFpsText, appContext.getString(R.string.max_fps_input_label), 240) ?: return null
        val parsedVideoBitRate = parseOptionalNumber(videoBitRateText, appContext.getString(R.string.video_bitrate_input_label), 200) ?: return null
        val parsedAudioBitRate = parseOptionalNumber(audioBitRateText, appContext.getString(R.string.audio_bitrate_input_label), 1024) ?: return null
        val parsedAutoReconnectAttempts = parsePositiveNumberOrDefault(
            raw = autoReconnectAttemptsText,
            label = appContext.getString(R.string.auto_reconnect_attempts_label),
            maxValue = 10,
            defaultValue = ScrcpySessionConfig.AUTO_RECONNECT_ATTEMPTS_DEFAULT,
        ) ?: return null
        val parsedAutoReconnectDelay = parsePositiveNumberOrDefault(
            raw = autoReconnectDelayText,
            label = appContext.getString(R.string.auto_reconnect_delay_label),
            maxValue = 60,
            defaultValue = ScrcpySessionConfig.AUTO_RECONNECT_DELAY_SECONDS_DEFAULT,
        ) ?: return null
        return ScrcpySessionConfig(
            videoCodec = videoCodec,
            audioCodec = audioCodec,
            maxSize = parsedMaxSize,
            maxFps = parsedMaxFps,
            videoBitRateMbps = parsedVideoBitRate,
            audioBitRateKbps = parsedAudioBitRate,
            wakeOnConnect = wakeOnConnect,
            turnScreenOff = turnScreenOff,
            stayAwake = stayAwake,
            powerOffOnClose = powerOffOnClose,
            showTouches = showTouches,
            autoSyncClipboard = autoSyncClipboard,
            autoReconnect = autoReconnect,
            autoReconnectMaxAttempts = parsedAutoReconnectAttempts,
            autoReconnectDelaySeconds = parsedAutoReconnectDelay,
        )
    }

    fun buildProfile(): DeviceProfile? {
        val targetHost = host.trim()
        val targetPort = portText.toIntOrNull()
        if (targetHost.isBlank() || targetPort == null || targetPort !in 1..65535) {
            errorText = appContext.getString(R.string.invalid_ip_port)
            return null
        }
        if (backendUrl.isBlank()) {
            errorText = appContext.getString(R.string.enter_backend_address)
            return null
        }
        val config = buildSessionConfig() ?: return null
        val normalizedName = profileName.trim().ifBlank { "$targetHost:$targetPort" }
        return if (editingProfileId != null) {
            DeviceProfile(
                id = editingProfileId.orEmpty(),
                name = normalizedName,
                host = targetHost,
                adbPort = targetPort,
                backendUrl = backendUrl.trim(),
                audioEnabled = enableAudio,
                sessionConfig = config,
            )
        } else {
            DeviceProfile(
                name = normalizedName,
                host = targetHost,
                adbPort = targetPort,
                backendUrl = backendUrl.trim(),
                audioEnabled = enableAudio,
                sessionConfig = config,
            )
        }
    }

    suspend fun runConnect(
        profile: DeviceProfile,
        successMessage: String,
        onConnected: (DeviceProfile) -> Unit,
    ) {
        isWorking = true
        errorText = null
        statusText = appContext.getString(R.string.requesting_adb_connect, profile.host, profile.adbPort)
        val result = BackendBridgeClient.requestAdbConnect(
            context = appContext,
            backendBaseUrl = profile.backendUrl,
            host = profile.host,
            port = profile.adbPort,
        )
        isWorking = false
        if (result.isSuccess) {
            statusText = successMessage
            onConnected(profile)
        } else {
            statusText = appContext.getString(R.string.connection_failed)
            errorText = result.message
        }
    }

    fun ensureOverlayPermission(): Boolean {
        if (Settings.canDrawOverlays(localContext)) {
            return true
        }
        val message = appContext.getString(R.string.overlay_permission_required)
        statusText = message
        errorText = message
        onRequestOverlayPermission()
        return false
    }

    LaunchedEffect(Unit) {
        reloadProfiles()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing,
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding)
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource(R.string.description),
                    fontSize = 18.sp,
                    color = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.86f),
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surface),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.adb_entry),
                            fontSize = 20.sp,
                            color = MiuixTheme.colorScheme.onSurface,
                        )

                        TextField(
                            value = profileName,
                            onValueChange = { profileName = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = stringResource(R.string.device_name_optional),
                            singleLine = true,
                        )

                        TextField(
                            value = host,
                            onValueChange = {
                                host = it
                                errorText = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = stringResource(R.string.device_ip),
                            singleLine = true,
                        )

                        TextField(
                            value = portText,
                            onValueChange = {
                                portText = it.filter(Char::isDigit).take(5)
                                errorText = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = stringResource(R.string.adb_port),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )

                        TextField(
                            value = backendUrl,
                            onValueChange = {
                                backendUrl = it
                                errorText = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = stringResource(R.string.adb_backend_address),
                            singleLine = true,
                        )

                        SettingSwitchRow(
                            title = stringResource(R.string.enable_audio),
                            summary = stringResource(R.string.enable_audio_hint),
                            checked = enableAudio,
                            onCheckedChange = { enableAudio = it },
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    host = "127.0.0.1"
                                    portText = "33445"
                                    errorText = null
                                },
                                modifier = Modifier.padding(start = 12.dp),
                            ) {
                                Text(stringResource(R.string.localhost))
                            }
                            Button(
                                onClick = {
                                    val profile = buildProfile() ?: return@Button
                                    DeviceProfileStore.upsert(appContext, profile)
                                    editingProfileId = profile.id
                                    reloadProfiles()
                                    statusText = appContext.getString(R.string.device_saved, profile.name)
                                    errorText = null
                                },
                            ) {
                                Text(stringResource(R.string.save_device))
                            }
                        }

                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            color = MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.current_status),
                                    fontSize = 14.sp,
                                    color = MiuixTheme.colorScheme.secondary,
                                )
                                Text(
                                    text = statusText,
                                    fontSize = 18.sp,
                                    color = MiuixTheme.colorScheme.onSurface,
                                )
                                if (errorText != null) {
                                    Text(
                                        text = errorText.orEmpty(),
                                        fontSize = 16.sp,
                                        color = MiuixTheme.colorScheme.error,
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Button(
                                onClick = {
                                    val profile = buildProfile() ?: return@Button
                                    scope.launch {
                                        runConnect(
                                            profile = profile,
                                            successMessage = appContext.getString(R.string.adb_connected_opening_sdl),
                                            onConnected = onLaunchRemote,
                                        )
                                    }
                                },
                                enabled = !isWorking,
                                modifier = Modifier.weight(1f),
                            ) {
                                if (isWorking) {
                                    InfiniteProgressIndicator(modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(10.dp))
                                }
                                Text(
                                    if (isWorking) {
                                        stringResource(R.string.connecting)
                                    } else {
                                        stringResource(R.string.connect_and_enter_remote)
                                    },
                                )
                            }
                            Button(
                                onClick = {
                                    if (!ensureOverlayPermission()) {
                                        return@Button
                                    }
                                    val profile = buildProfile() ?: return@Button
                                    scope.launch {
                                        runConnect(
                                            profile = profile,
                                            successMessage = appContext.getString(R.string.adb_connected_opening_floating),
                                            onConnected = onLaunchFloating,
                                        )
                                    }
                                },
                                enabled = !isWorking,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.connect_and_open_floating))
                            }
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surface),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.advanced_config),
                                    fontSize = 20.sp,
                                    color = MiuixTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = stringResource(R.string.advanced_config_summary),
                                    fontSize = 13.sp,
                                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Button(
                                onClick = { showAdvancedConfig = !showAdvancedConfig },
                            ) {
                                Text(
                                    stringResource(
                                        if (showAdvancedConfig) {
                                            R.string.hide_advanced_config
                                        } else {
                                            R.string.show_advanced_config
                                        },
                                    ),
                                )
                            }
                        }

                        if (showAdvancedConfig) {
                            CodecSelector(
                                title = stringResource(R.string.video_codec),
                                summary = stringResource(R.string.video_codec_hint),
                                selectedValue = videoCodec,
                                options = listOf(
                                    ScrcpySessionConfig.VIDEO_CODEC_AUTO to stringResource(R.string.codec_auto),
                                    ScrcpySessionConfig.VIDEO_CODEC_H264 to stringResource(R.string.codec_h264),
                                    ScrcpySessionConfig.VIDEO_CODEC_H265 to stringResource(R.string.codec_h265),
                                ),
                                onSelect = { videoCodec = it },
                            )

                            TextField(
                                value = maxSizeText,
                                onValueChange = {
                                    maxSizeText = it.filter(Char::isDigit).take(4)
                                    errorText = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = stringResource(R.string.max_size_input_label),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )

                            TextField(
                                value = maxFpsText,
                                onValueChange = {
                                    maxFpsText = it.filter(Char::isDigit).take(3)
                                    errorText = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = stringResource(R.string.max_fps_input_label),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )

                            TextField(
                                value = videoBitRateText,
                                onValueChange = {
                                    videoBitRateText = it.filter(Char::isDigit).take(3)
                                    errorText = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = stringResource(R.string.video_bitrate_input_label),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )

                            CodecSelector(
                                title = stringResource(R.string.audio_codec),
                                summary = stringResource(R.string.audio_codec_hint),
                                selectedValue = audioCodec,
                                options = listOf(
                                    ScrcpySessionConfig.AUDIO_CODEC_OPUS to stringResource(R.string.codec_opus),
                                    ScrcpySessionConfig.AUDIO_CODEC_AAC to stringResource(R.string.codec_aac),
                                    ScrcpySessionConfig.AUDIO_CODEC_FLAC to stringResource(R.string.codec_flac),
                                    ScrcpySessionConfig.AUDIO_CODEC_RAW to stringResource(R.string.codec_raw),
                                ),
                                onSelect = { audioCodec = it },
                            )

                            TextField(
                                value = audioBitRateText,
                                onValueChange = {
                                    audioBitRateText = it.filter(Char::isDigit).take(4)
                                    errorText = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = stringResource(R.string.audio_bitrate_input_label),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )

                            SettingSwitchRow(
                                title = stringResource(R.string.wake_on_connect),
                                summary = stringResource(R.string.wake_on_connect_hint),
                                checked = wakeOnConnect,
                                onCheckedChange = { wakeOnConnect = it },
                            )
                            SettingSwitchRow(
                                title = stringResource(R.string.turn_screen_off),
                                summary = stringResource(R.string.turn_screen_off_hint),
                                checked = turnScreenOff,
                                onCheckedChange = { turnScreenOff = it },
                            )
                            SettingSwitchRow(
                                title = stringResource(R.string.stay_awake),
                                summary = stringResource(R.string.stay_awake_hint),
                                checked = stayAwake,
                                onCheckedChange = { stayAwake = it },
                            )
                            SettingSwitchRow(
                                title = stringResource(R.string.power_off_on_close),
                                summary = stringResource(R.string.power_off_on_close_hint),
                                checked = powerOffOnClose,
                                onCheckedChange = { powerOffOnClose = it },
                            )
                            SettingSwitchRow(
                                title = stringResource(R.string.show_touches),
                                summary = stringResource(R.string.show_touches_hint),
                                checked = showTouches,
                                onCheckedChange = { showTouches = it },
                            )
                            SettingSwitchRow(
                                title = stringResource(R.string.auto_sync_clipboard),
                                summary = stringResource(R.string.auto_sync_clipboard_hint),
                                checked = autoSyncClipboard,
                                onCheckedChange = { autoSyncClipboard = it },
                            )
                            SettingSwitchRow(
                                title = stringResource(R.string.auto_reconnect),
                                summary = stringResource(R.string.auto_reconnect_hint),
                                checked = autoReconnect,
                                onCheckedChange = { autoReconnect = it },
                            )
                            if (autoReconnect) {
                                TextField(
                                    value = autoReconnectAttemptsText,
                                    onValueChange = {
                                        autoReconnectAttemptsText = it.filter(Char::isDigit).take(2)
                                        errorText = null
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = stringResource(R.string.auto_reconnect_attempts_label),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                )

                                TextField(
                                    value = autoReconnectDelayText,
                                    onValueChange = {
                                        autoReconnectDelayText = it.filter(Char::isDigit).take(2)
                                        errorText = null
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = stringResource(R.string.auto_reconnect_delay_label),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                )
                            }
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surface),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.wireless_debug_tools),
                            fontSize = 20.sp,
                            color = MiuixTheme.colorScheme.onSurface,
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !isWorking,
                                onClick = {
                                    scope.launch {
                                        isWorking = true
                                        errorText = null
                                        statusText = appContext.getString(R.string.discovering_connect_service)
                                        val result = BackendBridgeClient.discoverConnectService(appContext, backendUrl.trim())
                                        isWorking = false
                                        if (result.isSuccess && result.value != null) {
                                            host = result.value.host
                                            portText = result.value.port.toString()
                                            statusText = appContext.getString(
                                                R.string.discovery_result_connect,
                                                result.value.host,
                                                result.value.port,
                                            )
                                        } else {
                                            statusText = appContext.getString(R.string.discovery_failed)
                                            errorText = result.message
                                        }
                                    }
                                },
                            ) {
                                Text(stringResource(R.string.discover_connect_service))
                            }
                            Button(
                                enabled = !isWorking,
                                onClick = {
                                    scope.launch {
                                        isWorking = true
                                        errorText = null
                                        statusText = appContext.getString(R.string.discovering_pair_service)
                                        val result = BackendBridgeClient.discoverPairingService(appContext, backendUrl.trim())
                                        isWorking = false
                                        if (result.isSuccess && result.value != null) {
                                            pairHost = result.value.host
                                            pairPortText = result.value.port.toString()
                                            if (host.isBlank()) {
                                                host = result.value.host
                                            }
                                            statusText = appContext.getString(
                                                R.string.discovery_result_pair,
                                                result.value.host,
                                                result.value.port,
                                            )
                                        } else {
                                            statusText = appContext.getString(R.string.discovery_failed)
                                            errorText = result.message
                                        }
                                    }
                                },
                            ) {
                                Text(stringResource(R.string.discover_pair_service))
                            }
                        }

                        TextField(
                            value = pairHost,
                            onValueChange = { pairHost = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = stringResource(R.string.pair_host),
                            singleLine = true,
                        )
                        TextField(
                            value = pairPortText,
                            onValueChange = { pairPortText = it.filter(Char::isDigit).take(5) },
                            modifier = Modifier.fillMaxWidth(),
                            label = stringResource(R.string.pair_port),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        TextField(
                            value = pairCode,
                            onValueChange = { pairCode = it.filter(Char::isDigit).take(6) },
                            modifier = Modifier.fillMaxWidth(),
                            label = stringResource(R.string.pair_code),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )

                        Button(
                            enabled = !isWorking,
                            onClick = {
                                scope.launch {
                                    val pairPort = pairPortText.toIntOrNull()
                                    if (pairHost.isBlank() || pairPort == null || pairPort !in 1..65535 || pairCode.isBlank()) {
                                        errorText = appContext.getString(R.string.invalid_pair_input)
                                        return@launch
                                    }
                                    isWorking = true
                                    errorText = null
                                    statusText = appContext.getString(R.string.pairing_device, pairHost, pairPort)
                                    val result = BackendBridgeClient.pairAdb(
                                        context = appContext,
                                        backendBaseUrl = backendUrl.trim(),
                                        host = pairHost.trim(),
                                        port = pairPort,
                                        pairingCode = pairCode.trim(),
                                    )
                                    isWorking = false
                                    if (result.isSuccess) {
                                        statusText = result.message
                                        if (host.isBlank()) {
                                            host = pairHost.trim()
                                        }
                                    } else {
                                        statusText = appContext.getString(R.string.pair_failed)
                                        errorText = result.message
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.pair_device))
                        }
                    }
                }

                if (profiles.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surface),
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.saved_devices),
                                fontSize = 20.sp,
                                color = MiuixTheme.colorScheme.onSurface,
                            )

                            profiles.forEach { profile ->
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(18.dp),
                                    color = MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(
                                            text = profile.name,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MiuixTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            text = "${profile.host}:${profile.adbPort}  ·  ${profile.backendUrl}",
                                            fontSize = 14.sp,
                                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                                        )
                                        Text(
                                            text = profile.sessionConfig.summary(profile.audioEnabled),
                                            fontSize = 13.sp,
                                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(
                                                enabled = !isWorking,
                                                onClick = { populateFromProfile(profile) },
                                            ) {
                                                Text(stringResource(R.string.use_profile))
                                            }
                                            Button(
                                                enabled = !isWorking,
                                                onClick = {
                                                    populateFromProfile(profile)
                                                    scope.launch {
                                                        runConnect(
                                                            profile = profile,
                                                            successMessage = appContext.getString(R.string.adb_connected_opening_sdl),
                                                            onConnected = onLaunchRemote,
                                                        )
                                                    }
                                                },
                                            ) {
                                                Text(stringResource(R.string.connect_profile))
                                            }
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(
                                                enabled = !isWorking,
                                                onClick = {
                                                    if (!ensureOverlayPermission()) {
                                                        return@Button
                                                    }
                                                    populateFromProfile(profile)
                                                    scope.launch {
                                                        runConnect(
                                                            profile = profile,
                                                            successMessage = appContext.getString(R.string.adb_connected_opening_floating),
                                                            onConnected = onLaunchFloating,
                                                        )
                                                    }
                                                },
                                            ) {
                                                Text(stringResource(R.string.open_profile_floating))
                                            }
                                            Button(
                                                enabled = !isWorking,
                                                onClick = {
                                                    DeviceProfileStore.delete(appContext, profile.id)
                                                    if (editingProfileId == profile.id) {
                                                        editingProfileId = null
                                                    }
                                                    reloadProfiles()
                                                    statusText = appContext.getString(R.string.device_deleted, profile.name)
                                                },
                                            ) {
                                                Text(stringResource(R.string.delete_profile))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                fontSize = 17.sp,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = summary,
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun CodecSelector(
    title: String,
    summary: String,
    selectedValue: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            fontSize = 17.sp,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Text(
            text = summary,
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.72f),
        )
        options.chunked(2).forEach { rowOptions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowOptions.forEach { (value, label) ->
                    Button(
                        onClick = { onSelect(value) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (selectedValue == value) "[$label]" else label)
                    }
                }
                if (rowOptions.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ScrcpyHostPreview() {
    ScrcpyandroidTheme {
        ScrcpyHostApp()
    }
}
