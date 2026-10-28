package ua.mandat

import org.junit.Assert.assertEquals
import org.junit.Test
import ua.mandat.data.Obsidian

class ObsidianTest {
    @Test fun namesAreSafeAndUnique() {
        assertEquals("Справа А Б", Obsidian.name("Справа: А/Б", "x"))
        assertEquals("x", Obsidian.name("  ##  ", "x"))
        assertEquals(mapOf(1 to "Справа", 2 to "Справа (2)", 3 to "Інша"), Obsidian.unique(listOf(1 to "Справа", 2 to "справа".replaceFirstChar { it.uppercase() }, 3 to "Інша")))
        assertEquals("\"Він сказав: \\\"так\\\"\"", Obsidian.yaml("Він сказав: \"так\""))
    }

    @Test fun numberedNamesNeverRepeat() {
        val u = Obsidian.unique(listOf(1 to "Звіт", 2 to "Звіт", 3 to "Звіт (2)"))
        assertEquals(3, u.values.map { it.lowercase() }.toSet().size)
        assertEquals("x", Obsidian.name("...", "x"))
        assertEquals("Сховане", Obsidian.name(".Сховане", "x"))
    }
}
