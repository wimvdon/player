package to.wim.player.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * A short horizontal bar for a 0..1 value, like the listener bars on Last.fm's charts. The value
 * is also in [label] for screen readers, so the bar never carries meaning on its own.
 */
@Composable
fun ScoreMeter(
    value: Float,
    modifier: Modifier = Modifier,
    width: Dp = 40.dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Box(
        modifier =
            modifier
                .width(width)
                .padding(vertical = 2.dp)
                .size(width = width, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(value.coerceIn(0.04f, 1f))
                .background(color),
        )
    }
}

/** Album art when Spotify gave one, otherwise the artist's first letter on a tinted square. */
@Composable
fun CoverArt(
    url: String?,
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    val shape = RoundedCornerShape(8.dp)
    if (url != null) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
    } else {
        Box(
            modifier = modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = name.trim().take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/** A section title inside a screen (Title Small in the primary colour, as in the settings). */
@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.semantics { heading() },
    )
}
