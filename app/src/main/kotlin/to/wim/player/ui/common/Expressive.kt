package to.wim.player.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import to.wim.player.logic.Blob
import to.wim.player.ui.theme.WordmarkFamily
import to.wim.player.ui.theme.rememberReducedMotion

// Player's expressive pieces (DESIGN.md, "This app"): the cookie shape, the wordmark, the morphing
// loader, the wavy progress bar, the thick slider, the stepper, the mood line and the record.
// Decorative motion stops when the phone's animations are off (rememberReducedMotion).

private const val SHAPE_STEPS = 180

/** Fills [path] with a [blob]-shaped outline (radius function [radius]) centred in [size]. */
private fun Path.blob(
    size: Size,
    depth: Float,
    rotation: Float = 0f,
    radius: (Float) -> Float,
) {
    reset()
    val cx = size.width / 2
    val cy = size.height / 2
    val base = min(cx, cy) / (1 + depth)
    for (i in 0..SHAPE_STEPS) {
        val angle = (2 * PI * i / SHAPE_STEPS).toFloat()
        val r = base * radius(angle)
        val a = angle + rotation - (PI / 2).toFloat()
        val x = cx + r * cos(a)
        val y = cy + r * sin(a)
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/** A [Blob] as a Compose shape, for example the nine-lobed cookie behind the play button. */
class BlobShape(private val blob: Blob = Blob.Cookie) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline = Outline.Generic(Path().apply { blob(size, blob.depth) { blob.radius(it) } })
}

/** The cookie with a play triangle, as in the launcher icon. */
@Composable
fun CookiePlay(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
) {
    Box(
        modifier.size(size).clip(BlobShape()).background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(size * 0.62f),
        )
    }
}

