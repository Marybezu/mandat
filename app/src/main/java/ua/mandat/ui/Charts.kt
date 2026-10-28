package ua.mandat.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.hypot

/** One part of a ring or a bar: how many, its colour, its name, and a key for filtering. */
/** [display] replaces the plain number in the legend (for example "3 год · 40 %"). */
data class Slice(val key: String, val label: String, val value: Int, val color: Color, val suffix: String = "", val display: String? = null)

/** Calm colours with meaning, readable in light and dark themes. */
object ChartColors {
    val blue = FlagBlue
    val blueLight = Color(0xFF85B7EB)
    val coral = Color(0xFFD85A30)
    val purple = Color(0xFF7F77DD)
    val purpleLight = Color(0xFFAFA9EC)
    val teal = Color(0xFF1D9E75)
    val green = Color(0xFF639922)
    val sky = Color(0xFF378ADD)
    val pink = Color(0xFFD4537E)
    val amber = Color(0xFFBA7517)
    val amberLight = Color(0xFFFAC775)
    val gray = Color(0xFF888780)
    val grayLight = Color(0xFFB4B2A9)
    val salmon = Color(0xFFF0997B)
    /** Focus: amber-yellow, readable on light and dark backgrounds. */
    val focus = Color(0xFFF2B300)
    /** The on-device AI (✦): violet, a lighter one on dark backgrounds. */
    val aiLight = Color(0xFF6A4FD8)
    val aiDark = Color(0xFFB9A8FF)
}

/**
 * A ring divided into parts, with a number in the middle. Tapping a part (or its line in the
 * legend) picks it; tapping it again clears the choice.
 */
@Composable
fun DonutChart(
    slices: List<Slice>,
    center: String,
    centerLabel: String,
    selected: String?,
    onPick: (String?) -> Unit,
    size: Dp = 116.dp,
) {
    val parts = slices.filter { it.value > 0 }
    val total = parts.sumOf { it.value }.coerceAtLeast(1)
    val track = MaterialTheme.colorScheme.surfaceVariant
    val description = parts.joinToString(", ") { "${it.label} ${it.value}" }
    // Lengths are compared more accurately than angles: with many parts, ranked bars instead of a ring
    if (parts.size > 5) {
        RankedBars(parts, center, centerLabel, selected, onPick)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Ring(parts, total, track, description, center, centerLabel, selected, onPick, size)
        Spacer(Modifier.width(14.dp))
        Legend(parts, selected, onPick, Modifier.weight(1f))
    }
}

/**
 * Parts as bars, largest first: the top five, then "N others" that unfolds. A bar can be
 * tapped to pick it, like a part of a ring.
 */
@Composable
fun RankedBars(parts: List<Slice>, center: String, centerLabel: String, selected: String?, onPick: (String?) -> Unit) {
    var open by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val sorted = parts.sortedByDescending { it.value }
    val max = sorted.firstOrNull()?.value?.coerceAtLeast(1) ?: 1
    val top = if (open) sorted else sorted.take(5)
    val rest = sorted.drop(5)
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(bottom = 6.dp)) {
            Text(center, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(6.dp))
            Text(centerLabel, style = MaterialTheme.typography.labelMedium, color = Muted, modifier = Modifier.padding(bottom = 3.dp))
        }
        top.forEach { p -> BarRow(p.label, p.value, p.value.toFloat() / max, p.color, selected == p.key, selected != null && selected != p.key) {
            onPick(if (selected == p.key) null else p.key)
        } }
        if (rest.isNotEmpty()) {
            val restSum = rest.sumOf { it.value }
            if (!open) BarRow(androidx.compose.ui.res.stringResource(ua.mandat.R.string.others_n, rest.size), restSum,
                (restSum.toFloat() / max).coerceAtMost(1f), ChartColors.grayLight, false, selected != null) { open = true }
            else androidx.compose.material3.TextButton(onClick = { open = false }) {
                Text(androidx.compose.ui.res.stringResource(ua.mandat.R.string.show_less))
            }
        }
    }
}

@Composable
private fun BarRow(label: String, value: Int, fraction: Float, color: Color, isSel: Boolean, dim: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .background(if (isSel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(130.dp))
        Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).height(10.dp).clip(RoundedCornerShape(5.dp))
                .background(if (dim) color.copy(alpha = 0.3f) else color))
        }
        Text(groupDigits(value), style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false,
            modifier = Modifier.widthIn(min = 36.dp).padding(start = 8.dp))
    }
}

