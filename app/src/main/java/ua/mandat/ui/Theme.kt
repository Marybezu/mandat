package ua.mandat.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

// Calm colours; "error" is a warm amber, never red
private val Light = lightColorScheme(
    primary = Color(0xFF2F4A6D),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E2F2),
    onPrimaryContainer = Color(0xFF14243A),
    secondary = Color(0xFF5C6B5A),
    background = Color(0xFFFAF8F4),
    surface = Color(0xFFFAF8F4),
    surfaceVariant = Color(0xFFECE8E0),
    error = Color(0xFF8A5A12),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF6E3C4),
    onErrorContainer = Color(0xFF3B2600),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA9C4E8),
    onPrimary = Color(0xFF14243A),
    primaryContainer = Color(0xFF2F4A6D),
    onPrimaryContainer = Color(0xFFD6E2F2),
    secondary = Color(0xFFBCCBB8),
    background = Color(0xFF15181D),
    surface = Color(0xFF15181D),
    surfaceVariant = Color(0xFF2A2E35),
    error = Color(0xFFE8C07A),
    onError = Color(0xFF3B2600),
    errorContainer = Color(0xFF5A3E0E),
    onErrorContainer = Color(0xFFF6E3C4),
)

// Large, easy-to-read text
private val Large = Typography().run {
    copy(
        bodyLarge = bodyLarge.copy(fontSize = 19.sp, lineHeight = 27.sp),
        bodyMedium = bodyMedium.copy(fontSize = 17.sp, lineHeight = 24.sp),
        titleLarge = titleLarge.copy(fontSize = 24.sp),
        titleMedium = titleMedium.copy(fontSize = 19.sp),
        labelLarge = labelLarge.copy(fontSize = 17.sp),
        labelMedium = labelMedium.copy(fontSize = 14.sp),
    )
}

@Composable
fun MandatTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = Large,
        content = content,
    )
}
