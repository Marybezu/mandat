package ua.mandat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ua.mandat.data.MAX_NOTE_LENGTH
import ua.mandat.data.cleanInboxText

class CleanTextTest {
    @Test fun emptyTextIsRefused() {
        assertNull(cleanInboxText(""))
        assertNull(cleanInboxText("   \n\t "))
    }

    @Test fun textIsTrimmed() {
        assertEquals("Подзвонити щодо засідання", cleanInboxText("  Подзвонити щодо засідання \n"))
    }

    @Test fun veryLongTextIsLimited() {
        assertEquals(MAX_NOTE_LENGTH, cleanInboxText("а".repeat(MAX_NOTE_LENGTH + 50))!!.length)
    }
}
