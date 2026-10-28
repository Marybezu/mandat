package ua.mandat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.mandat.backup.AutoBackup
import ua.mandat.data.Delegation
import ua.mandat.data.HiddenNames
import ua.mandat.data.Matter
import ua.mandat.data.Packet
import ua.mandat.data.Person
import ua.mandat.ui.BackupDue
import ua.mandat.ui.Hidden
import ua.mandat.ui.backupDue
import ua.mandat.ui.person
import ua.mandat.ui.tripSince

/** Fixes from the review before handing Mandat to others to try. */
class AuditFixesTest {
    private val day = 86_400_000L

    @Test fun backupReminderComesWhenItShould() {
        val now = 100 * day
        assertNull(backupDue(hasData = false, manual = null, autoOk = null, autoFail = null, autoOn = false, now = now))
        assertEquals(BackupDue.NEVER, backupDue(true, null, null, null, false, now))
        assertNull(backupDue(true, now - 3 * day, null, null, false, now))
        assertEquals(BackupDue.OLD, backupDue(true, now - 20 * day, null, null, false, now))
        // A fresh automatic copy counts; one failing since the last good copy says so
        assertNull(backupDue(true, null, now - day, null, true, now))
        assertEquals(BackupDue.AUTO_STOPPED, backupDue(true, null, now - 3 * day, now - day, true, now))
        // Automatic copies turned off do not count as a copy
        assertEquals(BackupDue.NEVER, backupDue(true, null, now - day, null, false, now))
    }

    @Test fun automaticCopiesKeepWeeksToo() {
        val now = 400 * day + day / 2
        // One copy a day for sixty days
        val files = (0 until 60).map { "f$it" to now - it * day }
        val kept = AutoBackup.trimmed(files, keepNewest = "f0").map { it.first }
        assertEquals(AutoBackup.KEEP + AutoBackup.KEEP_WEEKS, kept.size)
        assertTrue((0 until AutoBackup.KEEP).all { "f$it" in kept })
        // The oldest kept one is about a month back, not a week
        val oldest = kept.maxOf { it.removePrefix("f").toInt() }
        assertTrue(oldest >= 3 * 7)
    }

    @Test fun packetNumbersItsOwnRecords() {
        val m = Matter(id = 57, title = "Справа", type = "OTHER", roleId = 9, attention = "FOCUS", nextStep = "", goal = "", description = "", status = "ACTIVE", createdAt = 0, updatedAt = 0)
        val p = Person(id = 4021, name = "Людина", organization = "", jobTitle = "", contact = "", notes = "", teamRole = null, createdAt = 0, updatedAt = 0)
        val d = Delegation(what = "Зробити", personId = 4021, matterId = 57, due = null, status = "OPEN", createdAt = 0, closedAt = null)
        val json = Packet.encode(Packet.P(listOf(m), listOf(p), emptyList(), listOf(d), emptyList(), emptyList(), emptyList(), emptyList(), mapOf(9L to "Роль")))
        assertFalse(json.contains("57"))
        assertFalse(json.contains("4021"))
        val back = Packet.decode(json)!!
        assertEquals(1L, back.matters.single().id)
        assertEquals(1L, back.delegations.single().matterId)
        assertEquals(1L, back.delegations.single().personId)
    }

    @Test fun changingOnlyTheStatusCountsAsAChange() {
        val pr = ua.mandat.data.Promise(id = 5, what = "Обіцянка", personId = null, toWhom = "", isPublic = false, matterId = null, due = null, status = "OPEN", createdAt = 1, closedAt = null)
        val d = ua.mandat.ui.PromiseDraft(pr, isNew = false)
        assertFalse(d.changed())
        d.status = ua.mandat.data.TaskStatus.DONE
        assertTrue(d.changed())
    }

    @Test fun writtenInTripModeStaysInView() {
        val since = 1_000L
        val h = Hidden(setOf(1L), emptySet(), emptySet(), emptySet(), HiddenNames(listOf("Таємна справа")), since)
        assertTrue(h.itemAt(null, 500L, "про таємну справу"))
        assertFalse(h.itemAt(null, 1_500L, "про таємну справу"))
        // Tied to the hidden matter: hidden whenever it was written
        assertTrue(h.itemAt(1L, 1_500L, "будь-що"))
        // A person's card naming the matter
        val p = Person(id = 3, name = "Особа", organization = "", jobTitle = "", contact = "", notes = "щодо таємної справи", teamRole = null, createdAt = 0, updatedAt = 10)
        assertTrue(h.person(p))
        assertFalse(h.person(p.copy(updatedAt = 2_000)))
        // A time in the future (a clock once set ahead) never exempts a record
        assertTrue(h.itemAt(null, Long.MAX_VALUE - 1, "про таємну справу"))
        assertEquals(Long.MAX_VALUE, tripSince(null))
        assertEquals(42L, tripSince("42"))
    }
}
