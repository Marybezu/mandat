package ua.mandat.ui

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import ua.mandat.data.MandatDb
import ua.mandat.data.MatterStatus
import ua.mandat.data.TaskStatus

/*
 * Plain facts handed to the on-device AI. Each builder reads the database itself and leaves out
 * hidden matters (trip mode, lens) and everything tied to them, so nothing hidden ever reaches a
 * prompt. The text stays in memory only.
 */

private fun MandatDb.hiddenOk(session: Session): (Long?) -> Boolean {
    val hidden = session.hiddenMatters.value
    return { id -> id == null || id !in hidden }
}

/**
 * For free text not tied to a matter (the diary, the Inbox, notes on people): true when it names a
 * hidden matter, so it is left out too while something is hidden.
 */
internal fun MandatDb.namesHidden(session: Session): (String) -> Boolean {
    val names = session.hidden.value.names
    return if (names.isEmpty) { _ -> false } else names::named
}

/**
 * A date for the model: digits with the year and how far it is from today ("26.10.2026, 2 дні тому"),
 * so it never has to guess the year or whether the day has passed.
 */
fun factDate(day: Long): String {
    val d = java.time.LocalDate.ofEpochDay(day)
    val diff = day - Dates.today()
    val rel = when {
        diff == 0L -> "сьогодні"
        diff == 1L -> "завтра"
        diff == -1L -> "вчора"
        diff > 0 -> "через $diff дн."
        else -> "${-diff} дн. тому"
    }
    return "%02d.%02d.%d (%s)".format(d.dayOfMonth, d.monthValue, d.year, rel)
}

/** The owner's post in words for the model; only "голова" means the owner runs that body's meetings. */
private fun postWord(post: String?): String = when (ua.mandat.data.RolePost.of(post)) {
    ua.mandat.data.RolePost.LEAD -> "голова"
    ua.mandat.data.RolePost.DEPUTY -> "заступник"
    ua.mandat.data.RolePost.SECRETARY -> "секретар"
    ua.mandat.data.RolePost.MEMBER -> "член"
    ua.mandat.data.RolePost.OTHER -> "інше"
    null -> "не вказано"
}

/** Each role with the owner's post in it, keyed by id: "Committee … (посада: член)". */
private suspend fun MandatDb.roleLines(): Map<Long, String> =
    roles().observeAll().first().associate { it.id to it.name + " (посада власника: " + postWord(it.post) + ")" }

/** What today holds: dated items due, focus matters, today's meetings and what was promised to those people. */
suspend fun todayFacts(db: MandatDb, session: Session): String {
    val okId = db.hiddenOk(session)
    val named = db.namesHidden(session)
    val ok = { id: Long?, text: String -> okId(id) && !(id == null && named(text)) }
    val today = Dates.today()
    val names = db.people().all().associate { it.id to it.name }
    val dl = db.deadlines().observeOpen().first().filter { ok(it.matterId, it.title) && it.day <= today + 1 }
    val dg = db.delegations().observeOpen().first().filter { ok(it.matterId, it.what) && it.due != null && it.due <= today + 1 }
    val pr = db.promises().observeOpen().first().filter { ok(it.matterId, it.what + " " + it.toWhom) }
    val focus = db.matters().observeAll().first().filter { okId(it.id) && it.attention == "FOCUS" && MatterStatus.of(it.status) == MatterStatus.ACTIVE }
    val events = db.events().observeRange(today, today).first().filter { ok(it.matterId, it.title + " " + it.notes) }.sortedBy { it.startMin ?: -1 }
    val roleOf = db.roleLines()
    val matterRole = db.matters().observeAll().first().associate { it.id to it.roleId }
    return buildString {
        append("Сьогодні ").append(factDate(today)).append(".\n")
        if (roleOf.isNotEmpty()) append("Ролі власника: ").append(roleOf.values.joinToString("; ")).append("\n")
        if (events.isNotEmpty()) {
            append("Зустрічі сьогодні:\n")
            events.forEach { e ->
                val who = db.events().peopleOf(e.id).mapNotNull { names[it] }
                append("- ").append(e.startMin?.let { timeText(it) + " " } ?: "").append(e.title)
                // Whose meeting it is: the matter's role and the owner's post there (unknown: the owner takes part)
                e.matterId?.let { matterRole[it] }?.let { roleOf[it] }?.let { append(" [роль: ").append(it).append("]") }
                if (who.isNotEmpty()) append(" (з: ").append(who.joinToString(", ")).append(")")
                append("\n")
                who.forEach { n ->
                    val promised = pr.filter { p -> p.personId != null && names[p.personId] == n }.take(3)
                    if (promised.isNotEmpty()) append("  ми обіцяли ").append(n).append(": ").append(promised.joinToString("; ") { it.what }).append("\n")
                }
            }
        }
        if (dl.isNotEmpty()) append("Строки (сьогодні, завтра чи прострочені): ").append(dl.take(10).joinToString("; ") { it.title + " — " + factDate(it.day) }).append("\n")
        if (dg.isNotEmpty()) append("Доручення до перевірки: ").append(dg.take(10).joinToString("; ") { it.what + (it.personId?.let { p -> " (" + names[p] + ")" } ?: "") }).append("\n")
        val duePr = pr.filter { it.due != null && it.due <= today + 1 }
        if (duePr.isNotEmpty()) append("Обіцянки зі строком: ").append(duePr.take(10).joinToString("; ") { it.what }).append("\n")
        if (focus.isNotEmpty()) append("У фокусі: ").append(focus.joinToString("; ") { it.title + (it.nextStep.takeIf { n -> n.isNotBlank() }?.let { n -> " (далі: $n)" } ?: "") }).append("\n")
    }
}

