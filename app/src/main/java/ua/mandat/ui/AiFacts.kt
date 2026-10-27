package ua.mandat.ui

import kotlinx.coroutines.flow.first
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

/** What today holds: dated items due, focus matters, today's meetings and what was promised to those people. */
suspend fun todayFacts(db: MandatDb, session: Session): String {
    val ok = db.hiddenOk(session)
    val today = Dates.today()
    val names = db.people().all().associate { it.id to it.name }
    val dl = db.deadlines().observeOpen().first().filter { ok(it.matterId) && it.day <= today + 1 }
    val dg = db.delegations().observeOpen().first().filter { ok(it.matterId) && it.due != null && it.due <= today + 1 }
    val pr = db.promises().observeOpen().first().filter { ok(it.matterId) }
    val focus = db.matters().observeAll().first().filter { ok(it.id) && it.attention == "FOCUS" && MatterStatus.of(it.status) == MatterStatus.ACTIVE }
    val events = db.events().observeRange(today, today).first().filter { ok(it.matterId) }.sortedBy { it.startMin ?: -1 }
    return buildString {
        append("Сьогодні ").append(Dates.format(today)).append(".\n")
        if (events.isNotEmpty()) {
            append("Зустрічі сьогодні:\n")
            events.forEach { e ->
                val who = db.events().peopleOf(e.id).mapNotNull { names[it] }
                append("- ").append(e.startMin?.let { timeText(it) + " " } ?: "").append(e.title)
                if (who.isNotEmpty()) append(" (з: ").append(who.joinToString(", ")).append(")")
                append("\n")
                who.forEach { n ->
                    val promised = pr.filter { p -> p.personId != null && names[p.personId] == n }.take(3)
                    if (promised.isNotEmpty()) append("  ми обіцяли ").append(n).append(": ").append(promised.joinToString("; ") { it.what }).append("\n")
                }
            }
        }
        if (dl.isNotEmpty()) append("Строки (сьогодні, завтра чи прострочені): ").append(dl.take(10).joinToString("; ") { it.title + " — " + Dates.format(it.day) }).append("\n")
        if (dg.isNotEmpty()) append("Доручення до перевірки: ").append(dg.take(10).joinToString("; ") { it.what + (it.personId?.let { p -> " (" + names[p] + ")" } ?: "") }).append("\n")
        val duePr = pr.filter { it.due != null && it.due <= today + 1 }
        if (duePr.isNotEmpty()) append("Обіцянки зі строком: ").append(duePr.take(10).joinToString("; ") { it.what }).append("\n")
        if (focus.isNotEmpty()) append("У фокусі: ").append(focus.joinToString("; ") { it.title + (it.nextStep.takeIf { n -> n.isNotBlank() }?.let { n -> " (далі: $n)" } ?: "") }).append("\n")
    }
}

/** What happened today, for a diary draft. */
suspend fun eveningFacts(db: MandatDb, session: Session): String {
    val ok = db.hiddenOk(session)
    val today = Dates.today()
    fun closedToday(ms: Long?) = ms != null && Dates.dayOf(ms) == today
    val events = db.events().observeRange(today, today).first().filter { ok(it.matterId) }
    val dl = db.deadlines().observeAll().first().filter { ok(it.matterId) && TaskStatus.of(it.status) == TaskStatus.DONE && closedToday(it.closedAt) }
    val dg = db.delegations().observeAll().first().filter { ok(it.matterId) && (closedToday(it.closedAt) || closedToday(it.createdAt)) }
    val pr = db.promises().observeAll().first().filter { ok(it.matterId) && (closedToday(it.closedAt) || closedToday(it.createdAt)) }
    val moved = db.matters().observeAll().first().filter { ok(it.id) && Dates.dayOf(it.updatedAt) == today }
    return buildString {
        append("День ").append(Dates.format(today)).append(".\n")
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
        if (m.goal.isNotBlank()) append("Мета: ").append(m.goal).append("\n")
        if (m.nextStep.isNotBlank()) append("Записаний наступний крок: ").append(m.nextStep).append("\n")
        if (m.description.isNotBlank()) append("Опис: ").append(m.description.take(800)).append("\n")
        append("У роботі днів: ").append((Dates.today() - Dates.dayOf(m.createdAt)).coerceAtLeast(0)).append("\n")
        if (people.isNotEmpty()) append("Люди: ").append(people.joinToString("; ") { it.name + (it.stance?.let { s -> " ($s)" } ?: "") }).append("\n")
        if (dl.isNotEmpty()) append("Строки: ").append(dl.joinToString("; ") { it.title + " " + Dates.format(it.day) + " " + it.status }).append("\n")
        if (dg.isNotEmpty()) append("Доручення: ").append(dg.joinToString("; ") { it.what + (it.personId?.let { p -> " — " + names[p] } ?: "") + " " + it.status }).append("\n")
        if (pr.isNotEmpty()) append("Обіцянки: ").append(pr.joinToString("; ") { it.what + " " + it.status }).append("\n")
        if (ev.isNotEmpty()) append("Останні події: ").append(ev.joinToString("; ") { Dates.format(it.day) + " " + it.title + (it.outcome.takeIf { o -> o.isNotBlank() }?.let { o -> " — $o" } ?: "") }).append("\n")
    }
}

/** An attack or critique and the matter around it, for a draft rebuttal. */
suspend fun attackFacts(db: MandatDb, session: Session, infoId: Long): String {
    val ok = db.hiddenOk(session)
    val item = db.info().observeAll().first().firstOrNull { it.id == infoId && ok(it.matterId) } ?: return ""
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
    val due = db.deadlines().observeAll().first().filter { ok(it.matterId) && it.day in span }
    return buildString {
        append("Тиждень ").append(Dates.format(span.from)).append(" – ").append(Dates.format(span.to)).append("\n")
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
    val rows = db.search().everything().first().filter { ok(it.matterId) }
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
