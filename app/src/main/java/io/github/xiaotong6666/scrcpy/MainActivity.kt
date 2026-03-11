package io.github.xiaotong6666.scrcpy

import android.os.Bundle
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xiaotong6666.scrcpy.R
import io.github.xiaotong6666.scrcpy.ui.theme.Canvas
import io.github.xiaotong6666.scrcpy.ui.theme.Ink
import io.github.xiaotong6666.scrcpy.ui.theme.ScrcpyandroidTheme
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
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
                    onLaunchRemote = { host, port, backendUrl ->
                        startActivity(
                            RemoteDisplayActivity.createIntent(
                                context = this,
                                host = host,
                                port = port,
                                backendUrl = backendUrl,
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
    onLaunchRemote: (host: String, port: Int, backendUrl: String) -> Unit = { _, _, _ -> },
) {
    val appContext = LocalContext.current.applicationContext
    var host by rememberSaveable { mutableStateOf("192.168.1.5") }
    var portText by rememberSaveable { mutableStateOf("5555") }
    var backendUrl by rememberSaveable { mutableStateOf("local://bridge") }
    var isConnecting by rememberSaveable { mutableStateOf(false) }
    var statusText by rememberSaveable { mutableStateOf(appContext.getString(R.string.waiting_for_connection)) }
    var errorText by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing,
        ) { innerPadding ->
            ConnectScreen(
                host = host,
                portText = portText,
                backendUrl = backendUrl,
                isConnecting = isConnecting,
                statusText = statusText,
                errorText = errorText,
                onHostChange = {
                    host = it
                    errorText = null
                },
                onPortChange = {
                    portText = it.filter(Char::isDigit).take(5)
                    errorText = null
                },
                onBackendUrlChange = {
                    backendUrl = it
                    errorText = null
                },
                onPresetSelected = { presetHost, presetPort ->
                    host = presetHost
                    portText = presetPort.toString()
                    errorText = null
                },
                onConnect = {
                    val port = portText.toIntOrNull()
                    if (host.isBlank() || port == null || port !in 1..65535) {
                        errorText = appContext.getString(R.string.invalid_ip_port)
                        return@ConnectScreen
                    }
                    if (backendUrl.isBlank()) {
                        errorText = appContext.getString(R.string.enter_backend_address)
                        return@ConnectScreen
                    }

                    isConnecting = true
                    statusText = appContext.getString(R.string.requesting_adb_connect, host, port)
                    errorText = null
                    scope.launch {
                        val trimmedHost = host.trim()
                        val trimmedBackendUrl = backendUrl.trim()
                        val result = BackendBridgeClient.requestAdbConnect(
                            context = appContext,
                            backendBaseUrl = trimmedBackendUrl,
                            host = trimmedHost,
                            port = port,
                        )
                        isConnecting = false
                        if (result.isSuccess) {
                            statusText = appContext.getString(R.string.adb_connected_opening_sdl)
                            onLaunchRemote(trimmedHost, port, trimmedBackendUrl)
                        } else {
                            statusText = appContext.getString(R.string.connection_failed)
                            errorText = result.message
                        }
                    }
                },
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

@Composable
private fun ConnectScreen(
    host: String,
    portText: String,
    backendUrl: String,
    isConnecting: Boolean,
    statusText: String,
    errorText: String?,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onBackendUrlChange: (String) -> Unit,
    onPresetSelected: (String, Int) -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.adb_entry),
                    fontSize = 20.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                )

                TextField(
                    value = host,
                    onValueChange = onHostChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.device_ip),
                    singleLine = true,
                )

                TextField(
                    value = portText,
                    onValueChange = onPortChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.adb_port),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )

                TextField(
                    value = backendUrl,
                    onValueChange = onBackendUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.adb_backend_address),
                    singleLine = true,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onPresetSelected("127.0.0.1", 33445) },
                        modifier = Modifier.padding(start = 12.dp),
                    ) { Text(stringResource(R.string.localhost)) }
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
                                text = errorText,
                                fontSize = 16.sp,
                                color = MiuixTheme.colorScheme.error,
                            )
                        }
                    }
                }

                Button(
                    onClick = onConnect,
                    enabled = !isConnecting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isConnecting) {
                        InfiniteProgressIndicator(
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                    Text(if (isConnecting) stringResource(R.string.connecting) else stringResource(R.string.connect_and_enter_remote))
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
