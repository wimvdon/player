package to.wim.player.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import to.wim.player.R

// Player's expressive type: Roboto Flex (bundled, SIL Open Font Licence, see
// assets/licenses/RobotoFlex-OFL.txt), wide and heavy, for the few big moments: the wordmark, the
// question on the generator, the set's title and the counters. Everything else keeps Roboto.

/** Wide, heavy Roboto Flex for display and headline text. */
val DisplayFamily =
    FontFamily(
        Font(
            R.font.roboto_flex,
            weight = FontWeight.Black,
            variationSettings =
                FontVariation.Settings(
                    FontVariation.weight(900),
                    FontVariation.width(125f),
                    FontVariation.Setting("opsz", 72f),
                ),
        ),
    )

/** The widest, boldest Roboto Flex: only for the "player" wordmark. */
val WordmarkFamily =
    FontFamily(
        Font(
            R.font.roboto_flex,
            weight = FontWeight.Black,
            variationSettings =
                FontVariation.Settings(
                    FontVariation.weight(1000),
                    FontVariation.width(151f),
                    FontVariation.Setting("opsz", 96f),
                ),
        ),
    )

private val Base = Typography()

private fun TextStyle.display(): TextStyle = copy(fontFamily = DisplayFamily, fontWeight = FontWeight.Black, letterSpacing = 0.sp)

/**
 * Material's type scale with Display and Headline Large in [DisplayFamily]. Headline Medium and
 * Small stay Roboto, because the large top app bar (Settings) uses Headline Medium and app bar
 * titles stay calm (DESIGN.md).
 */
val AppTypography =
    Base.copy(
        displayLarge = Base.displayLarge.display(),
        displayMedium = Base.displayMedium.display(),
        displaySmall = Base.displaySmall.display(),
        headlineLarge = Base.headlineLarge.display(),
    )