/** The "player" wordmark: the cookie and the name in the widest, boldest Roboto Flex. */
@Composable
fun Wordmark(
    name: String,
    modifier: Modifier = Modifier,
    fontSizeSp: Int = 26,
) {
    Row(
        modifier.clearAndSetSemantics {
            contentDescription = name
            heading()
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy((fontSizeSp * 0.35f).dp),
    ) {
        CookiePlay(size = (fontSizeSp * 1.2f).dp)
        Text(
            name.lowercase(),
            style = TextStyle(fontFamily = WordmarkFamily, fontSize = fontSizeSp.sp, letterSpacing = (-0.03f * fontSizeSp).sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** A filled shape that keeps morphing through [Blob.LoaderShapes] while it turns: "working on it". */
@Composable
fun MorphingLoader(
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
) {
    val reduced = rememberReducedMotion()
    val color = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "loader")
    val shapes = Blob.LoaderShapes.size
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = shapes.toFloat(),
        animationSpec = infiniteRepeatable(tween(shapes * 900, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    val turn by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(5000, easing = LinearEasing), RepeatMode.Restart),
        label = "turn",
    )
    val path = remember { Path() }
    val maxDepth = Blob.LoaderShapes.maxOf { it.depth }
    Canvas(modifier.size(size)) {
        if (reduced) {
            path.blob(this.size, Blob.Cookie.depth) { Blob.Cookie.radius(it) }
        } else {
            val (from, to, raw) = Blob.loaderAt(phase)
            // Hold each shape for a moment, then morph quickly to the next.
            val f = ((raw - 0.45f) / 0.55f).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
            path.blob(this.size, maxDepth, rotation = turn) { Blob.morph(from, to, f, it) }
        }
        drawPath(path, color)
    }
}

/**
 * A wavy progress bar: the done part is a moving wave, the rest a flat track with a stop dot.
 * [progress] null shows a wave across the whole width (progress unknown).
 */
@Composable
fun WavyProgress(
    progress: Float?,
    modifier: Modifier = Modifier,
) {
    val reduced = rememberReducedMotion()
    val active = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.primaryContainer
    val transition = rememberInfiniteTransition(label = "wave")
    val shift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "shift",
    )
    val path = remember { Path() }
    val semantics = if (progress == null) Modifier.progressSemantics() else Modifier.progressSemantics(progress.coerceIn(0f, 1f))
    Canvas(modifier.fillMaxWidth().height(24.dp).then(semantics)) {
        val stroke = 5.dp.toPx()
        val mid = size.height / 2
        val amplitude = 3.dp.toPx()
        val wavelength = 30.dp.toPx()
        val phase = if (reduced) 0f else shift
        val start = stroke / 2
        val end = if (progress == null) size.width - stroke / 2 else start + (size.width - stroke) * progress.coerceIn(0f, 1f)
        path.reset()
        var x = start
        while (x <= end) {
            val y = mid + amplitude * sin((2 * PI * (x / wavelength - phase)).toFloat())
            if (x == start) path.moveTo(x, y) else path.lineTo(x, y)
            x += 2f
        }
        if (end > start) drawPath(path, active, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        if (progress != null) {
            val gap = 8.dp.toPx()
            val trackStart = end + gap
            val trackEnd = size.width - stroke / 2
            if (trackStart < trackEnd) {
                drawLine(track, Offset(trackStart, mid), Offset(trackEnd, mid), strokeWidth = stroke, cap = StrokeCap.Round)
            }
            drawCircle(active, radius = stroke / 3, center = Offset(trackEnd, mid))
        }
    }
}

/**
 * Material 3 Expressive's slider: a thick track with a thin upright handle that narrows while you
 * drag. [state] is read out by screen readers in place of the raw percentage.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpressiveSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    state: String,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val dragged by interaction.collectIsDraggedAsState()
    val handleWidth by animateDpAsState(if (pressed || dragged) 2.dp else 4.dp, label = "handle")
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.primaryContainer
    Slider(
        value = value,
        onValueChange = onValueChange,
        interactionSource = interaction,
        modifier = modifier.fillMaxWidth().semantics { stateDescription = state },
        thumb = {
            Box(Modifier.size(width = handleWidth, height = 44.dp).background(active, RoundedCornerShape(2.dp)))
        },
        track = { sliderState ->
            Canvas(Modifier.fillMaxWidth().height(16.dp)) {
                val range = sliderState.valueRange
                val fraction = ((sliderState.value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
                val gap = 6.dp.toPx()
                val x = size.width * fraction
                val outer = CornerRadius(size.height / 2)
                val inner = CornerRadius(4.dp.toPx())
                if (x - gap > 0) {
                    drawRoundRect(active, size = Size(x - gap, size.height), cornerRadius = if (x - gap > size.height) inner else outer)
                    drawRoundRect(active, size = Size(min(size.height, x - gap), size.height), cornerRadius = outer)
                }
                if (x + gap < size.width) {
                    drawRoundRect(inactive, topLeft = Offset(x + gap, 0f), size = Size(size.width - x - gap, size.height), cornerRadius = inner)
                    val w = min(size.height, size.width - x - gap)
                    drawRoundRect(inactive, topLeft = Offset(size.width - w, 0f), size = Size(w, size.height), cornerRadius = outer)
                }
            }
        },
    )
}

/** A big number between a minus and a plus button that turn rounder while pressed. */
@Composable
fun NumberStepper(
    value: Int,
    unit: String,
    description: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    minusLabel: String,
    plusLabel: String,
    minusIcon: ImageVector,
    plusIcon: ImageVector,
    canMinus: Boolean,
    canPlus: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton(minusIcon, minusLabel, onMinus, canMinus)
        Column(
            Modifier
                .weight(1f)
                .clearAndSetSemantics {
                    contentDescription = description
                    liveRegion = LiveRegionMode.Polite
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "$value",
                style = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = "tnum"),
                maxLines = 1,
            )
            Text(unit, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        StepButton(plusIcon, plusLabel, onPlus, canPlus)
    }
}

@Composable
private fun StepButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner by animateDpAsState(if (pressed) 28.dp else 18.dp, label = "corner")
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(corner),
        interactionSource = interaction,
        modifier = Modifier.size(56.dp),
    ) {
        Icon(icon, contentDescription = label)
    }
}

/** The set's mood from first to last track as a line with a soft fill and a dot on the peak. */
@Composable
fun MoodLine(
    moods: List<Float>,
    description: String,
    modifier: Modifier = Modifier,
) {
    val line = MaterialTheme.colorScheme.tertiary
    val dotRing = MaterialTheme.colorScheme.surfaceContainer
    Canvas(
        modifier
            .fillMaxWidth()
            .height(72.dp)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        if (moods.isEmpty()) return@Canvas
        val pad = 4.dp.toPx()
        val w = size.width - 2 * pad
        val h = size.height - 2 * pad
        fun x(i: Int) = pad + if (moods.size == 1) w / 2 else w * i / (moods.size - 1)
        fun y(v: Float) = pad + h * (1 - v.coerceIn(0f, 1f))
        val stroke = Path()
        moods.forEachIndexed { i, v -> if (i == 0) stroke.moveTo(x(i), y(v)) else stroke.lineTo(x(i), y(v)) }
        val fill =
            Path().apply {
                addPath(stroke)
                lineTo(x(moods.lastIndex), size.height)
                lineTo(x(0), size.height)
                close()
            }
        drawPath(fill, Brush.verticalGradient(listOf(line.copy(alpha = 0.4f), line.copy(alpha = 0f))))
        drawPath(stroke, line, style = Stroke(2.5.dp.toPx(), join = StrokeJoin.Round, cap = StrokeCap.Round))
        val peak = moods.indices.maxBy { moods[it] }
        val center = Offset(x(peak), y(moods[peak]))
        drawCircle(dotRing, radius = 6.dp.toPx(), center = center)
        drawCircle(line, radius = 4.dp.toPx(), center = center)
    }
}

/** A record with a play label that spins while [spinning]. */
@Composable
fun SpinningRecord(
    spinning: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
) {
    val reduced = rememberReducedMotion()
    val transition = rememberInfiniteTransition(label = "record")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(3500, easing = LinearEasing), RepeatMode.Restart),
        label = "angle",
    )
    val turn = if (spinning && !reduced) angle else 0f
    val vinyl = MaterialTheme.colorScheme.surfaceContainerLow
    val groove = MaterialTheme.colorScheme.surfaceContainerHigh
    val label = MaterialTheme.colorScheme.primary
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size).graphicsLayer { rotationZ = turn }) {
            val r = this.size.minDimension / 2
            drawCircle(vinyl, r)
            var ring = r - 8.dp.toPx()
            while (ring > r * 0.42f) {
                drawCircle(groove, ring, style = Stroke(1.dp.toPx()))
                ring -= 4.dp.toPx()
            }
            // A light streak so the turning is visible.
            rotate(30f) { drawLine(groove, Offset(center.x, center.y - r + 10.dp.toPx()), Offset(center.x, center.y - r * 0.45f), 3.dp.toPx(), StrokeCap.Round) }
            drawCircle(label, r * 0.38f)
        }
        Icon(
            Icons.Rounded.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(size * 0.22f),
        )
    }
}
