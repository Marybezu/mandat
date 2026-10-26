package ua.mandat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.mandat.data.Topic
import ua.mandat.data.decodeTopics
import ua.mandat.data.encodeTopics
import ua.mandat.data.keysFor
import ua.mandat.data.matches
import ua.mandat.data.suggestTopics

class TopicsTest {
    @Test fun roundTrip() {
        val list = listOf(Topic("Тепло", listOf("тепл", "котельн")), Topic("Дороги", listOf("дорог")))
        assertEquals(list, decodeTopics(encodeTopics(list)))
        assertEquals(emptyList<Topic>(), decodeTopics(null))
    }

    @Test fun matchingInAnyForm() {
        val t = Topic("Тепло", keysFor("тепло, котельня, опалення"))
        assertTrue(matches("Модульна котельня до зими", t))
        assertTrue(matches("Реформа теплопостачання", t))
        assertTrue(matches("Опалювальний сезон", t) || matches("питання опалення", t))
        assertFalse(matches("Ремонт дороги", t))
    }

    @Test fun suggestsFrequentWords() {
        val texts = listOf("Ремонт дороги в селі", "Дорога до школи", "Кошторис дороги", "Укриття в ліцеї", "Укриття в школі", "Укриття: другий вхід")
        val s = suggestTopics(texts, existing = emptyList()).map { it.first }
        assertTrue(s.any { it.startsWith("дорог") })
        assertTrue(s.any { it.startsWith("укритт") })
        // Already a topic: not suggested again
        assertFalse(suggestTopics(texts, listOf(Topic("Дороги", listOf("дорог")))).any { it.first.startsWith("дорог") })
    }
}