/** What happened today, for a diary draft. */
suspend fun eveningFacts(db: MandatDb, session: Session): String {
    val okId = db.hiddenOk(session)
    val named = db.namesHidden(session)
    val ok = { id: Long?, text: String -> okId(id) && !(id == null && named(text)) }
    val today = Dates.today()
    fun closedToday(ms: Long?) = ms != null && Dates.dayOf(ms) == today
    val events = db.events().observeRange(today, today).first().filter { ok(it.matterId, it.title + " " + it.outcome) }
    val dl = db.deadlines().observeAll().first().filter { ok(it.matterId, it.title) && TaskStatus.of(it.status) == TaskStatus.DONE && closedToday(it.closedAt) }
    val dg = db.delegations().observeAll().first().filter { ok(it.matterId, it.what) && (closedToday(it.closedAt) || closedToday(it.createdAt)) }
    val pr = db.promises().observeAll().first().filter { ok(it.matterId, it.what + " " + it.toWhom) && (closedToday(it.closedAt) || closedToday(it.createdAt)) }
    val moved = db.matters().observeAll().first().filter { okId(it.id) && Dates.dayOf(it.updatedAt) == today }
    return buildString {
        append("День ").append(factDate(today)).append(".\n")
        if (events.isNotEmpty()) append("Події: ").append(events.joinToString("; ") { it.title + (it.outcome.takeIf { o -> o.isNotBlank() }?.let { o -> " — $o" } ?: "") }).append("\n")
        if (dl.isNotEmpty()) append("Виконані строки: ").append(dl.joinToString("; ") { it.title }).append("\n")
        if (dg.isNotEmpty()) append("Доручення (дано чи виконано): ").append(dg.joinToString("; ") { it.what }).append("\n")
        if (pr.isNotEmpty()) append("Обіцянки (дано чи виконано): ").append(pr.joinToString("; ") { it.what }).append("\n")
        if (moved.isNotEmpty()) append("Справи, що рухались: ").append(moved.joinToString("; ") { it.title }).append("\n")
    }
}

