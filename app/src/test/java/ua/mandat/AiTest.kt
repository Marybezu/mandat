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
}
