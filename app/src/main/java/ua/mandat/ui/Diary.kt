package ua.mandat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.mandat.R
import ua.mandat.data.MandatDb
import ua.mandat.data.Review
import ua.mandat.data.ReviewLink
import java.time.LocalDate

/** A colour for a 1–5 value: from muted coral (low) to teal (high). Only a picture, never a score. */
private fun levelColor(v: Int): Color = when (v) {
    1 -> ChartColors.salmon
    2 -> ChartColors.amberLight
    3 -> ChartColors.grayLight
    4 -> ChartColors.blueLight
    else -> ChartColors.teal
}

/** Saves energy or mood for a day, creating the day's entry when there is none yet. */
fun setFeel(session: Session, day: Long, energy: Int? = null, mood: Int? = null, clearEnergy: Boolean = false, clearMood: Boolean = false) {
    session.write { db ->
        val r = db.reviews().forDay(day)
        if (r == null) {
            db.reviews().insert(Review(day = day, progress = "", createdAt = System.currentTimeMillis(), energy = energy, mood = mood))
        } else {
            db.reviews().update(r.copy(energy = if (clearEnergy) null else energy ?: r.energy, mood = if (clearMood) null else mood ?: r.mood))
        }
    }
}

/** Five small circles to note energy or mood; tapping the chosen one again clears it. */
@Composable
fun FeelRow(label: String, value: Int?, onPick: (Int?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(96.dp))
        (1..5).forEach { v ->
            val sel = value == v
            Box(
                Modifier.padding(end = 6.dp).size(32.dp).clip(RoundedCornerShape(16.dp))
                    .background(if (sel) levelColor(v) else MaterialTheme.colorScheme.surfaceVariant)
                    .let { if (sel) it.border(2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(16.dp)) else it }
                    .clickable { onPick(if (sel) null else v) },
                contentAlignment = Alignment.Center,
            ) { Text(v.toString(), style = MaterialTheme.typography.labelMedium) }
        }
    }
}

