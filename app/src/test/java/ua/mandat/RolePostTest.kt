package ua.mandat

import org.junit.Assert.assertEquals
import org.junit.Test
import ua.mandat.data.RolePost

class RolePostTest {
    @Test fun guessesThePostFromTheName() {
        assertEquals(RolePost.LEAD, RolePost.guess("Голова — Комісія А"))
        assertEquals(RolePost.LEAD, RolePost.guess("Група Б — керівниця групи"))
        assertEquals(RolePost.DEPUTY, RolePost.guess("Заступниця голови — Фракція «Приклад»"))
        assertEquals(RolePost.MEMBER, RolePost.guess("Член — Комісія В"))
        assertEquals(RolePost.SECRETARY, RolePost.guess("Secretary — Committee"))
        assertEquals(null, RolePost.guess("Секретаріат ради"))
        assertEquals(null, RolePost.guess("Headquarters"))
        assertEquals(null, RolePost.guess("Фракція «Приклад»"))
    }
}
