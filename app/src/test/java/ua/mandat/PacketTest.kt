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
        val plan = Packet.plan(p, listOf(person(100, "людина б", "орг"), person(101, "Людина В", "Не та"), person(102, "Людина В")), listOf(matter(50, "Справа А")))
        // The same name and organisation: taken as the same person; otherwise the owner decides
        assertEquals(100L, plan.personDefault[10])
        assertNull(plan.personDefault[11])
        assertEquals(listOf(101L, 102L), plan.personCandidates[11]?.map { it.id })
        // A matter is never matched by itself
        assertEquals(listOf(50L), plan.matterCandidates[1]?.map { it.id })
        assertNull(plan.matterDefault[1])
    }

    @Test fun daysOutsideReasonDropTheRecord() {
        val json = """{"format":1,"deadlines":[{"title":"А","day":1000000000000,"status":"OPEN"},{"title":"Б","day":20000,"status":"DONE"}]}"""
        val p = Packet.decode(json)!!
        assertEquals(listOf("Б"), p.deadlines.map { it.title })
        assertEquals(true, p.deadlines.single().closedAt != null)
    }
}
