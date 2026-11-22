package to.wim.player.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import to.wim.player.data.model.ThemeMode

// Fallback palette (dynamic color off), from the icon's middle gradient stop #D51007: the primary
// roles from Material's fidelity scheme (so the red stays red, as on Last.fm), the secondary,
// tertiary and surface roles from the neutral scheme of the same hue (near-white and near-black
// surfaces, one accent).
private val LightColors =
    lightColorScheme(
        primary = Color(0xFFA90100),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD51007),
        onPrimaryContainer = Color(0xFFFFE7E3),
        secondary = Color(0xFF665D5C),
        onSecondary = Color(0xFFFFF7F6),
        secondaryContainer = Color(0xFFEDE0DD),
        onSecondaryContainer = Color(0xFF58504E),
        tertiary = Color(0xFF795562),
        onTertiary = Color(0xFFFFF7F8),
        tertiaryContainer = Color(0xFFF9CBDB),
        onTertiaryContainer = Color(0xFF63414E),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF93000A),
        background = Color(0xFFFFF8F6),
        onBackground = Color(0xFF373130),
        surface = Color(0xFFFFF8F6),
        onSurface = Color(0xFF373130),
        surfaceVariant = Color(0xFFEBE0DE),
        onSurfaceVariant = Color(0xFF655D5C),
        outline = Color(0xFF817978),
        outlineVariant = Color(0xFFB9B0AE),
        inverseSurface = Color(0xFF100E0D),
        inverseOnSurface = Color(0xFFA19C9B),
        inversePrimary = Color(0xFFFFB4A8),
        surfaceDim = Color(0xFFE3D7D6),
        surfaceBright = Color(0xFFFFF8F6),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFFAF2F0),
        surfaceContainer = Color(0xFFF5ECEA),
        surfaceContainerHigh = Color(0xFFF0E6E4),
        surfaceContainerHighest = Color(0xFFEBE0DE),
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFFFFB4A8),
        onPrimary = Color(0xFF690100),
        primaryContainer = Color(0xFFD51007),
        onPrimaryContainer = Color(0xFFFFE7E3),
        secondary = Color(0xFFA79B9A),
        onSecondary = Color(0xFF251F1E),
        secondaryContainer = Color(0xFF413A38),
        onSecondaryContainer = Color(0xFFC9BDBB),
        tertiary = Color(0xFFFFDCE7),
        onTertiary = Color(0xFF6C4A57),
        tertiaryContainer = Color(0xFFF9CBDB),
        onTertiaryContainer = Color(0xFF63414E),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF100E0D),
        onBackground = Color(0xFFEEE3E1),
        surface = Color(0xFF100E0D),
        onSurface = Color(0xFFEEE3E1),
        surfaceVariant = Color(0xFF2A2524),
        onSurfaceVariant = Color(0xFFB2A9A7),
        outline = Color(0xFF7C7472),
        outlineVariant = Color(0xFF4D4645),
        inverseSurface = Color(0xFFFFF8F6),
        inverseOnSurface = Color(0xFF585453),
        inversePrimary = Color(0xFFC00100),
        surfaceDim = Color(0xFF100E0D),
        surfaceBright = Color(0xFF312B2A),
        surfaceContainerLowest = Color(0xFF000000),
        surfaceContainerLow = Color(0xFF161312),
        surfaceContainer = Color(0xFF1C1918),
        surfaceContainerHigh = Color(0xFF231F1E),
        surfaceContainerHighest = Color(0xFF2A2524),
    )

/** True when the phone's theme (or the user's choice) is dark. */
@Composable
fun isAppInDarkTheme(themeMode: ThemeMode): Boolean =
    when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

@Composable
fun AppTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = isAppInDarkTheme(themeMode)
    val context = LocalContext.current
    val colorScheme =
        when {
            dynamicColor && dark -> dynamicDarkColorScheme(context)
            dynamicColor -> dynamicLightColorScheme(context)
            dark -> DarkColors
            else -> LightColors
        }
    SystemBarsFollowTheme(dark)
    MaterialTheme(colorScheme = colorScheme, content = content)
}