@Composable
private fun Ring(
    parts: List<Slice>, total: Int, track: Color, description: String, center: String, centerLabel: String,
    selected: String?, onPick: (String?) -> Unit, size: Dp,
) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(
                Modifier.size(size).semantics { contentDescription = description }.pointerInput(parts, selected) {
                    detectTapGestures { pos ->
                        val c = Offset(this.size.width / 2f, this.size.height / 2f)
                        val r = hypot(pos.x - c.x, pos.y - c.y)
                        if (r < this.size.width * 0.25f) {
                            onPick(null)
                            return@detectTapGestures
                        }
                        // Angle from the top, clockwise, 0..360
                        var a = Math.toDegrees(atan2((pos.y - c.y).toDouble(), (pos.x - c.x).toDouble())).toFloat() + 90f
                        if (a < 0) a += 360f
                        var acc = 0f
                        for (p in parts) {
                            acc += 360f * p.value / total
                            if (a <= acc) {
                                onPick(if (selected == p.key) null else p.key)
                                return@detectTapGestures
                            }
                        }
                    }
                },
            ) {
                val stroke = this.size.width * 0.14f
                val inset = stroke / 2f + 2f
                val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
                drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                var start = -90f
                val gap = if (parts.size > 1) 1.5f else 0f
                parts.forEach { p ->
                    val sweep = 360f * p.value / total
                    val dim = selected != null && selected != p.key
                    drawArc(
                        color = if (dim) p.color.copy(alpha = 0.25f) else p.color,
                        startAngle = start + gap / 2, sweepAngle = (sweep - gap).coerceAtLeast(0.5f), useCenter = false,
                        topLeft = Offset(inset, inset), size = arcSize,
                        style = Stroke(if (selected == p.key) stroke * 1.25f else stroke),
                    )
                    start += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(size * 0.62f)) {
                // A long value ("30 h 15 min") goes on two lines in a smaller size, so it stays inside the ring
                val long = center.length > 6
                val shown = if (long && ' ' in center) {
                    val spaces = center.indices.filter { center[it] == ' ' }
                    val cut = spaces.minByOrNull { kotlin.math.abs(it - center.length / 2) }!!
                    center.substring(0, cut) + "\n" + center.substring(cut + 1)
                } else center
                Text(shown, style = if (long) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleLarge,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(centerLabel, style = MaterialTheme.typography.labelSmall, color = Muted)
            }
        }
}

