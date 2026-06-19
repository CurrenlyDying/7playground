package com.tpuplayground.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val Cyan400 = Color(0xFF26C6DA)
val Cyan700 = Color(0xFF0097A7)
val Amber400 = Color(0xFFFFCA28)
val Red400 = Color(0xFFEF5350)
val Green400 = Color(0xFF66BB6A)
val Purple400 = Color(0xFFAB47BC)
val Blue400 = Color(0xFF42A5F5)
val Orange400 = Color(0xFFFFA726)
val Pink400 = Color(0xFFEC407A)
val Teal400 = Color(0xFF26A69A)

val ChartColors = listOf(
    Cyan400, Amber400, Red400, Green400, Purple400,
    Blue400, Orange400, Pink400, Teal400, Color(0xFF78909C)
)

val HeatMapColors = listOf(
    Color(0xFF1A237E),
    Color(0xFF283593),
    Color(0xFF1565C0),
    Color(0xFF0277BD),
    Color(0xFF00838F),
    Color(0xFF00695C),
    Color(0xFF2E7D32),
    Color(0xFF558B2F),
    Color(0xFF9E9D24),
    Color(0xFFF9A825),
    Color(0xFFFF8F00),
    Color(0xFFEF6C00),
    Color(0xFFD84315),
    Color(0xFFBF360C),
    Color(0xFFB71C1C)
)

private val DarkColorScheme = darkColorScheme(
    primary = Cyan400,
    secondary = Amber400,
    tertiary = Green400,
    background = Color(0xFF0D1117),
    surface = Color(0xFF161B22),
    surfaceVariant = Color(0xFF21262D),
    onBackground = Color(0xFFC9D1D9),
    onSurface = Color(0xFFC9D1D9),
    onSurfaceVariant = Color(0xFF8B949E),
    outline = Color(0xFF30363D)
)

@Composable
fun TPUPlaygroundTheme(content: @Composable () -> Unit) {
    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isSystemInDarkTheme()) {
        dynamicDarkColorScheme(LocalContext.current)
    } else {
        DarkColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
