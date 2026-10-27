package ua.mandat

import org.junit.Assert.assertEquals
import org.junit.Test
import ua.mandat.ai.LocalAi

class AiTest {
    @Test fun readsItemsAndSkipsUnknownKinds() {
        val raw = """```json
{"items":[{"kind":"delegation","text":"Підготувати довідку","person":"Людина А","due":"2026-11-02"},
{"kind":"weather","text":"дощ"},{"kind":"note","text":""},{"kind":"promise","text":"Надіслати відповідь"}]}
```"""
        val found = LocalAi.parseFound(raw)
        assertEquals(listOf("delegation", "promise"), found.map { it.kind })
        assertEquals("Людина А", found[0].person)
        assertEquals("2026-11-02", found[0].due)
    }

    @Test fun readsACutOffAnswer() {
        val raw = """{"items":[{"kind":"deadline","text":"Звіт","due":"2026-12-01"},{"kind":"matter","text":"Нова спр"""
        assertEquals(listOf("deadline"), LocalAi.parseFound(raw).map { it.kind })
    }

    @Test fun sortedNotesKeepTheirNumbers() {
        val raw = """{"items":[{"i":1,"kind":"deadline","text":"Звіт","due":"2026-12-01"},{"i":7,"kind":"note","text":"поза межами"},
{"i":1,"kind":"note","text":"повтор"},{"i":0,"kind":"weather","text":"дощ"},{"i":0,"kind":"promise","text":"Відповісти"}]}"""
        val sorted = LocalAi.parseSorted(raw, 3)
        assertEquals(listOf(1, 0), sorted.map { it.first })
        assertEquals(listOf("deadline", "promise"), sorted.map { it.second.kind })
    }

    @Test fun citesOnlyRecordsThatWereGiven() {
        assertEquals(listOf(1, 0), ua.mandat.ui.citedIndexes("Так [2], а також [1] і [2]; ще [9] і [0].", 3))
    }
}