/** One matter's story: card, people with stances, deadlines, delegations, promises, last events. */
suspend fun matterFacts(db: MandatDb, session: Session, matterId: Long): String {
    if (!db.hiddenOk(session)(matterId)) return ""
    val m = db.matters().get(matterId) ?: return ""
    val names = db.people().all().associate { it.id to it.name }
    val people = db.people().observeForMatter(matterId).first()
    val dl = db.deadlines().observeForMatter(matterId).first()
    val dg = db.delegations().observeForMatter(matterId).first()
    val pr = db.promises().observeForMatter(matterId).first()
    val ev = db.events().observeForMatter(matterId).first().sortedByDescending { it.day }.take(6)
    return buildString {
        append("Справа: ").append(m.title).append("\n")
        m.roleId?.let { db.roleLines()[it] }?.let { append("Роль: ").append(it).append("\n") }
        if (m.goal.isNotBlank()) append("Мета: ").append(m.goal).append("\n")
        if (m.nextStep.isNotBlank()) append("Записаний наступний крок: ").append(m.nextStep).append("\n")
        if (m.description.isNotBlank()) append("Опис: ").append(m.description.take(800)).append("\n")
        append("У роботі днів: ").append((Dates.today() - Dates.dayOf(m.createdAt)).coerceAtLeast(0)).append("\n")
        if (people.isNotEmpty()) append("Люди: ").append(people.joinToString("; ") { it.name + (it.stance?.let { s -> " ($s)" } ?: "") }).append("\n")
        if (dl.isNotEmpty()) append("Строки: ").append(dl.joinToString("; ") { it.title + " " + factDate(it.day) + " " + it.status }).append("\n")
        if (dg.isNotEmpty()) append("Доручення: ").append(dg.joinToString("; ") { it.what + (it.personId?.let { p -> " — " + names[p] } ?: "") + " " + it.status }).append("\n")
        if (pr.isNotEmpty()) append("Обіцянки: ").append(pr.joinToString("; ") { it.what + " " + it.status }).append("\n")
        if (ev.isNotEmpty()) append("Останні події: ").append(ev.joinToString("; ") { factDate(it.day) + " " + it.title + (it.outcome.takeIf { o -> o.isNotBlank() }?.let { o -> " — $o" } ?: "") }).append("\n")
    }
}

/** An attack or critique and the matter around it, for a draft rebuttal. */
suspend fun attackFacts(db: MandatDb, session: Session, infoId: Long): String {
    val ok = db.hiddenOk(session)
    val named = db.namesHidden(session)
    val item = db.info().observeAll().first().firstOrNull { it.id == infoId && ok(it.matterId) && !(it.matterId == null && named(it.title + " " + it.source + " " + it.notes)) } ?: return ""
    return buildString {
        append("Що сталося: ").append(item.title).append("\n")
        if (item.source.isNotBlank()) append("Джерело: ").append(item.source).append("\n")
        if (item.notes.isNotBlank()) append("Нотатки й факти: ").append(item.notes.take(1500)).append("\n")
        item.matterId?.let { append(matterFacts(db, session, it)) }
    }
}

/** The last seven days in numbers and titles, for a summary of the week. */
suspend fun weekFacts(db: MandatDb, session: Session): String {
    val ok = db.hiddenOk(session)
    val today = Dates.today()
    val span = ua.mandat.data.Span(today - 6, today)
    val matters = db.matters().observeAll().first().filter { ok(it.id) }
    val moved = matters.filter { MatterStatus.of(it.status) == MatterStatus.ACTIVE && Dates.dayOf(it.updatedAt) in span }
    val done = matters.filter { MatterStatus.of(it.status) == MatterStatus.DONE && Dates.dayOf(it.updatedAt) in span }
    val stuck = matters.filter { ua.mandat.data.isStale(it, today) }
    val named = db.namesHidden(session)
    val due = db.deadlines().observeAll().first().filter { ok(it.matterId) && !(it.matterId == null && named(it.title + " " + it.basis)) && it.day in span }
    return buildString {
        append("Тиждень ").append(factDate(span.from)).append(" – ").append(factDate(span.to)).append("\n")
        if (moved.isNotEmpty()) append("Рухались: ").append(moved.take(12).joinToString("; ") { it.title + (it.nextStep.takeIf { n -> n.isNotBlank() }?.let { n -> " (далі: $n)" } ?: "") }).append("\n")
        if (done.isNotEmpty()) append("Завершено: ").append(done.joinToString("; ") { it.title }).append("\n")
        if (stuck.isNotEmpty()) append("Без руху понад місяць: ").append(stuck.take(10).joinToString("; ") { it.title }).append("\n")
        if (due.isNotEmpty()) append("Строки тижня: ").append(due.take(12).joinToString("; ") { it.title + " — " + it.status }).append("\n")
    }
}

