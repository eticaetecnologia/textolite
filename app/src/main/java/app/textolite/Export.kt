package app.textolite

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Locale

/** Converte as páginas em .md, .fodt e .pdf, só com o texto ou com a imagem original de cada página. */
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

    // ---------- Imagens ----------

    private class Img(val b64: String, val w: Int, val h: Int)

    private fun decode(file: File, maxSide: Int): Bitmap {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, b)
        var s = 1
        while (maxOf(b.outWidth, b.outHeight) / (s * 2) >= maxSide) s *= 2
        val o = BitmapFactory.Options().apply { inSampleSize = s }
        val bmp = BitmapFactory.decodeFile(file.path, o) ?: throw IOException("Imagem da página indisponível.")
        return scaleTo(bmp, maxSide)
    }

    private fun scaleTo(b: Bitmap, maxSide: Int): Bitmap {
        val s = maxSide.toFloat() / maxOf(b.width, b.height)
        if (s >= 1f) return b
        return Bitmap.createScaledBitmap(
            b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true
        )
    }

    private fun img(file: File, maxSide: Int, quality: Int): Img {
        val bmp = decode(file, maxSide)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return Img(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), bmp.width, bmp.height)
    }

    // ---------- .md ----------

    /** Com imagens, elas vão embutidas no próprio arquivo (nem todo editor as exibe). */
    fun md(pages: List<Page>, withImages: Boolean): ByteArray {
        val sb = StringBuilder()
        pages.forEachIndexed { i, p ->
            if (i > 0) sb.append("\n\n---\n\n")
            if (withImages) {
                val im = img(p.file, 1200, 70)
                sb.append("![Página ${i + 1}](data:image/jpeg;base64,${im.b64})\n\n")
            }
            sb.append(p.text.trim())
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    // ---------- .fodt (LibreOffice / OpenDocument) ----------

    private fun appendBody(sb: StringBuilder, markdown: String, tableCounter: IntArray) {
        val lines = markdown.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (isTableRow(line)) {
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && isTableRow(lines[i])) {
                    if (!isSeparator(lines[i])) rows.add(cells(lines[i]))
                    i++
                }
                val cols = rows.maxOfOrNull { it.size } ?: 1
                tableCounter[0]++
                sb.append("""<table:table table:name="Tabela${tableCounter[0]}" table:style-name="Tbl">""")
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
    }

    fun fodt(pages: List<Page>, withImages: Boolean): ByteArray {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append(
            """<office:document xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" """ +
            """xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" """ +
            """xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" """ +
            """xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" """ +
            """xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" """ +
            """xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0" """ +
            """xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" """ +
            """office:version="1.3" office:mimetype="application/vnd.oasis.opendocument.text">"""
        ).append('\n')
        sb.append("<office:automatic-styles>")
        sb.append("""<style:style style:name="Tbl" style:family="table"><style:table-properties style:width="17cm" table:align="margins"/></style:style>""")
        sb.append("""<style:style style:name="Cel" style:family="table-cell"><style:table-cell-properties fo:padding="0.1cm" fo:border="0.5pt solid #000000"/></style:style>""")
        sb.append("""<style:style style:name="PB" style:family="paragraph"><style:paragraph-properties fo:break-before="page"/></style:style>""")
        sb.append("</office:automatic-styles>")
        sb.append("<office:body><office:text>")

        val multi = pages.size > 1
        val tableCounter = intArrayOf(0)
        pages.forEachIndexed { i, p ->
            if (multi) {
                val style = if (i > 0) """ text:style-name="PB"""" else ""
                sb.append("<text:p$style>Página ${i + 1}</text:p>")
            }
            if (withImages) {
                val im = img(p.file, 1600, 80)
                val wCm = minOf(17.0, 22.0 * im.w / im.h)
                val hCm = wCm * im.h / im.w
                val w = String.format(Locale.US, "%.2f", wCm)
                val h = String.format(Locale.US, "%.2f", hCm)
                sb.append("<text:p><draw:frame draw:name=\"Imagem${i + 1}\" text:anchor-type=\"as-char\" svg:width=\"${w}cm\" svg:height=\"${h}cm\">")
                sb.append("<draw:image><office:binary-data>${im.b64}</office:binary-data></draw:image>")
                sb.append("</draw:frame></text:p>")
            }
            appendBody(sb, p.text, tableCounter)
        }
        sb.append("</office:text></office:body></office:document>")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    // ---------- .pdf ----------

    fun pdf(pages: List<Page>, withImages: Boolean): ByteArray {
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

        fun drawImage(file: File) {
            val bmp = decode(file, 1600)
            val boxW = (pageW - 2 * margin).toFloat()
            val boxH = (pageH - 2 * margin).toFloat()
            val s = minOf(boxW / bmp.width, boxH / bmp.height)
            val w = bmp.width * s
            val h = bmp.height * s
            val left = margin + (boxW - w) / 2
            val top = margin + (boxH - h) / 2
            page.canvas.drawBitmap(bmp, null, RectF(left, top, left + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG))
        }

        pages.forEachIndexed { index, p ->
            if (index > 0) newPage()
            if (withImages) {
                drawImage(p.file)
                newPage()
            }
            for (line in p.text.lines()) {
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
        }
        doc.finishPage(page)
        val out = ByteArrayOutputStream()
        doc.writeTo(out)
        doc.close()
        return out.toByteArray()
    }
}
