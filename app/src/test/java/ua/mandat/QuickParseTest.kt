package ua.mandat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ua.mandat.data.MatterTitle
import ua.mandat.data.MatterType
import ua.mandat.data.NoteKind
import ua.mandat.data.Person
import ua.mandat.data.TeamRole
import ua.mandat.data.findPerson
import ua.mandat.data.parseDue
import ua.mandat.data.suggest
import java.time.LocalDate

class QuickParseTest {
    private val today = LocalDate.of(2026, 10, 26).toEpochDay() // a Monday
    private fun day(m: Int, d: Int, y: Int = 2026) = LocalDate.of(y, m, d).toEpochDay()

    private fun person(id: Long, name: String, team: TeamRole? = null) =
        Person(id = id, name = name, organization = "", jobTitle = "", contact = "", notes = "",
            teamRole = team?.name, createdAt = 0, updatedAt = 0)

    private val people = listOf(
        person(1, "Тарас Вигаданий", TeamRole.ASSISTANT),
        person(2, "Оксана Тестова", TeamRole.OFFICE_HEAD),
        person(3, "Марія Петрівна Приклад"),
        person(4, "Олег Перший"),
        person(5, "Олег Другий"),
    )
    private val matters = listOf(MatterTitle(10, "Реформа теплопостачання"), MatterTitle(11, "Міст у селі Тестове"))

    @Test fun dates() {
        assertEquals(today, parseDue("зробити сьогодні", today))
        assertEquals(today + 1, parseDue("Завтра зателефонувати", today))
        assertEquals(day(10, 30), parseDue("до п’ятниці", today))
        assertEquals(day(10, 27), parseDue("у вівторок", today))
        assertEquals(day(11, 2), parseDue("в понеділок", today)) // never today itself
        assertEquals(day(11, 25), parseDue("до 25.11", today))
        assertEquals(day(3, 1, 2027), parseDue("1.03.2027", today))
        assertEquals(day(11, 25), parseDue("25 листопада", today))
        assertEquals(day(1, 5, 2027), parseDue("5 січня", today)) // already passed this year
        assertEquals(today + 3, parseDue("через 3 дні", today))
        assertEquals(today + 14, parseDue("через 2 тижні", today))
        assertEquals(today + 7, parseDue("через тиждень", today))
        assertEquals(day(11, 2), parseDue("на наступному тижні", today))
        assertEquals(day(10, 31), parseDue("до кінця місяця", today))
        assertNull(parseDue("просто нотатка без дати", today))
    }

    @Test fun peopleInAnyCase() {
        assertEquals(1L, findPerson("Тарасу таблицю", people))
        assertEquals(1L, findPerson("попросити Тарасові", people))
        assertEquals(2L, findPerson("з Оксаною", people))
        assertEquals(3L, findPerson("відповісти Марії Петрівні", people))
        assertNull(findPerson("Олегу зателефонувати", people)) // two people named Oleh: do not guess
    }

    @Test fun kinds() {
        val d = suggest("Тарасу таблицю підписів до п'ятниці", people, matters, today)
        assertEquals(NoteKind.DELEGATION, d.kind)
        assertEquals(1L, d.personId)
        assertEquals(day(10, 30), d.due)

        assertEquals(NoteKind.PROMISE, suggest("Пообіцяла Марії Петрівні відповідь", people, matters, today).kind)
        assertEquals(NoteKind.DEADLINE, suggest("Строк подання 25.11", people, matters, today).kind)

        val m = suggest("Новий законопроєкт про лічильники", people, matters, today)
        assertEquals(NoteKind.MATTER, m.kind)
        assertEquals(MatterType.BILL, m.matterType)

        assertEquals(10L, suggest("доручити Оксані план реформи теплопостачання", people, matters, today).matterId)
        assertEquals(NoteKind.NOTE, suggest("подумати про це", people, matters, today).kind)
    }
}