/** Records that best match a question, found by word stems; the model answers only from these. */
suspend fun askFacts(db: MandatDb, session: Session, question: String): Pair<String, List<ua.mandat.data.SearchRow>> {
    val ok = db.hiddenOk(session)
    val stems = Regex("[\\p{L}\\p{N}'’ʼ]{3,}").findAll(question.lowercase()).map { it.value.take(5) }
        .filter { it !in STOP }.distinct().toList()
    if (stems.isEmpty()) return "" to emptyList()
    // Diary days, people and communities tied to something hidden stay out too, as on every screen
    val rows = visibleRows(db.search().everything().first().filter { ok(it.matterId) }, session.hidden.value)
    val ranked = rows.map { r -> r to stems.count { r.haystack.lowercase().contains(it) } }.filter { it.second > 0 }
        .sortedByDescending { it.second }.take(25).map { it.first }
    val facts = buildString {
        append("Питання: ").append(question).append("\n\nЗаписи:\n")
        ranked.forEachIndexed { i, r -> append(i + 1).append(". [").append(r.kind).append("] ").append(r.haystack.replace('\n', ' ').take(300)).append("\n") }
    }
    return facts to ranked
}

/** Common question words that say nothing about the topic. */
private val STOP = setOf("що", "хто", "коли", "де", "скіль", "які", "який", "яка", "чи", "як", "чому", "мені", "мене", "моїх", "мої", "мій", "моя",
    "було", "буде", "були", "цього", "цей", "ця", "для", "про", "від", "якщо", "щодо", "всі", "усі", "весь")

/** Before a meeting: the event as it stands in the sheet, its matter, and each person taking part. */
suspend fun meetingFacts(db: MandatDb, session: Session, title: String, day: Long, startMin: Int?, matterId: Long?, people: Set<Long>, notes: String): String {
    val ok = db.hiddenOk(session)
    if (!ok(matterId)) return ""
    val named = db.namesHidden(session)
    // An event not tied to a matter but naming a hidden one stays out while it is hidden
    if (matterId == null && named(title + " " + notes)) return ""
    val persons = db.people().all().filter { it.id in people }
    val open = { s: String -> TaskStatus.of(s) == TaskStatus.OPEN }
    return buildString {
        append("Подія: ").append(title).append(", ").append(factDate(day)).append(startMin?.let { " " + timeText(it) } ?: "").append("\n")
        if (notes.isNotBlank() && !named(notes)) append("Нотатки до події: ").append(notes.take(800)).append("\n")
        matterId?.let { append(matterFacts(db, session, it)) }
        persons.forEach { p ->
            append("\nУчасник: ").append(p.name)
            listOf(p.jobTitle, p.organization).filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { append(" (").append(it.joinToString(", ")).append(")") }
            append("\n")
            matterId?.let { m -> db.people().observeForMatter(m).first().firstOrNull { it.personId == p.id }?.stance?.let { append("  позиція у справі: ").append(it).append("\n") } }
            val promised = db.promises().observeForPerson(p.id).first().filter { ok(it.matterId) && open(it.status) && !(it.matterId == null && named(it.what)) }
            if (promised.isNotEmpty()) append("  ви обіцяли: ").append(promised.take(4).joinToString("; ") { it.what }).append("\n")
            val asked = db.delegations().observeForPerson(p.id).first().filter { ok(it.matterId) && open(it.status) && !(it.matterId == null && named(it.what)) }
            if (asked.isNotEmpty()) append("  чекаєте від людини: ").append(asked.take(4).joinToString("; ") { it.what }).append("\n")
        }
    }
}

/** Promises in danger: open, of high risk, or public and due within two weeks. */
suspend fun riskFacts(db: MandatDb, session: Session): String {
    val ok = db.hiddenOk(session)
    val named = db.namesHidden(session)
    val today = Dates.today()
    val names = db.people().all().associate { it.id to it.name }
    val risky = db.promises().observeAll().first().filter { ok(it.matterId) && !(it.matterId == null && named(it.what + " " + it.toWhom + " " + it.statusNote)) && isRisky(it, today) }
        .sortedWith(compareBy({ it.due == null }, { it.due })).take(10)
    if (risky.isEmpty()) return ""
    return buildString {
        append("Сьогодні ").append(factDate(today)).append(".\nОбіцянки під загрозою:\n")
        risky.forEach { p ->
            append("- ").append(p.what)
            val whom = p.personId?.let(names::get) ?: p.toWhom.takeIf { it.isNotBlank() }
            if (whom != null) append("; кому: ").append(whom)
            p.due?.let { append("; строк: ").append(factDate(it)).append(if (it < today) " (прострочено)" else "") }
            append("; публічність: ").append(p.level.name.lowercase())
            if (p.risk == 3) append("; ризик високий")
            if (p.statusNote.isNotBlank()) append("; стан: ").append(p.statusNote.take(200))
            append("\n")
        }
    }
}

