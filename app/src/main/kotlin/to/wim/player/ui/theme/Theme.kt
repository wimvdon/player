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

// Fallback palette (dynamic color off), from the icon's middle gradient stop #FF5A47. Dark is the
// default: warm near-black surfaces with one vivid coral red for what you press (like Spotify's
// one green), amber (tertiary) only where energy and mood are drawn. Light keeps the same roles
// with a deeper red so text on white stays readable. See DESIGN.md, "This app".
private val LightColors =
    lightColorScheme(
        primary = Color(0xFFB02B1B),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFDAD4),
        onPrimaryContainer = Color(0xFF410100),
        secondary = Color(0xFF775651),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFFFDAD4),
        onSecondaryContainer = Color(0xFF2C1511),
        tertiary = Color(0xFF7D5700),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFFDDB0),
        onTertiaryContainer = Color(0xFF281800),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF93000A),
        background = Color(0xFFFFF8F6),
        onBackground = Color(0xFF231918),
        surface = Color(0xFFFFF8F6),
        onSurface = Color(0xFF231918),
        surfaceVariant = Color(0xFFF5DDD9),
        onSurfaceVariant = Color(0xFF534340),
        outline = Color(0xFF85736F),
        outlineVariant = Color(0xFFD8C2BD),
        inverseSurface = Color(0xFF392E2C),
        inverseOnSurface = Color(0xFFFFEDEA),
        inversePrimary = Color(0xFFFF6B57),
        surfaceDim = Color(0xFFE8D6D3),
        surfaceBright = Color(0xFFFFF8F6),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFFFF0EE),
        surfaceContainer = Color(0xFFFCEAE7),
        surfaceContainerHigh = Color(0xFFF6E4E1),
        surfaceContainerHighest = Color(0xFFF1DFDB),
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFFFF6B57),
        onPrimary = Color(0xFF2B0600),
        primaryContainer = Color(0xFF7A1A0E),
        onPrimaryContainer = Color(0xFFFFDAD4),
        secondary = Color(0xFFE7BDB6),
        onSecondary = Color(0xFF442925),
        secondaryContainer = Color(0xFF5D3F3A),
        onSecondaryContainer = Color(0xFFFFDAD4),
        tertiary = Color(0xFFFFC05C),
        onTertiary = Color(0xFF422C00),
        tertiaryContainer = Color(0xFF5C3C00),
        onTertiaryContainer = Color(0xFFFFDDB0),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF150F0E),
        onBackground = Color(0xFFF5E3DF),
        surface = Color(0xFF150F0E),
        onSurface = Color(0xFFF5E3DF),
        surfaceVariant = Color(0xFF3A2F2C),
        onSurfaceVariant = Color(0xFFD6BFBA),
        outline = Color(0xFF9F8984),
        outlineVariant = Color(0xFF52423F),
        inverseSurface = Color(0xFFF5E3DF),
        inverseOnSurface = Color(0xFF392E2C),
        inversePrimary = Color(0xFFB02B1B),
        surfaceDim = Color(0xFF150F0E),
        surfaceBright = Color(0xFF3D3331),
        surfaceContainerLowest = Color(0xFF0F0A09),
        surfaceContainerLow = Color(0xFF1D1614),
        surfaceContainer = Color(0xFF241C1A),
        surfaceContainerHigh = Color(0xFF2F2523),
        surfaceContainerHighest = Color(0xFF3A2F2C),
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
    themeMode: ThemeMode = ThemeMode.DARK,
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
    MaterialTheme(colorScheme = colorScheme, typography = AppTypography, content = content)
}
