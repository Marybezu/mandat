package ua.mandat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.mandat.backup.BackupCrypto
import ua.mandat.data.CommunityLink
import ua.mandat.data.Event
import ua.mandat.data.HiddenNames
import ua.mandat.data.Hiding
import ua.mandat.data.IcsReader
import ua.mandat.data.IdRef
import ua.mandat.data.LinkKind
import ua.mandat.data.Obsidian
import ua.mandat.data.Review
import ua.mandat.data.ReviewLink
import ua.mandat.data.WipingBuffer
import ua.mandat.data.cleanEvent
import java.time.ZoneId

/** What the stabilisation fixed: hiding, inert notes, bounded events, password bytes, buffers. */
class StabilityTest {

    @Test fun hiddenTitlesAreFoundInOtherWordForms() {
        val names = HiddenNames(listOf("Ремонт школи", "Тестова справа №12"))
        assertTrue(names.named("Щодо ремонту школи в селі"))
        assertTrue(names.named("РЕМОНТ ШКОЛА"))
        assertTrue(names.named("тестової справи №12"))
        assertFalse(names.named("Ремонт дороги"))
        assertFalse(names.named("Тестова справа №13"))
        assertFalse(HiddenNames.NONE.named("будь-що"))
        // Short linking words are left out, four-letter words match their other forms
        assertTrue(HiddenNames(listOf("Ремонт для школи")).named("ремонт школи"))
        assertTrue(HiddenNames(listOf("Чиста вода")).named("про чисту воду"))
        // Words shorter than three letters say nothing alone
        assertTrue(HiddenNames(listOf("Ра")).isEmpty)
    }

    @Test fun peopleKnownOnlyThroughHiddenMattersAreHidden() {
        val hidden = setOf(1L)
        val refs = listOf(
            IdRef(10, 1), // only the hidden matter
            IdRef(11, 1), IdRef(11, 2), // also a visible one
            IdRef(12, null), IdRef(12, 1), // a task without a matter keeps them visible
            IdRef(13, 2),
        )
        assertEquals(setOf(10L), Hiding.people(hidden, refs))
        assertEquals(emptySet<Long>(), Hiding.people(emptySet(), refs))
    }

    @Test fun diaryDaysTiedToHiddenThingsAreHidden() {
        val names = HiddenNames(listOf("Таємна справа"))
        val reviews = listOf(
            Review(id = 1, day = 1, progress = "звичайний день", createdAt = 0),
            Review(id = 2, day = 2, progress = "про таємну справу", createdAt = 0),
            Review(id = 3, day = 3, progress = "зустріч", createdAt = 0),
            Review(id = 4, day = 4, progress = "розмова", createdAt = 0),
        )
        val links = listOf(ReviewLink(3, "matter", 1), ReviewLink(4, "person", 10), ReviewLink(1, "matter", 2))
        assertEquals(setOf(2L, 3L, 4L), Hiding.reviews(setOf(1L), setOf(10L), names, reviews, links))
    }

    @Test fun communitiesTiedOnlyToHiddenThingsAreHidden() {
        val links = listOf(
            CommunityLink(1, LinkKind.MATTER, 1), CommunityLink(1, LinkKind.EVENT, 5),
            CommunityLink(2, LinkKind.MATTER, 1), CommunityLink(2, LinkKind.MATTER, 2),
            CommunityLink(3, LinkKind.PERSON, 10),
        )
        assertEquals(setOf(1L, 3L), Hiding.communities(setOf(1L), setOf(10L), links, mapOf(5L to 1L), emptyMap()))
    }

    @Test fun obsidianNotesCannotLoadOrRun() {
        val note = "---\ntitle: \"<b>x</b>\"\ntags: [mandat/matter]\n---\n\n**Ціль:** ![](https://example.org/a.png) <iframe src=x>\n```dataviewjs\ndv.x()\n```\n~~~js\n1\n~~~\n`\$= dv.y()`"
        val out = Obsidian.neutralize(note)
        // The front matter is left as it was (quoted values)
        assertTrue(out.startsWith("---\ntitle: \"<b>x</b>\"\ntags: [mandat/matter]\n---\n"))
        val body = out.substringAfter("---\n\n")
        assertFalse(body.contains("!["))
        assertFalse(body.contains("<iframe"))
        assertFalse(Regex("(?m)^```").containsMatchIn(body))
        assertFalse(Regex("(?m)^~~~").containsMatchIn(body))
        assertFalse(Regex("(?<!\\\\)`").containsMatchIn(body))
        // Mandat's own markup stays
        assertTrue(body.contains("**Ціль:**"))
    }

    @Test fun eventsOutsideReasonAreNotSaved() {
        val ok = Event(title = "Подія", day = 20_000, kind = "OTHER", createdAt = 0, updatedAt = 0)
        assertEquals(20_000L, cleanEvent(ok)?.day)
        assertNull(cleanEvent(ok.copy(day = 3_000_000)))
        // A span of thousands of years keeps only its first day
        assertNull(cleanEvent(ok.copy(endDay = 2_900_000))?.endDay)
        assertEquals(20_005L, cleanEvent(ok.copy(endDay = 20_005))?.endDay)
    }

    @Test fun calendarFileWithEndlessEventIsBounded() {
        val ics = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nSUMMARY:Довга\r\nDTSTART;VALUE=DATE:20261001\r\nDTEND;VALUE=DATE:99991231\r\nEND:VEVENT\r\n" +
            "BEGIN:VEVENT\r\nSUMMARY:Давня\r\nDTSTART;VALUE=DATE:15000101\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val items = IcsReader.read(ics, ZoneId.of("Europe/Kyiv"))
        assertEquals(1, items.size)
        assertNull(items.single().endDay)
    }

    @Test fun passwordBytesAreTheSameAsBefore() {
        val fast = BackupCrypto.KdfParams(memoryKiB = 1024, iterations = 1, parallelism = 1)
        val salt = ByteArray(16) { it.toByte() }
        // The text and the characters give the same key; a decomposed "й" is the same password as the composed one
        assertArrayEquals(BackupCrypto.deriveKey("пароль-тест-123", salt, fast), BackupCrypto.deriveKey("пароль-тест-123".toCharArray(), salt, fast))
        assertArrayEquals(BackupCrypto.deriveKey("ключ-й", salt, fast), BackupCrypto.deriveKey("ключ-й".toCharArray(), salt, fast))
        assertArrayEquals("тест-é".toByteArray(Charsets.UTF_8), BackupCrypto.utf8Nfc("тест-é".toCharArray()))
    }

    @Test fun wipingBufferKeepsWhatWasWrittenAndClears() {
        val b = WipingBuffer(16)
        val data = ByteArray(1000) { (it % 251).toByte() }
        b.write(data, 0, 600)
        b.write(data, 600, 400)
        assertArrayEquals(data, b.toByteArray())
        b.wipe()
        assertEquals(0, b.size)
        assertEquals(0, b.toByteArray().size)
    }
}
