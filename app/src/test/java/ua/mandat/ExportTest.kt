package ua.mandat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.mandat.data.ContactCsv
import ua.mandat.data.Event
import ua.mandat.data.Export
import ua.mandat.data.IcsReader
import ua.mandat.data.VCard
import ua.mandat.data.notesFromText
import java.time.ZoneId

class ExportTest {
    private val kyiv = ZoneId.of("Europe/Kyiv")

    @Test fun vcardRoundTrip() {
        val text = Export.vcard(listOf(Export.VContact("Тестова Особа, молодша", listOf("+380000000001"), listOf("t@example.org"), "Організація; відділ",
            "Посада", "Перший рядок\nдругий", listOf("Список"))))
        val back = VCard.read(text.reader().buffered()).contacts.single()
        assertEquals("Тестова Особа, молодша", back.name)
        assertEquals("+380000000001", back.phones)
        assertEquals("Перший рядок\nдругий", back.notes)
    }

    @Test fun icsRoundTrip() {
        val e = Event(id = 1, title = "Тестова зустріч", day = 20_000, startMin = 600, endMin = 690, kind = "MEETING", place = "Тестове місце",
            notes = "Нотатка", createdAt = 1, updatedAt = 1)
        val allDay = Event(id = 2, title = "Тестова поїздка", day = 20_010, endDay = 20_012, kind = "TRIP", createdAt = 1, updatedAt = 1)
        val text = Export.ics(listOf(e, allDay), { it }, kyiv, now = 0)
        val items = IcsReader.read(text, kyiv)
        assertEquals(2, items.size)
        assertEquals(20_000L, items[0].day)
        assertEquals(600, items[0].startMin)
        assertEquals(690, items[0].endMin)
        assertEquals("Тестове місце", items[0].place)
        assertEquals(null, items[1].startMin)
        assertEquals(20_012L, items[1].endDay)
    }

    @Test fun csvIsSafe() {
        assertEquals("'=1+1", Export.csvField("=1+1"))
        assertEquals("\"a,b\"", Export.csvField("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", Export.csvField("say \"hi\""))
        assertTrue(Export.csv(listOf("a"), listOf(listOf("б"))).startsWith("﻿"))
    }

    @Test fun contactsFromCsv() {
        val list = ContactCsv.contacts("Ім'я;Телефон;Організація;Група\r\nТестова Особа;+380000000002;Тест;Список 1\r\n\"Особа; друга\";;;\r\n")
        assertEquals(2, list.size)
        assertEquals("+380000000002", list[0].phones)
        assertEquals("Список 1", list[0].label)
        assertEquals("Особа; друга", list[1].name)
    }

    @Test fun negativeAmountStaysNumber() {
        assertEquals("-12.00", Export.csvField("-12.00"))
        assertEquals("'-1+2", Export.csvField("-1+2"))
    }

    @Test fun emojiNotSplitByFolding() {
        val name = "Тест " + "🙂".repeat(40)
        val text = Export.vcard(listOf(Export.VContact(name, emptyList(), emptyList(), "", "", "", emptyList())))
        assertEquals(name, VCard.read(text.reader().buffered()).contacts.single().name)
        // No lone carriage return inside a value can start a new property
        val crText = Export.vcard(listOf(Export.VContact("А", emptyList(), emptyList(), "", "", "x\rTEL:1", emptyList())))
        assertTrue(crText.lines().none { it.startsWith("TEL:1") })
    }

    @Test fun markdownLinksEscaped() {
        assertEquals("\\!\\[\\](x) \\<b\\>", Export.md("![](x) <b>"))
    }

    @Test fun markdownLineStartsStayText() {
        assertEquals("\\- not a list\n1\\. nor this\n  \\+ indented", Export.md("- not a list\n1. nor this\n  + indented"))
    }

    @Test fun exportMark() {
        assertTrue(ua.mandat.backup.BackupCrypto.startsWithExportMark(ua.mandat.backup.BackupCrypto.EXPORT_MARK + byteArrayOf(1, 2)))
        assertTrue(!ua.mandat.backup.BackupCrypto.startsWithExportMark("{\"schema\":1}".toByteArray()))
    }

    @Test fun longContinuationIsLinearAndBounded() {
        val text = "BEGIN:VCALENDAR\nBEGIN:VEVENT\nDTSTART:20260101T100000Z\nSUMMARY:a\n" + " x\n".repeat(300_000) + "END:VEVENT\n"
        val started = System.nanoTime()
        val items = IcsReader.read(text, kyiv)
        assertTrue(System.nanoTime() - started < 5_000_000_000L)
        assertEquals(1, items.size)
        assertTrue(items[0].title.length <= 500)
    }

    @Test fun csvColumnsCapped() {
        assertTrue(ContactCsv.rows(";".repeat(10_000)).all { it.size <= ContactCsv.MAX_COLUMNS + 1 })
    }

    @Test fun notes() {
        assertEquals(listOf("перша", "друга\nз продовженням"), notesFromText("перша\n\n\nдруга\nз продовженням\n"))
    }
}
