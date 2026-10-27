package ua.mandat.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.mandat.data.MatterType

// Blue and yellow of the Ukrainian flag as accents; calm neutrals around them.
// "error" (needs attention) is a burnt orange, distinct from the yellow and never red.
val FlagBlue = Color(0xFF0057B7)
val FlagYellow = Color(0xFFFFD700)

private val Light = lightColorScheme(
    primary = FlagBlue,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E6F8),
    onPrimaryContainer = Color(0xFF00214A),
    secondary = Color(0xFF6B6A64),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = FlagYellow,
    onTertiary = Color(0xFF2E2500),
    tertiaryContainer = Color(0xFFFFF3B0),
    onTertiaryContainer = Color(0xFF3A2E00),
    background = Color(0xFFF7F6F2),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF1EFE8),
    onSurfaceVariant = Color(0xFF5F5E5A),
    outline = Color(0xFFD3D1C7),
    outlineVariant = Color(0xFFE6E4DC),
    error = Color(0xFF9A4A00),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFBE3CF),
    onErrorContainer = Color(0xFF5C2C00),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8EBBF2),
    onPrimary = Color(0xFF002C5E),
    primaryContainer = Color(0xFF00428C),
    onPrimaryContainer = Color(0xFFD6E6F8),
    secondary = Color(0xFFB4B2A9),
    onSecondary = Color(0xFF2C2C2A),
    tertiary = FlagYellow,
    onTertiary = Color(0xFF2E2500),
    tertiaryContainer = Color(0xFF4A3D00),
    onTertiaryContainer = Color(0xFFFFF3B0),
    background = Color(0xFF121418),
    surface = Color(0xFF1B1E23),
    surfaceVariant = Color(0xFF262A30),
    onSurfaceVariant = Color(0xFFB4B2A9),
    outline = Color(0xFF44474D),
    outlineVariant = Color(0xFF30343A),
    error = Color(0xFFF2A65A),
    onError = Color(0xFF3A1C00),
    errorContainer = Color(0xFF4A2A0B),
    onErrorContainer = Color(0xFFFBE3CF),
)

/**
 * Only three text sizes in the whole app, so nothing "jumps": 22 (screen titles), 16 (text and
 * card titles, card titles in medium weight) and 13 (labels, meta, section headers).
 */
private val Calm = Typography().run {
    val big = titleLarge.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium)
    val text = bodyLarge.copy(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Normal)
    val textStrong = text.copy(fontWeight = FontWeight.Medium)
    val small = bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal)
    copy(
        headlineSmall = big, titleLarge = big,
        titleMedium = textStrong, titleSmall = textStrong,
        bodyLarge = text, bodyMedium = text,
        bodySmall = small, labelLarge = small.copy(fontWeight = FontWeight.Medium),
        labelMedium = small, labelSmall = small,
    )
}

@Composable
fun MandatTheme(dynamic: Boolean = false, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = androidx.compose.ui.platform.LocalContext.current
    val base = when {
        // Material You: colours from the wallpaper (minSdk 31, so always available)
        dynamic && dark -> androidx.compose.material3.dynamicDarkColorScheme(context)
        dynamic -> androidx.compose.material3.dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    // Whatever the accents, "needs attention" stays our calm orange, never red
    val ours = if (dark) Dark else Light
    val scheme = base.copy(
        error = ours.error, onError = ours.onError,
        errorContainer = ours.errorContainer, onErrorContainer = ours.onErrorContainer,
    )
    MaterialTheme(colorScheme = scheme, typography = Calm, content = content)
}

/** Spacing used everywhere, so screens line up. */
object Space {
    // One density for the whole app: compact, so more fits on the screen without crowding
    val screen = 12.dp
    val card = 10.dp
    val gap = 5.dp
    val section = 14.dp
}

/** A calm colour pair (background, text) for a matter type's tag; meaning, not decoration. */
data class Tint(val bg: Color, val fg: Color)

@Composable
@ReadOnlyComposable
fun MatterType.tint(): Tint {
    val dark = isSystemInDarkTheme()
    fun t(light: Long, lightFg: Long, darkBg: Long, darkFg: Long) =
        if (dark) Tint(Color(darkBg), Color(darkFg)) else Tint(Color(light), Color(lightFg))
    return when (this) {
        MatterType.CAMPAIGN -> t(0xFFFAECE7, 0xFF712B13, 0xFF4A1B0C, 0xFFF5C4B3)
        MatterType.BILL -> t(0xFFEEEDFE, 0xFF3C3489, 0xFF26215C, 0xFFCECBF6)
        MatterType.INVESTIGATION -> t(0xFFE1F5EE, 0xFF085041, 0xFF04342C, 0xFF9FE1CB)
        MatterType.COURT -> t(0xFFF1EFE8, 0xFF444441, 0xFF2C2C2A, 0xFFD3D1C7)
        MatterType.DISTRICT -> t(0xFFEAF3DE, 0xFF27500A, 0xFF173404, 0xFFC0DD97)
        MatterType.INTERNATIONAL -> t(0xFFE6F1FB, 0xFF0C447C, 0xFF042C53, 0xFFB5D4F4)
        MatterType.WORKING_GROUP -> t(0xFFEEEDFE, 0xFF3C3489, 0xFF26215C, 0xFFCECBF6)
        MatterType.INQUIRY -> t(0xFFFBEAF0, 0xFF72243E, 0xFF4B1528, 0xFFF4C0D1)
        MatterType.PUBLIC_COMMS -> t(0xFFFAEEDA, 0xFF633806, 0xFF412402, 0xFFFAC775)
        MatterType.AID -> t(0xFFFAECE7, 0xFF712B13, 0xFF4A1B0C, 0xFFF5C4B3)
        MatterType.OTHER -> t(0xFFF1EFE8, 0xFF444441, 0xFF2C2C2A, 0xFFD3D1C7)
    }
}
