package ua.mandat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.mandat.data.Docx
import ua.mandat.data.speakingMinutes
import ua.mandat.data.wordCount
import java.util.zip.ZipInputStream

class TextsTest {
    @Test fun countsWords() {
        assertEquals(6, wordCount("# Заголовок\n- пункт один, і ще м'яко"))
        assertEquals(0, speakingMinutes(0))
        assertEquals(1, speakingMinutes(30))
        assertEquals(3, speakingMinutes(360))
    }

    @Test fun docxIsAZipWithEscapedText() {
        val bytes = Docx.build("# Тест <b> & \"лапки\"\n- пункт\nабзац\u0001")
        val names = ArrayList<String>()
        var doc = ""
        ZipInputStream(bytes.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                names += e.name
                if (e.name == "word/document.xml") doc = z.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertTrue(names.containsAll(listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/styles.xml")))
        assertTrue(doc.contains("&lt;b&gt; &amp; &quot;лапки&quot;"))
        assertTrue(doc.contains("Heading1"))
        assertTrue(!doc.contains("\u0001"))
    }
}
