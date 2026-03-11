package io.github.xiaotong6666.scrcpy.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = NightAccent,
    secondary = NightMint,
    tertiary = NightWarm,
    background = PureBlack,
    surface = PureBlack,
    surfaceVariant = PureBlack,
    onPrimary = PureBlack,
    onSecondary = PureBlack,
    onTertiary = PureBlack,
    onBackground = PureWhite,
    onSurface = PureWhite,
    onSurfaceVariant = PureWhite.copy(alpha = 0.82f),
    outline = PureWhite.copy(alpha = 0.64f),
    outlineVariant = PureWhite.copy(alpha = 0.4f),
)

private val LightColorScheme = lightColorScheme(
    primary = SlateBlue,
    secondary = TealSignal,
    tertiary = Ember,
    background = PureWhite,
    surface = PureWhite,
    surfaceVariant = PureWhite,
    onPrimary = PureWhite,
    onSecondary = PureWhite,
    onTertiary = PureWhite,
    onBackground = Ink,
    onSurface = Ink,
    onSurfaceVariant = Ink.copy(alpha = 0.82f),
    outline = Ink.copy(alpha = 0.5f),
    outlineVariant = Ink.copy(alpha = 0.24f),
)

@Composable
fun ScrcpyandroidTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) {
        top.yukonga.miuix.kmp.theme.darkColorScheme()
    } else {
        top.yukonga.miuix.kmp.theme.lightColorScheme()
    }

    top.yukonga.miuix.kmp.theme.MiuixTheme(
        colors = colorScheme,
        content = content,
    )
}
