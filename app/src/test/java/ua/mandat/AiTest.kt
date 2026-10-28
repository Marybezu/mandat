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
{"i":1,"kind":"note","text":"повтор"},{"i":1,"kind":"note","text":"повтор"},{"i":0,"kind":"weather","text":"дощ"},{"i":0,"kind":"promise","text":"Відповісти"}]}"""
        val sorted = LocalAi.parseSorted(raw, 3)
        // Several things from one note are kept; numbers outside the list and unknown kinds are dropped
        assertEquals(listOf(1, 1, 0), sorted.map { it.first })
        assertEquals(listOf("deadline", "note", "promise"), sorted.map { it.second.kind })
    }

    @Test fun citesOnlyRecordsThatWereGiven() {
        assertEquals(listOf(1, 0), ua.mandat.ui.citedIndexes("Так [2], а також [1] і [2]; ще [9] і [0].", 3))
    }

    @Test fun tidyMakesAnswersPlain() {
        val raw = """Ось стислий підсумок тижня:

**Зрушило:**
* Завершено перший етап.
1. На що звернути увагу: Перше.
2. На що звернути увагу: Друге."""
        assertEquals("Зрушило:\n• Завершено перший етап.\n\nНа що звернути увагу:\n• Перше.\n• Друге.", LocalAi.tidy(raw))
        // A text that replaces the owner's own keeps its marks
        assertEquals("# Назва\n- пункт", LocalAi.tidy("# Назва\n- пункт", keepMarks = true))
    }

    @Test fun hintKeepsOnlyKnownNames() {
        val h = LocalAi.parseHint("""{"kind":"deadline","role":"Роль А","matter":"Вигадана"}""", listOf("Роль А"), listOf("Справа 1"))
        assertEquals("deadline", h?.kind)
        assertEquals("Роль А", h?.role)
        assertEquals(null, h?.matter)
    }

    @Test fun chunksCutAtParagraphs() {
        val text = "А".repeat(60) + "\n\n" + "Б".repeat(60)
        assertEquals(2, LocalAi.chunks(text, 100).size)
    }
}
