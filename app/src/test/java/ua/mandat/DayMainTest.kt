package ua.mandat

import org.junit.Assert.assertEquals
import org.junit.Test
import ua.mandat.data.DayMain

class DayMainTest {
    @Test fun keepsOnlyTodayAndKnownReferences() {
        val m = DayMain(100, listOf("matter:3", "deadline:7"), setOf("deadline:7"))
        assertEquals(m, DayMain.decode(m.encode(), 100))
        // Another day starts empty
        assertEquals(emptyList<String>(), DayMain.decode(m.encode(), 101).items)
        // Unknown kinds, repeats and a fourth item are dropped
        assertEquals(listOf("matter:1", "promise:2", "delegation:3"), DayMain.decode("100;matter:1,x:5,promise:2,matter:1,delegation:3,deadline:4;", 100).items)
    }
}