/**
 * Below the evening question: energy and mood of today (optional, without scores or advice),
 * and what the day was about (matters and people).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TodayFeel(db: MandatDb, session: Session) {
    val today = Dates.today()
    val review by remember(db, today) { db.reviews().observeDay(today) }.collectAsStateWithLifecycle(null)
    val links by remember(db) { db.reviews().observeLinks() }.collectAsStateWithLifecycle(emptyList())
    val people by remember(db) { db.people().observeAll().hidingPeople(session) }.collectAsStateWithLifecycle(emptyList())
    val titles by remember(db) { db.matters().observeAllTitles().hiding(session) { it.id } }.collectAsStateWithLifecycle(emptyList())
    var picking by remember { mutableStateOf<String?>(null) }
    // Tonight's entry tied to something hidden: neither its feel nor its links are shown
    val hiddenNow by session.hidden.collectAsStateWithLifecycle()
    val r = review?.takeIf { it.id !in hiddenNow.reviews }
    FeelRow(stringResource(R.string.diary_energy), r?.energy) { v -> setFeel(session, today, energy = v, clearEnergy = v == null) }
    FeelRow(stringResource(R.string.diary_mood), r?.mood) { v -> setFeel(session, today, mood = v, clearMood = v == null) }
    val mine = if (r == null) emptyList() else links.filter { it.reviewId == r.id }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        mine.forEach { l ->
            val name = if (l.kind == "matter") titles.firstOrNull { it.id == l.refId }?.title else people.firstOrNull { it.id == l.refId }?.name
            if (name != null) FilterBadge(name) { session.write { it.reviews().unlink(l.reviewId, l.kind, l.refId) } }
        }
        TextButton(onClick = { picking = "matter" }) { Text("+ " + stringResource(R.string.diary_link_matter)) }
        TextButton(onClick = { picking = "person" }) { Text("+ " + stringResource(R.string.diary_link_person)) }
    }
    picking?.let { kind ->
        val options = if (kind == "matter") titles.map { it.id to it.title } else people.map { it.id to it.name }
        LinkPickDialog(options, onPick = { id ->
            picking = null
            session.write { db2 ->
                val existing = db2.reviews().forDay(today)
                val rid = existing?.id ?: db2.reviews().insert(Review(day = today, progress = "", createdAt = System.currentTimeMillis()))
                db2.reviews().link(ReviewLink(rid, kind, id))
            }
        }, onCancel = { picking = null })
    }
}

@Composable
private fun LinkPickDialog(options: List<Pair<Long, String>>, onPick: (Long) -> Unit, onCancel: () -> Unit) =
    PickSheet(stringResource(R.string.pick_one), options.map { (id, text) -> PickItem(id, text) }, onPick = onPick, onDismiss = onCancel)

/**
 * The diary: a mosaic of half a year (each square a day: written, or its mood or energy), the
 * chosen day, search, and all entries. Nothing is rated; the colours only help to see the weeks.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DiaryScreen(db: MandatDb, session: Session) {
    BackHandler { session.showDiary = false }
    val reviews by remember(db) { db.reviews().observeAll().hidingReviews(session) }.collectAsStateWithLifecycle(emptyList())
    val links by remember(db) { db.reviews().observeLinks() }.collectAsStateWithLifecycle(emptyList())
    val people by remember(db) { db.people().observeAll().hidingPeople(session) }.collectAsStateWithLifecycle(emptyList())
    val titles by remember(db) { db.matters().observeAllTitles().hiding(session) { it.id } }.collectAsStateWithLifecycle(emptyList())
    val nameOf = people.associate { it.id to it.name }
    val titleOf = titles.associate { it.id to it.title }
    val today = Dates.today()
    var mode by rememberSaveable { mutableStateOf(0) } // 0 written, 1 mood, 2 energy
    var picked by rememberSaveable { mutableStateOf<Long?>(null) }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Review?>(null) }
    val byDay = reviews.associateBy { it.day }
    val linksOf = links.groupBy { it.reviewId }
    val q = query.trim().lowercase()
    val shown = reviews.filter { r ->
        (picked == null || r.day == picked) && (q.length < 2 || r.progress.lowercase().contains(q) ||
            linksOf[r.id].orEmpty().any { l -> (if (l.kind == "matter") titleOf[l.refId] else nameOf[l.refId]).orEmpty().lowercase().contains(q) })
    }.sortedByDescending { it.day }
    val written30 = reviews.count { it.day > today - 30 && it.progress.isNotBlank() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diary_title)) },
                actions = { HowItWorks(R.string.how_diary) },
                navigationIcon = {
                    IconButton(onClick = { session.showDiary = false }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).imePadding().fillMaxSize(),
            contentPadding = PaddingValues(start = Space.screen, end = Space.screen, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(Space.gap),
        ) {
            item(key = "mosaic") {
                SectionCard(Sym.calendar, stringResource(R.string.diary_written_30, written30)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                        listOf(R.string.diary_mode_written, R.string.diary_mood, R.string.diary_energy).forEachIndexed { i, label ->
                            QuickChip(stringResource(label), mode == i) { mode = i }
                        }
                    }
                    Mosaic(today, byDay, mode, picked) { picked = it }
                    if (mode > 0) Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        Text("1", style = MaterialTheme.typography.labelSmall, color = Muted)
                        (1..5).forEach { Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(levelColor(it))) }
                        Text("5", style = MaterialTheme.typography.labelSmall, color = Muted)
                    }
                }
            }
            item(key = "search") {
                OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(stringResource(R.string.search)) }, modifier = Modifier.fillMaxWidth())
            }
            picked?.let { d -> item(key = "picked") { Row { FilterBadge(Dates.format(d)) { picked = null } } } }
            if (shown.isEmpty()) item(key = "empty") { EmptyHint(Sym.bedtime, stringResource(R.string.smart_empty)) }
            item(key = "list") {
                if (shown.isNotEmpty()) SectionCard(Sym.bedtime, stringResource(R.string.diary_entries), count = shown.size) {
                    Text(stringResource(R.string.diary_dots_hint), style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.padding(bottom = 4.dp))
                    Paged(shown) { r ->
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                .clickable { editing = r }.padding(10.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                MiniChip(Dates.format(r.day))
                                Spacer(Modifier.width(6.dp))
                                r.energy?.let { Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(levelColor(it))); Spacer(Modifier.width(3.dp)) }
                                r.mood?.let { Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(levelColor(it))) }
                            }
                            if (r.progress.isNotBlank()) Text(r.progress, style = MaterialTheme.typography.bodyMedium, maxLines = 6,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                            val ls = linksOf[r.id].orEmpty()
                            if (ls.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(top = 4.dp)) {
                                ls.forEach { l ->
                                    if (l.kind == "matter") titleOf[l.refId]?.let { MiniChip(it, onClick = { session.openMatter(l.refId) }) }
                                    else nameOf[l.refId]?.let { MiniChip(it, onClick = { session.openPerson(l.refId) }) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    editing?.let { r -> DiaryEditDialog(session, r) { editing = null } }
}

/** Half a year of days in columns of weeks, the newest on the right. Tap a day to see it. */
@Composable
private fun Mosaic(today: Long, byDay: Map<Long, Review>, mode: Int, picked: Long?, onPick: (Long?) -> Unit) {
    val weeks = 26
    val lastMonday = today - (LocalDate.ofEpochDay(today).dayOfWeek.value - 1)
    val start = lastMonday - (weeks - 1) * 7
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.horizontalScroll(rememberScrollState(), reverseScrolling = true), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        (0 until weeks).forEach { w ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                (0 until 7).forEach { d ->
                    val day = start + w * 7 + d
                    val r = byDay[day]
                    val color = when {
                        day > today -> Color.Transparent
                        r == null -> scheme.surfaceVariant
                        mode == 0 -> if (r.progress.isNotBlank()) ChartColors.blue else ChartColors.blueLight
                        mode == 1 -> r.mood?.let(::levelColor) ?: scheme.surfaceVariant
                        else -> r.energy?.let(::levelColor) ?: scheme.surfaceVariant
                    }
                    Box(
                        Modifier.size(11.dp).clip(RoundedCornerShape(2.dp)).background(color)
                            .let { if (picked == day) it.border(1.5.dp, scheme.onSurface, RoundedCornerShape(2.dp)) else it }
                            .let { if (day <= today) it.clickable { onPick(if (picked == day) null else day) } else it },
                    )
                }
            }
        }
    }
}

