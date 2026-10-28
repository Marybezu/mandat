package ua.mandat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ua.mandat.R
import ua.mandat.data.Attention
import ua.mandat.data.Graph
import ua.mandat.data.MandatDb
import ua.mandat.data.MatterType
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * The map of links: matters and the people in them, as points and lines. Bigger points have more
 * links; a person between several matters shows who connects the work. Pinch to zoom, drag to
 * move, tap a point to see its links, tap its name to open it. Hidden matters are not drawn.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GraphScreen(db: MandatDb, session: Session) {
    BackHandler { session.showGraph = false }
    val active by remember(db) { db.matters().observeActive().hiding(session) { it.id } }.collectAsStateWithLifecycle(emptyList())
    val people by remember(db) { db.people().observeAll().hidingPeople(session) }.collectAsStateWithLifecycle(emptyList())
    val links by remember(db) { db.people().observeLinks().hiding(session) { it.matterId } }.collectAsStateWithLifecycle(emptyList())
    var focusOnly by remember { mutableStateOf(true) }
    val shown = if (focusOnly) active.filter { Attention.of(it.attention) == Attention.FOCUS } else active.filter { Attention.of(it.attention) != Attention.SLEEPING }
    val typeOf = active.associate { it.id to MatterType.of(it.type) }
    val layout by produceState<Graph.Layout?>(null, shown, people, links) {
        value = withContext(Dispatchers.Default) {
            Graph.build(shown.map { it.id to it.title }, people.associate { it.id to it.name }, links.map { it.matterId to it.personId })
        }
    }
    // A pick belongs to one layout: when the map is counted again, it is cleared
    var picked by remember(layout) { mutableStateOf<Int?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurface
    val lineColor = MaterialTheme.colorScheme.outline
    val people0 = Tone.people

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.graph_title)) },
            navigationIcon = { IconButton(onClick = { session.showGraph = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Column(Modifier.padding(horizontal = Space.screen), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickChip(stringResource(R.string.graph_focus), focusOnly) { focusOnly = true; picked = null; scale = 1f; pan = Offset.Zero }
                    QuickChip(stringResource(R.string.graph_all), !focusOnly) { focusOnly = false; picked = null; scale = 1f; pan = Offset.Zero }
                }
                Text(stringResource(R.string.graph_hint), style = MaterialTheme.typography.labelSmall, color = Muted)
            }
            val g = layout
            if (g == null || g.nodes.isEmpty()) {
                Text(stringResource(R.string.graph_empty), style = MaterialTheme.typography.bodyMedium, color = Muted, modifier = Modifier.padding(Space.screen))
                return@Column
            }
            val neighbours = picked?.let { p -> g.edges.mapNotNull { (a, b) -> if (a == p) b else if (b == p) a else null }.toSet() + p }
            Canvas(
                Modifier.weight(1f).fillMaxWidth()
                    .pointerInput(g) {
                        detectTransformGestures { centroid, move, zoom, _ ->
                            val s = (scale * zoom).coerceIn(0.6f, 6f)
                            pan = (pan - centroid) * (s / scale) + centroid + move
                            scale = s
                        }
                    }
                    .pointerInput(g, scale, pan) {
                        detectTapGestures { tap ->
                            val m = 24.dp.toPx()
                            val w = size.width - 2 * m; val h = size.height - 2 * m
                            val hit = g.nodes.indices.minByOrNull { i ->
                                val x = (m + g.nodes[i].x * w) * scale + pan.x; val y = (m + g.nodes[i].y * h) * scale + pan.y
                                hypot(x - tap.x, y - tap.y)
                            }?.takeIf { i ->
                                val x = (m + g.nodes[i].x * w) * scale + pan.x; val y = (m + g.nodes[i].y * h) * scale + pan.y
                                hypot(x - tap.x, y - tap.y) < 28.dp.toPx()
                            }
                            picked = if (hit == picked) null else hit
                        }
                    },
            ) {
                val m = 24.dp.toPx()
                val w = size.width - 2 * m; val h = size.height - 2 * m
                fun at(i: Int) = Offset((m + g.nodes[i].x * w) * scale + pan.x, (m + g.nodes[i].y * h) * scale + pan.y)
                g.edges.forEach { (a, b) ->
                    val on = neighbours == null || (a in neighbours && b in neighbours)
                    drawLine(lineColor.copy(alpha = if (on) 0.45f else 0.08f), at(a), at(b), strokeWidth = if (picked != null && on) 2.dp.toPx() else 1.dp.toPx())
                }
                g.nodes.forEachIndexed { i, node ->
                    val on = neighbours == null || i in neighbours
                    val r = (if (node.isMatter) 6f else 4f) + 2f * sqrt(node.degree.toFloat())
                    val color = if (node.isMatter) typeOf[node.key.drop(1).toLong()]?.chartColor() ?: ChartColors.blue else people0
                    drawCircle(color.copy(alpha = if (on) 1f else 0.2f), r.dp.toPx(), at(i))
                    // Names where they help: matters, people who connect several matters, and everything when zoomed in or picked
                    val label = node.isMatter || node.degree >= 3 || scale >= 1.8f || (neighbours != null && i in neighbours)
                    if (label && on) {
                        val text = if (node.label.length > 26) node.label.take(25) + "…" else node.label
                        drawText(measurer, text, at(i) + Offset(r.dp.toPx() + 3.dp.toPx(), -7.dp.toPx()),
                            style = TextStyle(fontSize = (if (node.isMatter) 11 else 10).sp, color = labelColor.copy(alpha = if (node.isMatter) 0.9f else 0.7f)))
                    }
                }
            }
            picked?.let { i -> g.nodes.getOrNull(i) }?.let { node ->
                val id = node.key.drop(1).toLong()
                Row(Modifier.fillMaxWidth().padding(horizontal = Space.screen, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(node.label, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                        Text(stringResource(if (node.isMatter) R.string.graph_people_n else R.string.graph_matters_n, node.degree),
                            style = MaterialTheme.typography.labelMedium, color = Muted)
                    }
                    TextButton(onClick = {
                        session.showGraph = false
                        if (node.isMatter) session.openMatter(id) else session.openPerson(id)
                    }) { Text(stringResource(R.string.graph_open)) }
                }
            }
        }
    }
}
