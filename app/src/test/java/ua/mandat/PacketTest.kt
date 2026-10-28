package ua.mandat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ua.mandat.data.Matter
import ua.mandat.data.Packet
import ua.mandat.data.Person

class PacketTest {
    private fun person(id: Long, name: String, org: String = "") = Person(id = id, name = name, organization = org, jobTitle = "", contact = "", notes = "секрет", teamRole = null, createdAt = 0, updatedAt = 0)
    private fun matter(id: Long, title: String) = Matter(id = id, title = title, type = "OTHER", roleId = null, attention = "FOCUS", nextStep = "", goal = "", description = "", status = "ACTIVE", createdAt = 0, updatedAt = 0)

    @Test fun roundTripKeepsRecordsAndDropsPrivateNotes() {
        val p = Packet.P(listOf(matter(1, "Справа А")), listOf(person(10, "Людина Б", "Орг")), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyMap())
        val back = Packet.decode(Packet.encode(p))!!
        assertEquals("Справа А", back.matters.single().title)
        assertEquals("", back.people.single().notes)
        assertNull(Packet.decode("{\"format\":99}"))
    }

    @Test fun matchesTheSamePeopleAndMatters() {
        val p = Packet.P(listOf(matter(1, "  справа а "), matter(2, "Нова")), listOf(person(10, "Людина Б", "Орг"), person(11, "Людина В", "Інша")),
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyMap())
        val plan = Packet.plan(p, listOf(person(100, "людина б"), person(101, "Людина В", "Не та")), listOf(matter(50, "Справа А")))
        assertEquals(100L, plan.personMap[10])
        assertNull(plan.personMap[11])
        assertEquals(50L, plan.matterMap[1])
        assertEquals(1, plan.newMatters)
    }
}