/** The names and numbers of a chart's parts; each line can be tapped to pick that part. */
@Composable
fun Legend(parts: List<Slice>, selected: String?, onPick: (String?) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        parts.forEach { p ->
            val isSel = selected == p.key
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                    .background(if (isSel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .clickable { onPick(if (isSel) null else p.key) }
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(p.color))
                Spacer(Modifier.width(8.dp))
                Text(p.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                Text(p.display ?: groupDigits(p.value), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * A small ring showing one number against a limit or a total, with a label under it.
 * [overflow] (for example more in focus than the limit) is drawn in the attention colour.
 */
@Composable
fun RingStat(
    text: String,
    label: String,
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    overflow: Float = 0f,
    attention: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val warn = MaterialTheme.colorScheme.error
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(72.dp)) {
                val stroke = 8.dp.toPx()
                val inset = stroke / 2f
                val s = Size(this.size.width - stroke, this.size.height - stroke)
                drawArc(track, 0f, 360f, false, Offset(inset, inset), s, style = Stroke(stroke))
                val main = fraction.coerceIn(0f, 1f) * 360f * (1f - overflow.coerceIn(0f, 0.5f))
                if (main > 0f) drawArc(color, -90f, main, false, Offset(inset, inset), s, style = Stroke(stroke, cap = StrokeCap.Round))
                if (overflow > 0f) {
                    val o = overflow.coerceIn(0f, 0.5f) * 360f
                    drawArc(warn, -90f + 360f - o, o, false, Offset(inset, inset), s, style = Stroke(stroke))
                }
            }
            Text(text, style = MaterialTheme.typography.titleMedium, color = if (attention) warn else MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (attention) warn else Muted, maxLines = 1)
    }
}

/** A horizontal bar split into parts, with a legend line under it. Parts can be tapped. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun StackBar(slices: List<Slice>, selected: String? = null, onPick: ((String?) -> Unit)? = null) {
    val parts = slices.filter { it.value > 0 }
    if (parts.isEmpty()) return
    Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))) {
        parts.forEach { p ->
            val dim = selected != null && selected != p.key
            Box(
                Modifier.weight(p.value.toFloat()).fillMaxHeight()
                    .background(if (dim) p.color.copy(alpha = 0.25f) else p.color)
                    .let { m -> if (onPick != null) m.clickable { onPick(if (selected == p.key) null else p.key) } else m },
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        parts.forEach { p ->
            Row(
                Modifier.clip(RoundedCornerShape(6.dp))
                    .background(if (selected == p.key) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .let { m -> if (onPick != null) m.clickable { onPick(if (selected == p.key) null else p.key) } else m }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(p.color))
                Spacer(Modifier.width(5.dp))
                Text("${p.label} ${groupDigits(p.value)}${p.suffix}", style = MaterialTheme.typography.labelMedium, color = Muted)
            }
        }
    }
}

/** One day column: its label, how much is on it, and whether a hard deadline falls on it. */
data class DayLoad(val day: Long, val label: String, val count: Int, val hard: Boolean)

/** Columns for the coming days; a darker column has a hard deadline. Tap a day to pick it. */
@Composable
fun DayColumns(days: List<DayLoad>, selected: Long?, onPick: (Long?) -> Unit) {
    val max = days.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
        days.forEach { d ->
            val isSel = selected == d.day
            Column(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(6.dp))
                    .background(if (isSel) scheme.primaryContainer else Color.Transparent)
                    .clickable { onPick(if (isSel) null else d.day) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                if (d.count > 0) Text(d.count.toString(), style = MaterialTheme.typography.labelSmall, color = Muted)
                val h = if (d.count == 0) 3.dp else (56f * d.count / max).coerceAtLeast(8f).dp
                Box(
                    Modifier.padding(horizontal = 3.dp).fillMaxWidth().height(h).clip(RoundedCornerShape(4.dp))
                        .background(if (d.hard) ChartColors.blue else if (d.count == 0) scheme.surfaceVariant else ChartColors.blueLight),
                )
                Spacer(Modifier.height(4.dp))
                Text(d.label, style = MaterialTheme.typography.labelSmall, color = Muted, maxLines = 1)
            }
        }
    }
}

/**
 * A card holding one section of a list: an icon, a title with a count, then rows separated
 * by thin lines. Gives every group a clear border.
 */
@Composable
fun SectionCard(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    attention: Boolean = false,
    /** A colour with meaning for the icon (for example the level of attention). */
    tint: Color? = null,
    /** With a fixed name, the card can be folded by tapping its title, and stays so. */
    foldKey: String? = null,
    /** A muted colour for the whole card: a level of attention as a canvas the items lie on. */
    canvas: Color? = null,
    content: @Composable () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var folded by androidx.compose.runtime.remember(foldKey) { androidx.compose.runtime.mutableStateOf(foldKey != null && Appearance.isFolded(context, foldKey)) }
    val body: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
        val color = if (attention) MaterialTheme.colorScheme.error else if (canvas != null) canvas else Muted
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).let { m ->
                if (foldKey == null) m else m.clickable { folded = !folded; Appearance.setFolded(context, foldKey, folded) }
            },
        ) {
            Icon(icon, contentDescription = null, tint = tint ?: color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(title + (count?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelLarge, color = color, modifier = Modifier.weight(1f))
            if (foldKey != null) Text(if (folded) "▸" else "▾", color = Muted)
        }
        if (!folded) androidx.compose.runtime.CompositionLocalProvider(LocalOnCanvas provides (canvas != null)) { content() }
    }
    if (canvas == null) MCard(modifier, content = body)
    else {
        val shape = RoundedCornerShape(12.dp)
        androidx.compose.material3.Surface(
            modifier = modifier.fillMaxWidth(), shape = shape,
            color = canvas.copy(alpha = 0.13f).compositeOver(MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, canvas.copy(alpha = 0.35f)),
        ) { Column(Modifier.padding(Space.card), content = body) }
    }
}

/** True inside a coloured canvas: rows there are drawn on plain surface, so they stand out from it. */
val LocalOnCanvas = androidx.compose.runtime.compositionLocalOf { false }

/** The gap between tiles inside a [SectionCard]. */
@Composable
fun RowDivider() = Spacer(Modifier.height(6.dp))

/** A small removable label showing an active filter. */
@Composable
fun FilterBadge(text: String, onClear: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onClear).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.width(6.dp))
        Text("✕", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/** The colour of a matter type in charts (the same family as its tag). */
fun ua.mandat.data.MatterType.chartColor(): Color = when (this) {
    ua.mandat.data.MatterType.CAMPAIGN -> ChartColors.coral
    ua.mandat.data.MatterType.BILL -> ChartColors.purple
    ua.mandat.data.MatterType.INVESTIGATION -> ChartColors.teal
    ua.mandat.data.MatterType.COURT -> ChartColors.gray
    ua.mandat.data.MatterType.DISTRICT -> ChartColors.green
    ua.mandat.data.MatterType.INTERNATIONAL -> ChartColors.sky
    ua.mandat.data.MatterType.WORKING_GROUP -> ChartColors.purpleLight
    ua.mandat.data.MatterType.INQUIRY -> ChartColors.pink
    ua.mandat.data.MatterType.PUBLIC_COMMS -> ChartColors.amber
    ua.mandat.data.MatterType.AID -> ChartColors.salmon
    ua.mandat.data.MatterType.OTHER -> ChartColors.grayLight
}

/** Colours for a list of things without their own meaning (roles, categories), in a calm order. */
val SeriesColors = listOf(
    ChartColors.blue, ChartColors.teal, ChartColors.coral, ChartColors.purple, ChartColors.green,
    ChartColors.amber, ChartColors.pink, ChartColors.sky, ChartColors.purpleLight, ChartColors.gray, ChartColors.grayLight,
)

/** A small bar for one row of a list (for example one matter), parts in proportion. */
@Composable
fun MiniBar(slices: List<Slice>, modifier: Modifier = Modifier) {
    val parts = slices.filter { it.value > 0 }
    Row(modifier.height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        parts.forEach { p -> Box(Modifier.weight(p.value.toFloat()).fillMaxHeight().background(p.color)) }
    }
}

/** One matter in a "by matter" list: title, a small bar, the numbers; tap to open. */
@Composable
fun ByMatterRow(title: String, slices: List<Slice>, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiniBar(slices, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(slices.filter { it.value > 0 }.joinToString(" · ") { it.value.toString() }, style = MaterialTheme.typography.labelSmall, color = Muted)
        }
    }
}

