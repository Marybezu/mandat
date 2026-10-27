package ua.mandat.data

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A plain Word document (.docx) from text with light marks: "# " and "## " become headings,
 * "- " a bulleted line, everything else a paragraph. Built by hand (the format is a ZIP of XML
 * files), so no library is needed; Word, LibreOffice and Google Docs open it.
 */
object Docx {
    fun build(text: String): ByteArray {
        val body = StringBuilder()
        for (raw in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            when {
                raw.startsWith("# ") -> para(body, raw.removePrefix("# "), "Heading1")
                raw.startsWith("## ") -> para(body, raw.removePrefix("## "), "Heading2")
                raw.startsWith("- ") -> para(body, "• " + raw.removePrefix("- "), "ListLine")
                else -> para(body, raw, null)
            }
        }
        val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="850" w:bottom="1134" w:left="1701" w:header="709" w:footer="709" w:gutter="0"/></w:sectPr></w:body></w:document>"""
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, xml: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(xml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", CONTENT_TYPES)
            put("_rels/.rels", RELS)
            put("word/_rels/document.xml.rels", DOC_RELS)
            put("word/styles.xml", STYLES)
            put("word/document.xml", document)
        }
        return out.toByteArray()
    }

    private fun para(out: StringBuilder, text: String, style: String?) {
        out.append("<w:p>")
        if (style != null) out.append("<w:pPr><w:pStyle w:val=\"").append(style).append("\"/></w:pPr>")
        if (text.isNotEmpty()) out.append("<w:r><w:t xml:space=\"preserve\">").append(esc(text)).append("</w:t></w:r>")
        out.append("</w:p>")
    }

    /** XML escaping; characters XML does not allow at all (most control characters) are dropped. */
    internal fun esc(s: String): String {
        val b = StringBuilder(s.length)
        for (ch in s) {
            when {
                ch == '&' -> b.append("&amp;")
                ch == '<' -> b.append("&lt;")
                ch == '>' -> b.append("&gt;")
                ch == '"' -> b.append("&quot;")
                ch == '\t' -> b.append(' ')
                ch.code < 0x20 || ch == '￾' || ch == '￿' -> Unit
                else -> b.append(ch)
            }
        }
        return b.toString()
    }

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>"""

    private const val RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""

    private const val DOC_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>"""

    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Times New Roman" w:hAnsi="Times New Roman" w:cs="Times New Roman"/><w:sz w:val="28"/><w:lang w:val="uk-UA"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="120" w:line="276" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style><w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:pPr><w:keepNext/><w:spacing w:before="240" w:after="120"/><w:jc w:val="center"/><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:sz w:val="32"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:pPr><w:keepNext/><w:spacing w:before="200" w:after="80"/><w:outlineLvl w:val="1"/></w:pPr><w:rPr><w:b/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="ListLine"><w:name w:val="List Line"/><w:basedOn w:val="Normal"/><w:pPr><w:ind w:left="567" w:hanging="283"/><w:spacing w:after="60"/></w:pPr></w:style></w:styles>"""
}
