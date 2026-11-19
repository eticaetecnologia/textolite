package app.textolite

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.StaticLayout
import android.text.TextPaint
import java.io.ByteArrayOutputStream

/** Converte o Markdown em .md, .fodt e .pdf. */
object Export {

    private fun isTableRow(line: String) = line.trim().let { it.startsWith("|") && it.endsWith("|") && it.length > 1 }
    private fun isSeparator(line: String) = line.trim().replace("|", "").replace("-", "").replace(":", "").replace(" ", "").isEmpty()
    private fun cells(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split("|").map { plain(it.trim()) }

    /** Tira marcas de Markdown inline para formatos sem suporte a elas. */
    private fun plain(s: String): String =
        s.replace("**", "").replace("__", "").replace("~~", "").replace("`", "")

    private fun xml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    // ---------- .md ----------

    fun md(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    // ---------- .fodt (LibreOffice / OpenDocument) ----------

    fun fodt(markdown: String): ByteArray {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append(
            """<office:document xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" """ +
            """xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" """ +
            """xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" """ +
            """xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" """ +
            """xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" """ +
            """office:version="1.3" office:mimetype="application/vnd.oasis.opendocument.text">"""
        ).append('\n')
        sb.append("<office:automatic-styles>")
        sb.append("""<style:style style:name="Tbl" style:family="table"><style:table-properties style:width="17cm" table:align="margins"/></style:style>""")
        sb.append("""<style:style style:name="Cel" style:family="table-cell"><style:table-cell-properties fo:padding="0.1cm" fo:border="0.5pt solid #000000"/></style:style>""")
        sb.append("</office:automatic-styles>")
        sb.append("<office:body><office:text>")

        val lines = markdown.lines()
        var i = 0
        var tableCount = 0
        while (i < lines.size) {
            val line = lines[i]
            if (isTableRow(line)) {
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && isTableRow(lines[i])) {
                    if (!isSeparator(lines[i])) rows.add(cells(lines[i]))
                    i++
                }
                val cols = rows.maxOfOrNull { it.size } ?: 1
                tableCount++
                sb.append("""<table:table table:name="Tabela$tableCount" table:style-name="Tbl">""")
                sb.append("""<table:table-column table:number-columns-repeated="$cols"/>""")
                for (r in rows) {
                    sb.append("<table:table-row>")
                    for (c in 0 until cols) {
                        sb.append("""<table:table-cell table:style-name="Cel" office:value-type="string"><text:p>""")
                        sb.append(xml(r.getOrElse(c) { "" }))
                        sb.append("</text:p></table:table-cell>")
                    }
                    sb.append("</table:table-row>")
                }
                sb.append("</table:table>")
                continue
            }
            val t = line.trim()
            when {
                t.isEmpty() -> {}
                t.startsWith("#") -> {
                    val level = t.takeWhile { it == '#' }.length.coerceIn(1, 6)
                    sb.append("""<text:h text:outline-level="$level">""")
                        .append(xml(plain(t.dropWhile { it == '#' }.trim())))
                        .append("</text:h>")
                }
                t.startsWith("- ") || t.startsWith("* ") ->
                    sb.append("<text:p>• ").append(xml(plain(t.drop(2)))).append("</text:p>")
                else -> sb.append("<text:p>").append(xml(plain(t))).append("</text:p>")
            }
            i++
        }
        sb.append("</office:text></office:body></office:document>")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    // ---------- .pdf ----------

    fun pdf(markdown: String): ByteArray {
        val pageW = 595
        val pageH = 842
        val margin = 40
        val width = pageW - 2 * margin

        val body = TextPaint().apply { isAntiAlias = true; color = Color.BLACK; textSize = 11f }
        val mono = TextPaint(body).apply { typeface = Typeface.MONOSPACE; textSize = 9.5f }
        fun heading(size: Float) = TextPaint(body).apply { typeface = Typeface.DEFAULT_BOLD; textSize = size }

        val doc = PdfDocument()
        var pageNumber = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNumber).create())
        var y = margin.toFloat()

        fun newPage() {
            doc.finishPage(page)
            pageNumber++
            page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNumber).create())
            y = margin.toFloat()
        }

        fun draw(text: String, paint: TextPaint, spacing: Float) {
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width).build()
            if (y + layout.height > pageH - margin && y > margin) newPage()
            page.canvas.save()
            page.canvas.translate(margin.toFloat(), y)
            layout.draw(page.canvas)
            page.canvas.restore()
            y += layout.height + spacing
        }

        for (line in markdown.lines()) {
            val t = line.trim()
            when {
                t.isEmpty() -> y += 6f
                isTableRow(t) -> if (!isSeparator(t)) draw(cells(t).joinToString("  |  "), mono, 3f)
                t.startsWith("#") -> {
                    val level = t.takeWhile { it == '#' }.length
                    val size = when (level) { 1 -> 18f; 2 -> 15f; else -> 13f }
                    draw(plain(t.dropWhile { it == '#' }.trim()), heading(size), 6f)
                }
                t.startsWith("- ") || t.startsWith("* ") -> draw("• " + plain(t.drop(2)), body, 3f)
                else -> draw(plain(t), body, 4f)
            }
        }
        doc.finishPage(page)
        val out = ByteArrayOutputStream()
        doc.writeTo(out)
        doc.close()
        return out.toByteArray()
    }
}