/** The last 30 days of time by role against the wished shares, and what is in focus, for next week's plan. */
suspend fun planFacts(db: MandatDb, session: Session): String {
    val ok = db.hiddenOk(session)
    val today = Dates.today()
    val span = ua.mandat.data.Span(today - 29, today)
    val matters = db.matters().observeAll().first().filter { ok(it.id) }
    val events = db.events().observeAll().first().filter { ok(it.matterId) }
    val entries = db.time().observeAll().first().filter { ok(it.matterId) }
    val byRole = ua.mandat.data.minutesByRole(span, events, entries, matters.associate { it.id to it.roleId })
    val total = byRole.values.sum().coerceAtLeast(1)
    val shares = db.time().observeShares().first().associate { it.key.removePrefix(ua.mandat.data.TIME_SHARE_PREFIX).toLongOrNull() to (it.value.toIntOrNull() ?: 0) }
    val roles = db.roles().observeAll().first()
    val focus = matters.filter { it.attention == "FOCUS" && MatterStatus.of(it.status) == MatterStatus.ACTIVE }
    return buildString {
        append("Час за 30 днів за ролями (факт → бажано):\n")
        roles.forEach { r ->
            val pct = Math.round((byRole[r.id] ?: 0) * 100f / total)
            append("- ").append(r.name).append(": ").append(pct).append(" %").append(shares[r.id]?.let { " → $it %" } ?: "").append("\n")
        }
        byRole[0L]?.let { append("- без ролі: ").append(Math.round(it * 100f / total)).append(" %\n") }
        if (focus.isNotEmpty()) append("У фокусі: ").append(focus.joinToString("; ") { m -> m.title + (m.roleId?.let { id -> roles.firstOrNull { it.id == id }?.name }?.let { " ($it)" } ?: "") }).append("\n")
        val stale = matters.filter { ua.mandat.data.isStale(it, today) && it.attention != "SLEEPING" }
        if (stale.isNotEmpty()) append("Без руху понад місяць: ").append(stale.take(8).joinToString("; ") { it.title }).append("\n")
    }
}

/** A delegation to remind someone of: what, by when, what it is for. */
suspend fun reminderFacts(db: MandatDb, session: Session, delegationId: Long): String {
    val d = db.delegations().get(delegationId) ?: return ""
    if (!db.hiddenOk(session)(d.matterId)) return ""
    if (d.matterId == null && db.namesHidden(session)(d.what + " " + d.purpose)) return ""
    val name = d.personId?.let { db.people().get(it)?.name }
    return reminderText(d.what, d.due, name, d.purpose)
}

/** The same facts from what is in the delegation sheet now. */
fun reminderText(what: String, due: Long?, name: String?, purpose: String): String = buildString {
    append("Доручення: ").append(what).append("\n")
    if (name != null) append("Кому: ").append(name).append("\n")
    due?.let { append("Строк: ").append(factDate(it)).append(if (it < Dates.today()) " (минув)" else "").append("\n") }
    if (purpose.isNotBlank()) append("Навіщо: ").append(purpose.take(300)).append("\n")
}

/** The titles of the matters hidden now, kept current; for lists that must not offer what names them. */
fun hiddenTitles(@Suppress("UNUSED_PARAMETER") db: MandatDb, session: Session): kotlinx.coroutines.flow.Flow<ua.mandat.data.HiddenNames> =
    session.hidden.map { it.names }

/** True when free text names one of the hidden titles (also in other word forms). */
fun namesAny(text: String, titles: ua.mandat.data.HiddenNames): Boolean = titles.named(text)
