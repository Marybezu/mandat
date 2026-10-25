package ua.mandat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ua.mandat.data.Attention
import ua.mandat.data.MatterStatus
import ua.mandat.data.MatterType
import ua.mandat.data.cleanMatter
import ua.mandat.data.matterFromNote

class MattersTest {
    @Test fun firstLineOfANoteBecomesTheTitle() {
        val m = matterFromNote("Вигадана реформа\nПідготувати зустріч\nЗібрати аргументи", 1L)
        assertEquals("Вигадана реформа", m.title)
        assertEquals("Підготувати зустріч\nЗібрати аргументи", m.description)
        assertEquals(MatterType.OTHER.name, m.type)
        assertEquals(Attention.BACKGROUND.name, m.attention)
        assertEquals(MatterStatus.ACTIVE.name, m.status)
    }

    @Test fun veryLongFirstLineIsShortenedAndKeptInNotes() {
        val long = "а".repeat(200)
        val m = matterFromNote(long, 1L)
        assertEquals(118, m.title.length)
        assertEquals(long, m.description)
    }

    @Test fun emptyTitleIsRefused() {
        assertNull(cleanMatter(matterFromNote("   ", 1L)))
    }

    @Test fun fieldsAreTrimmed() {
        val m = cleanMatter(matterFromNote("  Назва  ", 1L).copy(nextStep = "  Подзвонити  "))!!
        assertEquals("Назва", m.title)
        assertEquals("Подзвонити", m.nextStep)
    }

    @Test fun unknownStoredValuesFallBackSafely() {
        assertEquals(MatterType.OTHER, MatterType.of("SOMETHING_NEW"))
        assertEquals(Attention.BACKGROUND, Attention.of(""))
        assertEquals(MatterStatus.ACTIVE, MatterStatus.of("?"))
    }
}

class RoleNameTest {
    @Test fun positionAndBody() {
        assertEquals("Секретар — Комітет", ua.mandat.ui.composeRoleName("Комітет", "Секретар", "  "))
    }

    @Test fun bodyWithoutPositionAndWithName() {
        assertEquals("Регіон «Вигаданий»", ua.mandat.ui.composeRoleName("Регіон", null, "Вигаданий"))
    }
}