/** Editing an earlier day: the text, energy and mood. */
@Composable
private fun DiaryEditDialog(session: Session, r: Review, onClose: () -> Unit) {
    var text by remember(r) { mutableStateOf(r.progress) }
    var energy by remember(r) { mutableStateOf(r.energy) }
    var mood by remember(r) { mutableStateOf(r.mood) }
    // A correction being typed is written to that day if Mandat locks meanwhile
    if (text != r.progress || energy != r.energy || mood != r.mood) SaveOnLock(session, "diary-edit") { db ->
        db.reviews().update(r.copy(progress = text.trim().take(ua.mandat.data.MAX_NOTE_LENGTH), energy = energy, mood = mood))
    }
    EditSheet(
        title = Dates.format(r.day),
        onDismiss = onClose,
        onSave = {
                onClose()
                val clean = text.trim().take(ua.mandat.data.MAX_NOTE_LENGTH)
                session.write { it.reviews().update(r.copy(progress = clean, energy = energy, mood = mood)) }
            },
    ) {
        SheetGroup {

                OutlinedTextField(text, { text = it }, minLines = 3, modifier = Modifier.fillMaxWidth())
                FeelRow(stringResource(R.string.diary_energy), energy) { energy = it }
                FeelRow(stringResource(R.string.diary_mood), mood) { mood = it }
        }
    }
}