/**
 * Rows in portions: the first [page], then "N more". Long lists stay quick to draw and easy
 * to take in; nothing is hidden, only shown step by step.
 */
@Composable
fun <T> Paged(items: List<T>, page: Int = 20, row: @Composable (T) -> Unit) {
    var shown by androidx.compose.runtime.saveable.rememberSaveable(items.size) { androidx.compose.runtime.mutableStateOf(page) }
    items.take(shown).forEachIndexed { i, x ->
        if (i > 0) RowDivider()
        row(x)
    }
    if (items.size > shown) {
        androidx.compose.material3.TextButton(onClick = { shown += page }) {
            Text(androidx.compose.ui.res.stringResource(ua.mandat.R.string.show_more, minOf(page, items.size - shown)))
        }
    }
}

/** A whole number with thin spaces between thousands: 14 040. */
fun groupDigits(n: Int): String = ua.mandat.data.formatAmount(n.toLong() * 100)

/** Attention at a glance: focus is warm and bright, the working background calm blue, sleeping pale. */
fun ua.mandat.data.Attention.color(): Color = when (this) {
    ua.mandat.data.Attention.FOCUS -> ChartColors.focus
    ua.mandat.data.Attention.BACKGROUND -> ChartColors.sky
    ua.mandat.data.Attention.SLEEPING -> ChartColors.grayLight
}

/** A number with a short label that opens the list behind it. Every count on a screen leads somewhere. */
@Composable
fun StatLink(value: Int, label: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(groupDigits(value), style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        Spacer(Modifier.width(6.dp))
        Text("$label →", style = MaterialTheme.typography.labelMedium, color = Muted)
    }
}

/** One number of a [CompactStats] line. */
class CompactStat(val value: String, val label: String, val accent: Color, val onClick: (() -> Unit)? = null)

/** A compact line of a few numbers, each in its own colour, split by hairlines; each can open its list. */
@Composable
fun CompactStats(stats: List<CompactStat>) {
    Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
        stats.forEachIndexed { i, s ->
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 8.dp).background(MaterialTheme.colorScheme.outlineVariant))
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).let { m -> s.onClick?.let { m.clickable(onClick = it) } ?: m }.padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(s.value, style = MaterialTheme.typography.titleLarge, color = s.accent, maxLines = 1)
                Text(s.label, style = MaterialTheme.typography.labelSmall, color = Muted, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

/**
 * The meaning of colours across Mandat: the same colour always says the same thing, so the eye
 * reads groups before words. Used muted, as canvases and tints; a screen uses only a few.
 */
object Tone {
    /** Focus and attention. */
    val focus get() = ChartColors.focus
    /** Ordinary planned work, events and the calendar. */
    val plan get() = ChartColors.blue
    /** Deadlines, risk, what needs looking at. */
    val risk get() = ChartColors.coral
    /** Promises. */
    val promise get() = ChartColors.purple
    /** People and the team. */
    val people get() = ChartColors.teal
    /** The district and its communities. */
    val district get() = ChartColors.green
    /** Media and information. */
    val media get() = ChartColors.pink
}
