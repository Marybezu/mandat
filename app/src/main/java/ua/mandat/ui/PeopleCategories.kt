package ua.mandat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.mandat.R
import ua.mandat.data.MandatDb
import ua.mandat.data.Person
import ua.mandat.data.PersonCategory
import ua.mandat.data.suggestCategory

private const val OWN = "OTHER:"

/** The name of a category key: a standard one, the owner's own word, or "not set". */
@Composable
fun categoryLabel(key: String?): String = when {
    key == null || key == "NONE" -> stringResource(R.string.not_set)
    key.startsWith(OWN) -> key.removePrefix(OWN)
    else -> PersonCategory.of(key)?.let { stringResource(it.label()) } ?: key
}

/** The owner's own categories: every own word already given to someone. */
fun ownCategories(people: List<Person>): List<String> =
    people.flatMap { it.allCategories }.filter { it.startsWith(OWN) }.distinct().sortedBy { it.lowercase() }

/** All categories one can choose: the standard ones (except "other") and the owner's own. */
fun categoryChoices(people: List<Person>): List<String> =
    PersonCategory.entries.filter { it != PersonCategory.OTHER }.map { it.name } + ownCategories(people)

/**
 * Category in the person card: the main one (standard or own), others the person also belongs to,
 * and a suggestion from the organisation and position when none is set.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryFields(db: MandatDb, draft: PersonDraft, session: Session? = null) {
    val people by remember(db) { db.people().observeAll().hidingPeople(session) }.collectAsStateWithLifecycle(emptyList())
    var newOwn by remember { mutableStateOf(false) }
    val choices = categoryChoices(people)
    val currentKey = when {
        draft.category == null -> null
        draft.category == PersonCategory.OTHER -> OWN + draft.categoryCustom.trim()
        else -> draft.category!!.name
    }
    fun pick(key: String?) {
        newOwn = false
        when {
            key == null -> { draft.category = null; draft.categoryCustom = "" }
            key.startsWith(OWN) -> { draft.category = PersonCategory.OTHER; draft.categoryCustom = key.removePrefix(OWN) }
            else -> { draft.category = PersonCategory.of(key); draft.categoryCustom = "" }
        }
        draft.more = draft.more - listOfNotNull(key).toSet()
    }
    Text(stringResource(R.string.person_category), style = MaterialTheme.typography.labelLarge)
    if (draft.category == null) {
        suggestCategory(draft.organization, draft.jobTitle)?.let { s ->
            TextButton(onClick = { pick(s.name) }) { Text(stringResource(R.string.category_suggest, stringResource(s.label()))) }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        QuickChip(stringResource(R.string.not_set), currentKey == null && !newOwn) { pick(null) }
        choices.forEach { k -> QuickChip(categoryLabel(k), currentKey == k) { pick(k) } }
        QuickChip(stringResource(R.string.category_own), newOwn || (draft.category == PersonCategory.OTHER && OWN + draft.categoryCustom.trim() !in choices)) {
            newOwn = true; draft.category = PersonCategory.OTHER; draft.categoryCustom = ""
        }
    }
    if (newOwn || (draft.category == PersonCategory.OTHER && OWN + draft.categoryCustom.trim() !in choices)) {
        OutlinedTextField(value = draft.categoryCustom, onValueChange = { draft.categoryCustom = it.take(100) },
            label = { Text(stringResource(R.string.category_own_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
    if (draft.category != null) {
        Text(stringResource(R.string.category_more), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            choices.filter { it != currentKey }.forEach { k ->
                QuickChip(categoryLabel(k), k in draft.more) { draft.more = if (k in draft.more) draft.more - k else draft.more + k }
            }
        }
    }
}

/**
 * Sorting out people without a category, one by one: the suggestion first, any category in one
 * tap, or skip. Each choice is saved at once.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CategorizeScreen(db: MandatDb, session: Session) {
    BackHandler { session.showCategorize = false }
    val people by remember(db) { db.people().observeAll().hidingPeople(session) }.collectAsStateWithLifecycle(emptyList())
    var skipped by remember { mutableStateOf(setOf<Long>()) }
    val todo = people.filter { it.teamRole == null && it.category == null && it.id !in skipped }
    val p = todo.firstOrNull()
    val choices = categoryChoices(people)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.categorize_title)) },
                navigationIcon = {
                    IconButton(onClick = { session.showCategorize = false }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.screen)) {
            Text(stringResource(R.string.categorize_left, todo.size), style = MaterialTheme.typography.labelLarge, color = Muted)
            Spacer(Modifier.height(Space.gap))
            if (p == null) {
                EmptyHint(Sym.checkCircle, stringResource(R.string.categorize_done))
                return@Column
            }
            MCard {
                Text(p.name, style = MaterialTheme.typography.titleMedium)
                val meta = listOf(p.jobTitle, p.organization).filter { it.isNotBlank() }.joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodyMedium, color = Muted)
            }
            Spacer(Modifier.height(Space.gap))
            fun assign(key: String) {
                val own = key.startsWith(OWN)
                session.write {
                    it.people().update(p.copy(category = if (own) PersonCategory.OTHER.name else key, categoryCustom = if (own) key.removePrefix(OWN) else "",
                        updatedAt = System.currentTimeMillis()))
                }
            }
            suggestCategory(p.organization, p.jobTitle)?.let { s ->
                Text(stringResource(R.string.categorize_suggested), style = MaterialTheme.typography.labelLarge)
                QuickChip(stringResource(s.label()), true) { assign(s.name) }
                Spacer(Modifier.height(Space.gap))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                choices.forEach { k -> QuickChip(categoryLabel(k), false) { assign(k) } }
            }
            Row {
                TextButton(onClick = { skipped = skipped + p.id }) { Text(stringResource(R.string.categorize_skip)) }
                TextButton(onClick = { session.openPerson(p.id) }) { Text(stringResource(R.string.categorize_open)) }
            }
        }
    }
}
