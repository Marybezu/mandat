package ua.mandat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.mandat.data.Graph

class GraphTest {
    @Test fun linksMattersAndPeopleAndFitsTheSquare() {
        val g = Graph.build(listOf(1L to "Справа 1", 2L to "Справа 2"), mapOf(10L to "Людина А", 11L to "Людина Б"),
            listOf(1L to 10L, 2L to 10L, 2L to 11L, 3L to 11L))
        assertEquals(4, g.nodes.size)
        assertEquals(3, g.edges.size)
        assertEquals(2, g.nodes.first { it.key == "p10" }.degree)
        assertTrue(g.nodes.all { it.x in 0f..1f && it.y in 0f..1f })
        // The same data gives the same picture
        val again = Graph.build(listOf(1L to "Справа 1", 2L to "Справа 2"), mapOf(10L to "Людина А", 11L to "Людина Б"),
            listOf(1L to 10L, 2L to 10L, 2L to 11L, 3L to 11L))
        assertEquals(g.nodes.map { it.x }, again.nodes.map { it.x })
    }

    @Test fun keepsPeopleWhoConnectMattersWhenLarge() {
        val matters = (1L..5L).map { it to "С$it" }
        val people = (10L..20L).associateWith { "Л$it" }
        val links = listOf(1L to 10L, 2L to 10L) + (11L..20L).map { 3L to it }
        val g = Graph.build(matters, people, links, maxNodes = 8)
        assertEquals(listOf("p10"), g.nodes.filter { !it.isMatter }.map { it.key })
    }
}
