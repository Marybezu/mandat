package ua.mandat.ui

import androidx.compose.foundation.layout.widthIn
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ua.mandat.R

/**
 * Screen tests draw sheets in place instead of in a window of their own, so they can be
 * captured. Always false in the app.
 */
val LocalInlineSheets = compositionLocalOf { false }

/**
 * The one way Mandat edits something: a whole-screen sheet over the current screen, in the same
 * look as the screens. A close button and the title on top; the content in cards; one large
 * "Save" at the bottom, under the thumb. Back closes it the same as the cross.
 */
@Composable
fun EditSheet(
    title: String,
    onDismiss: () -> Unit,
    onSave: (() -> Unit)?,
    saveEnabled: Boolean = true,
    saveText: String = stringResource(R.string.save),
    changed: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    // With something typed, the cross and Back first ask: nothing written is thrown away by one tap
    var askDiscard by remember { mutableStateOf(false) }
    val close: () -> Unit = if (changed) ({ askDiscard = true }) else onDismiss
    if (askDiscard) ConfirmDialog(
        stringResource(R.string.discard_title), stringResource(R.string.discard_text), stringResource(R.string.discard_go), danger = true,
        onConfirm = { askDiscard = false; onDismiss() }, onCancel = { askDiscard = false },
    )
    val frame = @Composable {
        SheetFrame(title, close, bottom = onSave?.let { save ->
            {
                Button(onClick = save, enabled = saveEnabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(saveText, style = MaterialTheme.typography.titleSmall)
                }
            }
        }) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Space.screen, vertical = Space.gap),
                verticalArrangement = Arrangement.spacedBy(Space.gap + 4.dp),
                content = content,
            )
        }
    }
    if (LocalInlineSheets.current) frame()
    else Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false),
    ) { frame() }
}

/** A whole-screen sheet window with the shared frame; for sheets that build their own body (lists). */
@Composable
fun SheetWindow(title: String, onDismiss: () -> Unit, bottom: (@Composable () -> Unit)? = null, body: @Composable ColumnScope.() -> Unit) {
    val frame = @Composable { SheetFrame(title, onDismiss, bottom, body) }
    if (LocalInlineSheets.current) frame()
    else Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false)) { frame() }
}

/** The frame shared by sheets: top bar with close and title, the body, an optional bottom bar. */
@Composable
private fun SheetFrame(title: String, onDismiss: () -> Unit, bottom: (@Composable () -> Unit)?, body: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onDismiss)
    NoAutofill()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // On a tablet the sheet keeps a readable width in the middle of the screen
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = Space.screen, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close)) }
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            Column(Modifier.weight(1f), content = body)
            if (bottom != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.fillMaxWidth().padding(horizontal = Space.screen, vertical = 10.dp)) { bottom() }
            }
        }
        }
    }
}

/** A group of fields in a sheet, as a card with a small title. */
@Composable
fun SheetGroup(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    MCard {
        if (title != null) Text(title, style = MaterialTheme.typography.labelLarge, color = Muted, modifier = Modifier.padding(bottom = 6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

/** One choice among a few, as large tiles in a grid (an icon or a colour mark, and a label). */
@Composable
fun <T> ChoiceTiles(
    options: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    onPick: (T) -> Unit,
    columns: Int = 3,
    icon: (@Composable (T) -> ImageVector?)? = null,
    mark: ((T) -> Color?)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { o ->
                    val on = o == selected
                    val shape = RoundedCornerShape(12.dp)
                    Column(
                        Modifier.weight(1f).heightIn(min = 64.dp).clip(shape)
                            .background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                            .border(if (on) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, Color.Transparent), shape)
                            .clickable { onPick(o) }.padding(horizontal = 6.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        val i = icon?.invoke(o)
                        val m = mark?.invoke(o)
                        val fg = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        if (i != null) Icon(i, contentDescription = null, tint = m ?: fg, modifier = Modifier.size(22.dp))
                        else if (m != null) Box(Modifier.size(10.dp).clip(CircleShape).background(m))
                        Spacer(Modifier.size(4.dp))
                        Text(label(o), style = MaterialTheme.typography.labelMedium, color = fg, textAlign = TextAlign.Center, maxLines = 2,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** A field that shows its value as a large tile and opens a picker on tap, with an optional clear. */
@Composable
fun FieldTile(label: String, value: String, modifier: Modifier = Modifier, placeholder: Boolean = false, onClear: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)).clickable(onClick = onClick)
            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Muted)
            Text(value, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (placeholder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        }
        if (onClear != null) IconButton(onClick = onClear) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.clear_date), tint = Muted) }
        else Text("›", style = MaterialTheme.typography.titleLarge, color = Muted, modifier = Modifier.padding(end = 10.dp))
    }
}

/** A small rounded button for quick choices (Today, Tomorrow…); filled when it is the current one. */
@Composable
fun QuickChip(text: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 1,
        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = modifier.clip(RoundedCornerShape(20.dp))
            .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

/** One entry offered by [PickSheet]. */
data class PickItem<K>(val key: K, val title: String, val subtitle: String = "")

/**
 * Picking one thing from a list (a matter, a person, a category): a whole-screen sheet with a
 * search box when the list is long; a tap picks and closes.
 */
@Composable
fun <K> PickSheet(title: String, items: List<PickItem<K>>, onPick: (K) -> Unit, onDismiss: () -> Unit, current: K? = null, footer: (@Composable () -> Unit)? = null) {
    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase()
    val shown = if (q.isEmpty()) items else items.filter { it.title.lowercase().contains(q) || it.subtitle.lowercase().contains(q) }
    val frame = @Composable {
        SheetFrame(title, onDismiss, bottom = null) {
            if (items.size > 8) OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(stringResource(R.string.search)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = Space.screen, vertical = 4.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Space.screen, vertical = 6.dp)) {
                items(shown, key = { it.key.toString() + "|" + it.title }) { item ->
                    val on = item.key == current
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable { onPick(item.key) }.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (item.subtitle.isNotBlank()) Text(item.subtitle, style = MaterialTheme.typography.labelMedium, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (on) Text("✓", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                    }
                }
                if (footer != null) item(key = "footer") { footer() }
            }
        }
    }
    if (LocalInlineSheets.current) frame()
    else Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) { frame() }
}

/**
 * A sheet or dialog is a window of its own: autofill services (a password manager, the keyboard)
 * are kept out of it too, as out of the main window.
 */
@Composable
fun NoAutofill() {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect { view.rootView.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS }
}

/** A short question with two answers (delete?, continue?): compact, the risky answer in the warning colour. */
@Composable
fun ConfirmDialog(title: String?, text: String, confirmText: String, danger: Boolean = false, onConfirm: () -> Unit, onCancel: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        title = title?.let { t -> { Text(t) } },
        text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm) {
                Text(confirmText, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A label row inside a sheet group, with a quiet hint under it. */
@Composable
fun SheetLabel(text: String, hint: String? = null) {
    Column {
        Text(text, style = MaterialTheme.typography.labelLarge)
        if (hint != null) Text(hint, style = MaterialTheme.typography.labelSmall, color = Muted)
    }
}
