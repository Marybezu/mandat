package ua.mandat

import org.junit.Assert.assertEquals
import org.junit.Test
import ua.mandat.data.Words

class WordsTest {
    @Test fun formsCountTogetherAndFunctionWordsAreLeftOut() {
        val top = Words.top(listOf("Котельня для громади", "Нова котельня щодо громади", "Котельні і школа", "Школа"))
        assertEquals("котельня" to 3, top[0])
        assertEquals(listOf("громади", "школа"), top.drop(1).map { it.first })
    }

    @Test fun findsAMatterWithTheSameWords() {
        val others = listOf(ua.mandat.data.MatterTitle(1, "Ремонт дороги в селі Приклад"), ua.mandat.data.MatterTitle(2, "Котельня для школи"))
        assertEquals(listOf(1L), Words.similar("Дорога в селі Приклад: ремонт", others).map { it.id })
        assertEquals(emptyList<Long>(), Words.similar("Новий парк", others).map { it.id })
    }
}
