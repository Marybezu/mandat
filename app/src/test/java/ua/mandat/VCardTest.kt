package ua.mandat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.mandat.data.Contact
import ua.mandat.data.Person
import ua.mandat.data.VCard
import ua.mandat.data.normalizePhone
import ua.mandat.data.phonesIn
import ua.mandat.data.planImport
import ua.mandat.data.sortKeyOf

class VCardTest {
    private fun read(text: String) = VCard.read(text.reader().buffered(), now = 1L)

    @Test fun readsVersion3WithFolding() {
        val r = read(
            "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Тестова Особа\r\nN:Особа;Тестова;;;\r\nTEL;TYPE=CELL:+380 00 000 00 01\r\n" +
                "TEL;TYPE=WORK:000-00-02\r\nEMAIL:test@example.org\r\nORG:Тестова організація;Відділ\r\nTITLE:Тестова посада\r\n" +
                "NOTE:Перший рядок\\nдругий\\, з комою\r\n  і продовження\r\nCATEGORIES:Список,myContacts\r\nEND:VCARD\r\n",
        )
        val c = r.contacts.single()
        assertEquals("Тестова Особа", c.name)
        assertEquals("+380 00 000 00 01\n000-00-02", c.phones)
        assertEquals("test@example.org", c.emails)
        assertEquals("Тестова організація, Відділ", c.organization)
        assertEquals("Тестова посада", c.jobTitle)
        assertEquals("Перший рядок\nдругий, з комою і продовження", c.notes)
        assertEquals(listOf("Список"), r.categories[0])
    }

    @Test fun readsVersion21QuotedPrintable() {
        // "Тест" in UTF-8, split over two lines with a soft break
        val r = read(
            "BEGIN:VCARD\nVERSION:2.1\nN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:;=D0=A2=D0=B5=\n=D1=81=D1=82;;;\n" +
                "TEL;CELL;PREF:0000000003\nEND:VCARD\n",
        )
        val c = r.contacts.single()
        assertEquals("Тест", c.name)
        assertEquals("0000000003", c.phones)
    }

    @Test fun skipsPhotosAndEmptyCards() {
        val photo = "PHOTO;ENCODING=b;TYPE=JPEG:" + "A".repeat(80) + "\n" + (1..500).joinToString("\n") { " " + "B".repeat(76) } + "\n"
        val r = read(
            "BEGIN:VCARD\nVERSION:3.0\nFN:З фото\n$photo" + "TEL:+380000000004\nEND:VCARD\n" +
                "BEGIN:VCARD\nVERSION:3.0\nEND:VCARD\n" +
                "BEGIN:VCARD\nVERSION:4.0\nTEL;VALUE=uri:tel:+380000000005\nEND:VCARD\n",
        )
        assertEquals(2, r.contacts.size)
        assertEquals("З фото", r.contacts[0].name)
        assertEquals("+380000000004", r.contacts[0].phones)
        // No name: the phone becomes the name
        assertEquals("+380000000005", r.contacts[1].name)
    }

    @Test fun readsThousands() {
        val text = buildString { repeat(6_000) { append("BEGIN:VCARD\nVERSION:3.0\nFN:Особа $it\nTEL:+38000${1000000 + it}\nEND:VCARD\n") } }
        assertEquals(6_000, read(text).contacts.size)
    }

    @Test fun phones() {
        assertEquals("+380001112233", normalizePhone("0001112233"))
        assertEquals("+380001112233", normalizePhone("+38 (000) 111-22-33"))
        assertEquals("+48123456789", normalizePhone("0048 123 456 789"))
        assertEquals(null, normalizePhone("12"))
        assertEquals(listOf("+380001112233"), phonesIn("тел. 000 111 22 33, ще раз +380001112233"))
    }

    @Test fun duplicatesAreSkipped() {
        fun c(name: String, phone: String = "") = Contact(name = name, phones = phone, sortKey = sortKeyOf(name), createdAt = 0)
        val book = listOf(c("Уже є", "+380000000001"))
        val people = listOf(Person(name = "Людина", organization = "", jobTitle = "", contact = "0000000002", notes = "", teamRole = null, createdAt = 0, updatedAt = 0))
        val plan = planImport(
            listOf(c("Інше ім'я", "000 000 00 01"), c("Хтось", "+380000000002"), c("Людина"), c("Нова", "+380000000009"), c("Нова двічі", "+380000000009")),
            book, people,
        )
        assertEquals(listOf("Нова"), plan.fresh.map { it.name })
        assertEquals(4, plan.duplicates)
        assertTrue(plan.fresh.all { it.sortKey == sortKeyOf(it.name) })
    }
}
