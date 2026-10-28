package ua.mandat.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** A quiet card: white surface, hairline border, rounded corners. */
@Composable
fun MCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        modifier = modifier.fillMaxWidth().clip(shape).let { if (onClick != null) it.clickable(onClick = onClick) else it },
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(Space.card), content = content)
    }
}

/** A section title with an icon; amber for things that need attention. */
@Composable
fun SectionHeader(icon: ImageVector, text: String, attention: Boolean = false, modifier: Modifier = Modifier) {
    val color = if (attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier.fillMaxWidth().padding(top = Space.section, bottom = Space.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/** A small coloured label, for example a matter's type. */
@Composable
fun Tag(text: String, tint: Tint) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = tint.fg,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(tint.bg).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** A neutral label, for example a role. */
@Composable
fun NeutralTag(text: String) = Tag(text, Tint(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant))

/** A number with a short label, for the summary at the top of Today. */
@Composable
fun RowScope.StatTile(value: Int, label: String, attention: Boolean = false, accent: Color? = null, onClick: (() -> Unit)? = null) {
    // Calm by default: a tint of the number's own colour. Something to look at gets a coral outline
    // and number, not a heavy fill, so warnings keep their weight
    val warn = attention && value > 0
    val tone = if (warn) ChartColors.coral else accent
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.weight(1f).clip(shape)
            .background(tone?.copy(alpha = 0.10f) ?: if (LocalOnCanvas.current) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .let { if (warn) it.border(androidx.compose.foundation.BorderStroke(1.dp, ChartColors.coral.copy(alpha = 0.6f)), shape) else it }
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(value.toString(), style = MaterialTheme.typography.titleLarge, color = if (value > 0) tone ?: MaterialTheme.colorScheme.onSurface else Muted)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A round badge with a person's initials. */
@Composable
fun Initials(name: String, team: Boolean) {
    val initials = name.split(" ", "-").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
    val bg = if (team) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (team) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.size(40.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text(initials, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

/** An empty section: one calm line with an icon, inviting the next step. */
@Composable
fun EmptyHint(icon: ImageVector, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp).padding(top = 2.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
    }
}

/** Top of a main screen: a small line above (for example the date) and the title, with actions. */
@Composable
fun ScreenHeader(title: String, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = Space.screen, end = 4.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(0.dp), content = actions)
    }
}

/** One fact in a summary strip; tapping it can filter the list below. */
class StripItem(
    val value: String,
    val label: String,
    val attention: Boolean = false,
    val selected: Boolean = false,
    val onClick: (() -> Unit)? = null,
)

/**
 * The small summary at the top of a screen: a few facts about this screen only. It can be
 * folded away; the choice is remembered per screen.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SummaryStrip(key: String, items: List<StripItem>, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    val context = androidx.compose.ui.platform.LocalContext.current
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(Appearance.stripOpen(context, key)) }
    fun toggle() {
        open = !open
        Appearance.setStripOpen(context, key, open)
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        if (open) {
            androidx.compose.foundation.layout.FlowRow(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items.forEach { item -> StripChip(item) }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        androidx.compose.material3.IconButton(onClick = ::toggle, modifier = Modifier.size(32.dp)) {
            Icon(
                androidx.compose.material.icons.Icons.Filled.ArrowDropDown,
                contentDescription = androidx.compose.ui.res.stringResource(if (open) ua.mandat.R.string.strip_hide else ua.mandat.R.string.strip_show),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(if (open) 180f else 0f),
            )
        }
    }
}

@Composable
private fun StripChip(item: StripItem) {
    val scheme = MaterialTheme.colorScheme
    val bg = when {
        item.selected -> scheme.primaryContainer
        item.attention -> scheme.errorContainer
        else -> scheme.surfaceVariant
    }
    val fg = when {
        item.selected -> scheme.onPrimaryContainer
        item.attention -> scheme.onErrorContainer
        else -> scheme.onSurface
    }
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(bg)
            .let { if (item.onClick != null) it.clickable(onClick = item.onClick) else it }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(item.value, style = MaterialTheme.typography.labelLarge, color = fg)
        Spacer(Modifier.width(6.dp))
        Text(item.label, style = MaterialTheme.typography.labelMedium, color = if (item.selected || item.attention) fg else scheme.onSurfaceVariant)
    }
}

/** Plain colour helper for text that is not important. */
val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

/** Material Symbols (Apache 2.0) shipped as drawables: clearer pictures than the basic set. */
object Sym {
    val inbox @Composable get() = sym(ua.mandat.R.drawable.sym_inbox)
    val folder @Composable get() = sym(ua.mandat.R.drawable.sym_folder)
    val explore @Composable get() = sym(ua.mandat.R.drawable.sym_explore)
    val group @Composable get() = sym(ua.mandat.R.drawable.sym_group)
    val calendar @Composable get() = sym(ua.mandat.R.drawable.sym_calendar_month)
    val home @Composable get() = sym(ua.mandat.R.drawable.sym_home)
    val target @Composable get() = sym(ua.mandat.R.drawable.sym_target)
    val warning @Composable get() = sym(ua.mandat.R.drawable.sym_warning)
    val schedule @Composable get() = sym(ua.mandat.R.drawable.sym_schedule)
    val layers @Composable get() = sym(ua.mandat.R.drawable.sym_layers)
    val bedtime @Composable get() = sym(ua.mandat.R.drawable.sym_bedtime)
    val checkCircle @Composable get() = sym(ua.mandat.R.drawable.sym_check_circle)
    val save @Composable get() = sym(ua.mandat.R.drawable.sym_save)
    val badge @Composable get() = sym(ua.mandat.R.drawable.sym_badge)
    val notifications @Composable get() = sym(ua.mandat.R.drawable.sym_notifications)
    val palette @Composable get() = sym(ua.mandat.R.drawable.sym_palette)
    val info @Composable get() = sym(ua.mandat.R.drawable.sym_info)
    val lock @Composable get() = sym(ua.mandat.R.drawable.sym_lock)
    val search @Composable get() = sym(ua.mandat.R.drawable.sym_search)
    val settings @Composable get() = sym(ua.mandat.R.drawable.sym_settings)
}

@Composable
fun sym(id: Int): ImageVector = ImageVector.vectorResource(id)
