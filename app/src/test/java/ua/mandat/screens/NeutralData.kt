package ua.mandat.screens

import ua.mandat.data.Attention
import ua.mandat.data.Deadline
import ua.mandat.data.DeadlineKind
import ua.mandat.data.Delegation
import ua.mandat.data.InboxItem
import ua.mandat.data.MandatDb
import ua.mandat.data.Matter
import ua.mandat.data.MatterPerson
import ua.mandat.data.MatterStatus
import ua.mandat.data.MatterType
import ua.mandat.data.Person
import ua.mandat.data.PersonCategory
import ua.mandat.data.Promise
import ua.mandat.data.Review
import ua.mandat.data.Role
import ua.mandat.data.SETTING_ROLES_ASKED
import ua.mandat.data.Setting
import ua.mandat.data.TaskStatus
import ua.mandat.data.TeamRole
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import java.time.ZoneId

/**
 * Neutral, made-up test data for the screen checks: plain names like "Справа 1" and
 * "Людина А", nothing about any real person. Dates are counted from today.
 */
object NeutralData {
    private val today = LocalDate.now().toEpochDay()
    private fun ms(daysFromToday: Long) =
        LocalDate.ofEpochDay(today + daysFromToday).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    suspend fun fill(db: MandatDb) {
        db.settings().put(Setting(SETTING_ROLES_ASKED, "1"))
        db.settings().put(Setting(ua.mandat.data.SETTING_TOPICS, ua.mandat.data.encodeTopics(listOf(
            ua.mandat.data.Topic("Мета", ua.mandat.data.keysFor("мета")),
            ua.mandat.data.Topic("Громада", ua.mandat.data.keysFor("громада")),
        ))))
        val roles = listOf("Комітет А — член комітету", "Підкомітет Б — голова", "Робоча група В — керівник", "Округ Г")
            .mapIndexed { i, n -> db.roles().insert(Role(name = n, position = i + 1)) }

        val types = MatterType.entries
        val matters = (1..16).map { i ->
            val type = types[i % types.size]
            val attention = when {
                i <= 5 -> Attention.FOCUS
                i <= 13 -> Attention.BACKGROUND
                else -> Attention.SLEEPING
            }
            db.matters().insert(
                Matter(
                    title = "Тестова справа $i",
                    type = type.name,
                    roleId = roles[i % roles.size],
                    attention = attention.name,
                    nextStep = if (i % 5 == 0) "" else "Наступний крок для справи $i",
                    goal = "Мета справи $i",
                    description = "",
                    status = MatterStatus.ACTIVE.name,
                    createdAt = ms(-200L + i * 7),
                    updatedAt = ms(if (i % 4 == 0) -45L else -i.toLong()),
                ),
            )
        }
        db.matters().insert(
            Matter(title = "Завершена справа", type = MatterType.BILL.name, roleId = roles[0], attention = Attention.BACKGROUND.name,
                nextStep = "", goal = "", description = "", status = MatterStatus.DONE.name, createdAt = ms(-300), updatedAt = ms(-3)),
        )

        val letters = listOf("А", "Б", "В", "Г", "Ґ", "Д", "Е", "Є", "Ж", "З", "И", "І", "Ї", "Й", "К", "Л")
        val team = listOf(TeamRole.OFFICE_HEAD, TeamRole.ASSISTANT, TeamRole.ASSISTANT, TeamRole.INTERN, TeamRole.LAWYER)
        val people = letters.mapIndexed { i, l ->
            val inTeam = i < team.size
            db.people().insert(
                Person(
                    name = "Людина $l",
                    organization = if (inTeam) "Офіс" else "Організація $l",
                    jobTitle = if (inTeam) "" else "Посада $l",
                    contact = "", notes = "",
                    teamRole = if (inTeam) team[i].name else null,
                    createdAt = ms(-100), updatedAt = ms(-100),
                    category = if (inTeam) null else PersonCategory.entries[i % PersonCategory.entries.size].name,
                    influence = if (inTeam || i % 7 == 0) null else 1 + i % 3,
                    stance = if (inTeam) null else listOf("ALLY", "UNDECIDED", "OPPONENT", "NEUTRAL")[i % 4],
                    isGroup = i == 12,
                    reportsToId = null,
                    lastContact = if (i % 2 == 0) today - i * 5 else null,
                ),
            )
        }
        // Many more people, to see folded groups and the alphabet index
        (1..60).forEach { i ->
            db.people().insert(
                Person(name = "${listOf("Особа", "Учасник", "Мешканка", "Експерт")[i % 4]} $i", organization = "Громада ${i % 7}", jobTitle = "",
                    contact = "", notes = "", teamRole = null, createdAt = ms(-50), updatedAt = ms(-50),
                    category = PersonCategory.entries[i % PersonCategory.entries.size].name, influence = 1 + i % 3),
            )
        }
        val stances = listOf("ALLY", "UNDECIDED", "OPPONENT", "NEUTRAL", null)
        matters.take(8).forEachIndexed { i, m ->
            people.drop(5).take(4).forEachIndexed { j, p ->
                db.people().link(MatterPerson(m, p, stances[(i + j) % stances.size], ""))
            }
        }

        (1..14).forEach { i ->
            val done = i > 10
            db.delegations().insert(
                Delegation(
                    what = "Тестове доручення $i", personId = people[i % 5], matterId = matters[i % matters.size],
                    due = today + (i - 4), status = if (done) TaskStatus.DONE.name else TaskStatus.OPEN.name,
                    createdAt = ms(-20L - i), closedAt = if (done) ms(-i.toLong()) else null,
                    purpose = "Тестова мета доручення $i", doneMeans = "Тестовий результат $i",
                    freedom = ua.mandat.data.Freedom.entries[i % 4].name, checkIn = if (i % 3 == 0) today + i % 5 else null,
                    returned = i == 7,
                ),
            )
        }
        val promiseStates = listOf(TaskStatus.OPEN, TaskStatus.DONE, TaskStatus.DONE, TaskStatus.FAILED, TaskStatus.CANCELLED, TaskStatus.OPEN)
        (1..18).forEach { i ->
            val st = promiseStates[i % promiseStates.size]
            db.promises().insert(
                Promise(
                    what = "Тестова обіцянка $i", personId = if (i % 3 == 0) people[6 + i % 8] else null,
                    toWhom = if (i % 3 == 0) "" else "Мешканці громади $i", isPublic = i % 3 != 0,
                    matterId = matters[i % matters.size], due = if (st == TaskStatus.OPEN) today + (i - 6) else null,
                    status = st.name, createdAt = ms(-i * 90L), closedAt = if (st == TaskStatus.OPEN) null else ms(-i * 30L),
                    source = "Тестове джерело", statusNote = if (st == TaskStatus.FAILED || st == TaskStatus.CANCELLED) "Тестове пояснення" else "",
                    publicity = if (i % 3 != 0) "PUBLIC" else listOf("CIRCLE", "PERSONAL", "SELF")[i % 3],
                    origin = ua.mandat.data.PromiseOrigin.entries[i % 4].name, risk = 1 + i % 3,
                ),
            )
        }
        (1..10).forEach { i ->
            val done = i > 7
            db.deadlines().insert(
                Deadline(
                    title = "Тестовий строк $i", day = today + (i * 2 - 6) - (if (done) 10 else 0),
                    kind = if (i % 3 == 0) DeadlineKind.HARD.name else DeadlineKind.SOFT.name,
                    basis = if (i % 3 == 0) "Тестова підстава" else "", matterId = matters[i % matters.size], remindBefore = 1,
                    status = if (done) TaskStatus.DONE.name else TaskStatus.OPEN.name, createdAt = ms(-30),
                    closedAt = if (done) ms((i * 2 - 17).toLong()) else null,
                ),
            )
        }
        // Old overdue things, for the sorting-out screen
        (1..4).forEach { i ->
            db.deadlines().insert(
                Deadline(title = "Давній тестовий строк $i", day = today - 40 - i * 15, kind = DeadlineKind.SOFT.name, basis = "",
                    matterId = matters[i], remindBefore = 1, status = TaskStatus.OPEN.name, createdAt = ms(-200), closedAt = null),
            )
        }
        (1..3).forEach { i ->
            db.delegations().insert(
                Delegation(what = "Давнє тестове доручення $i", personId = people[i], matterId = matters[i + 2], due = today - 35 - i * 20,
                    status = TaskStatus.OPEN.name, createdAt = ms(-150), closedAt = null),
            )
        }
        // Calendar: meetings, sessions, a trip, receptions; some with people
        val kinds = ua.mandat.data.EventKind.entries
        (-10..20).filter { it % 2 == 0 || it in 0..2 }.forEachIndexed { n, d ->
            val kind = kinds[n % kinds.size]
            val id = db.events().insert(
                ua.mandat.data.Event(
                    title = "Тестова подія ${n + 1}", day = today + d,
                    endDay = if (kind == ua.mandat.data.EventKind.TRIP) today + d + 2 else null,
                    startMin = if (kind == ua.mandat.data.EventKind.TRIP) null else (9 + n % 8) * 60 + (n % 2) * 30,
                    endMin = if (kind == ua.mandat.data.EventKind.TRIP) null else (10 + n % 8) * 60 + (n % 2) * 30,
                    kind = kind.name, place = if (n % 3 == 0) "Тестове місце ${n % 4}" else "",
                    matterId = if (n % 2 == 0) matters[n % matters.size] else null,
                    outcome = if (d < 0 && n % 2 == 0) "Тестовий підсумок ${n + 1}" else "",
                    remind = n % 4 == 0, createdAt = ms(-30), updatedAt = ms(-30),
                ),
            )
            if (n % 3 != 2) db.events().setPeople(id, listOf(people[5 + n % 8], people[n % 5]))
        }
        // Address book: plain made-up entries in three groups
        db.contacts().insertAll((1..140).map { i ->
            val name = "${listOf("Абонент", "Контакт", "Знайомий", "Колега", "Дописувач")[i % 5]} ${letters[i % letters.size]}$i"
            ua.mandat.data.Contact(name = name, phones = "+38000000${(1000 + i)}", organization = if (i % 3 == 0) "Організація ${i % 9}" else "",
                label = listOf("Список 1", "Список 2", "", "Список 3")[i % 4], sortKey = ua.mandat.data.sortKeyOf(name), createdAt = ms(-10))
        })
        // Media and information, key messages, alliances
        ua.mandat.data.InfoKind.entries.forEachIndexed { k, kind ->
            (1..3).forEach { i ->
                db.info().insert(
                    ua.mandat.data.InfoItem(
                        kind = kind.name, title = "Тестовий запис: ${kind.name.lowercase()} $i", day = today - i * 3 - k,
                        source = "Тестове джерело $i", personId = if (i % 2 == 0) people[6 + i] else null, matterId = matters[i + k],
                        reach = 1 + i % 3, stage = if (kind == ua.mandat.data.InfoKind.ATTACK) ua.mandat.data.AttackStage.entries[i].name else null,
                        decision = if (kind == ua.mandat.data.InfoKind.ATTACK && i > 1) ua.mandat.data.AttackDecision.entries[i].name else null,
                        checks = if (i == 1) "SOURCE,FACTS" else "", closed = i == 3, createdAt = ms(-5), updatedAt = ms(-5),
                    ),
                )
            }
        }
        (1..3).forEach { i ->
            db.info().insertMessage(ua.mandat.data.KeyMessage(text = "Тестовий меседж $i", proof = "Тестовий факт і джерело $i", audience = "Тестова аудиторія",
                matterId = matters[i], createdAt = ms(-3), updatedAt = ms(-3)))
        }
        ua.mandat.data.AllianceStatus.entries.forEachIndexed { k, st ->
            (1..2).forEach { i ->
                db.info().insertAlliance(ua.mandat.data.Alliance(withWhom = "Тестова група ${k * 2 + i}", about = "Тестове питання ${k * 2 + i}",
                    personId = people[7 + i], matterId = matters[k + i], untilDay = today + 5 * i + k * 20, gives = "Тестовий внесок", gets = "Тестова підтримка",
                    status = st.name, createdAt = ms(-40), updatedAt = ms(-4)))
            }
        }
        (1..40).forEach { i ->
            val id = db.reviews().insert(Review(day = today - i, progress = if (i % 6 == 0) "" else "Тестовий запис вечора $i", createdAt = ms(-i.toLong()),
                energy = if (i % 4 == 0) null else 1 + i % 5, mood = if (i % 5 == 0) null else 1 + (i * 2) % 5))
            if (i % 3 == 0) db.reviews().link(ua.mandat.data.ReviewLink(id, "matter", matters[i % matters.size]))
            if (i % 4 == 0) db.reviews().link(ua.mandat.data.ReviewLink(id, "person", people[i % people.size]))
        }
        // District: made-up communities with linked matters, visits, promises and people
        db.settings().put(Setting(ua.mandat.data.SETTING_DISTRICT, "1"))
        val allEvents = db.events().observeAll().first()
        val allPromises = db.promises().observeAll().first()
        (1..6).forEach { i ->
            val cid = db.communities().insert(ua.mandat.data.Community(name = "Тестова громада $i", kind = ua.mandat.data.CommunityKind.entries[i % 5].name,
                population = 500 * i, head = "Тестовий староста $i", createdAt = ms(-100), updatedAt = ms(-100)))
            db.communities().link(ua.mandat.data.CommunityLink(cid, "matter", matters[i]))
            if (i % 2 == 0) db.communities().link(ua.mandat.data.CommunityLink(cid, "promise", allPromises[i].id))
            db.communities().link(ua.mandat.data.CommunityLink(cid, "person", people[5 + i]))
            if (i < 5) {
                val eid = db.events().insert(ua.mandat.data.Event(title = "Тестовий прийом $i", day = today - i * 25, kind = "RECEPTION", place = "Тестова громада $i",
                    createdAt = ms(-30), updatedAt = ms(-30)))
                db.communities().link(ua.mandat.data.CommunityLink(cid, "event", eid))
            }
            if (allEvents.size > i) db.communities().link(ua.mandat.data.CommunityLink(cid, "event", allEvents[i].id))
        }
        // Budget: made-up amounts over a year; charity; one compensation pending; organised aid
        val inCats = ua.mandat.data.IncomeCategory.entries
        val outCats = ua.mandat.data.ExpenseCategory.entries
        (0..360 step 4).forEachIndexed { n, back ->
            val income = n % 6 == 0
            val cat = if (income) inCats[n % inCats.size].name else outCats[n % outCats.size].name
            val amount = (if (income) 2_000_000L else 50_000L) + n * 1_000L
            db.money().insert(ua.mandat.data.MoneyEntry(day = today - back, kind = if (income) "INCOME" else "EXPENSE", category = cat,
                amount = amount, currency = "UAH", uah = amount, note = if (n % 3 == 0) "Тестова примітка" else "",
                charityKind = if (cat == "CHARITY") ua.mandat.data.CharityKind.entries[n % 5].name else null,
                awaitingRefund = cat == "TRIPS" && back < 40, createdAt = ms(-back.toLong()), updatedAt = ms(-back.toLong())))
        }
        db.money().insert(ua.mandat.data.MoneyEntry(day = today - 2, kind = "EXPENSE", category = "TRIPS", amount = 30_000, currency = "EUR", uah = 1_350_000,
            note = "Тестова поїздка", awaitingRefund = true, createdAt = ms(-2), updatedAt = ms(-2)))
        db.settings().put(Setting(ua.mandat.data.SETTING_LIMIT_PREFIX + "HOME", "1500000"))
        db.settings().put(Setting(ua.mandat.data.SETTING_LIMIT_PREFIX + "OFFICE", "300000"))
        (1..6).forEach { i ->
            db.money().insertAid(ua.mandat.data.AidRecord(matterId = matters[1], day = today - i * 3, collected = i % 2 == 1, amount = 100_000L * i, currency = "UAH",
                uah = 100_000L * i, what = "Тестова допомога $i", party = "Тестова сторона $i", createdAt = ms(-i.toLong())))
        }
        (1..3).forEach { i -> db.inbox().insert(InboxItem(text = "Тестовий запис у вхідних $i", createdAt = ms(-i.toLong()))) }
    }
}
