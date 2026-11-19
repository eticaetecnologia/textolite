package app.textolite

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Locale

/** Converte as páginas em .md, .fodt e .pdf, só com o texto ou com a imagem original de cada página. */
object Export {

    private fun xml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

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

    private val noteRef = Regex("\\[\\^([^\\]\\s]+)](?!:)")

    private fun textStyle(name: String): String {
        val f = name.drop(1)
        val sb = StringBuilder()
        sb.append("<style:style style:name=\"").append(name).append("\" style:family=\"text\"><style:text-properties")
        if ('b' in f) sb.append(" fo:font-weight=\"bold\"")
        if ('i' in f) sb.append(" fo:font-style=\"italic\"")
        if ('s' in f) sb.append(" style:text-line-through-style=\"solid\"")
        if ('c' in f) sb.append(" fo:font-family=\"Liberation Mono\"")
        sb.append("/></style:style>")
        return sb.toString()
    }

    /** Converte trechos formatados em XML do ODF. Notas de rodapé viram notas reais do LibreOffice. */
    private fun odf(
        runs: List<Run>,
        notes: Map<String, String>,
        styles: MutableSet<String>,
        noteCounter: IntArray,
        forceBold: Boolean = false,
        allowNotes: Boolean = true
    ): String {
        val sb = StringBuilder()
        for (r in runs) {
            if (r.note != null) {
                val body = notes[r.note]
                if (body == null || !allowNotes) {
                    sb.append(xml("[^" + r.note + "]"))
                } else {
                    noteCounter[0]++
                    sb.append("<text:note text:id=\"ftn").append(noteCounter[0])
                        .append("\" text:note-class=\"footnote\"><text:note-citation>")
                        .append(xml(r.note))
                        .append("</text:note-citation><text:note-body><text:p>")
                        .append(odf(Markdown.inline(body), notes, styles, noteCounter, allowNotes = false))
                        .append("</text:p></text:note-body></text:note>")
                }
                continue
            }
            val b = r.bold || forceBold
            val name = "T" + (if (b) "b" else "") + (if (r.italic) "i" else "") +
                (if (r.strike) "s" else "") + (if (r.code) "c" else "")
            if (name == "T") {
                sb.append(xml(r.text))
            } else {
                styles.add(name)
                sb.append("<text:span text:style-name=\"").append(name).append("\">")
                    .append(xml(r.text)).append("</text:span>")
            }
        }
        return sb.toString()
    }

    private fun appendBlock(
        sb: StringBuilder,
        block: Block,
        notes: Map<String, String>,
        styles: MutableSet<String>,
        noteCounter: IntArray,
        tableCounter: IntArray
    ) {
        fun runs(s: String, forceBold: Boolean = false) =
            odf(Markdown.inline(s), notes, styles, noteCounter, forceBold)

        when (block) {
            is Block.Heading ->
                sb.append("<text:h text:outline-level=\"").append(block.level).append("\">")
                    .append(runs(block.text)).append("</text:h>")
            is Block.Para -> sb.append("<text:p>").append(runs(block.text)).append("</text:p>")
            is Block.Item ->
                sb.append("<text:p text:style-name=\"L").append(block.level).append("\">")
                    .append(xml(block.marker)).append(" ").append(runs(block.text)).append("</text:p>")
            is Block.Quote ->
                sb.append("<text:p text:style-name=\"Q\">").append(runs(block.text)).append("</text:p>")
            is Block.Rule -> sb.append("<text:p text:style-name=\"HR\"/>")
            is Block.PageNo ->
                sb.append("<text:p text:style-name=\"PN\">p. ").append(xml(block.label)).append("</text:p>")
            is Block.Table -> {
                val cols = block.rows.maxOf { it.size }.coerceAtLeast(1)
                tableCounter[0]++
                sb.append("<table:table table:name=\"Tabela").append(tableCounter[0])
                    .append("\" table:style-name=\"Tbl\">")
                sb.append("<table:table-column table:number-columns-repeated=\"").append(cols).append("\"/>")
                block.rows.forEachIndexed { ri, row ->
                    sb.append("<table:table-row>")
                    for (c in 0 until cols) {
                        sb.append("<table:table-cell table:style-name=\"Cel\" office:value-type=\"string\"><text:p>")
                        sb.append(runs(row.getOrElse(c) { "" }, forceBold = block.header && ri == 0))
                        sb.append("</text:p></table:table-cell>")
                    }
                    sb.append("</table:table-row>")
                }
                sb.append("</table:table>")
            }
        }
    }

    fun fodt(pages: List<Page>, withImages: Boolean): ByteArray {
        val body = StringBuilder()
        val styles = mutableSetOf<String>()
        val noteCounter = intArrayOf(0)
        val tableCounter = intArrayOf(0)
        val multi = pages.size > 1

        pages.forEachIndexed { i, p ->
            if (multi) {
                val style = if (i > 0) " text:style-name=\"PB\"" else ""
                body.append("<text:p").append(style).append(">Página ").append(i + 1).append("</text:p>")
            }
            if (withImages) {
                val im = img(p.file, 1600, 80)
                val wCm = minOf(17.0, 22.0 * im.w / im.h)
                val hCm = wCm * im.h / im.w
                val w = String.format(Locale.US, "%.2f", wCm)
                val h = String.format(Locale.US, "%.2f", hCm)
                body.append("<text:p><draw:frame draw:name=\"Imagem").append(i + 1)
                    .append("\" text:anchor-type=\"as-char\" svg:width=\"").append(w)
                    .append("cm\" svg:height=\"").append(h).append("cm\">")
                body.append("<draw:image><office:binary-data>").append(im.b64)
                    .append("</office:binary-data></draw:image></draw:frame></text:p>")
            }
            val doc = Markdown.parse(p.text)
            for (b in doc.blocks) appendBlock(body, b, doc.notes, styles, noteCounter, tableCounter)

            // Definições de nota que ninguém chamou no texto: mantém ao final da página
            val referenced = noteRef.findAll(p.text).map { it.groupValues[1] }.toSet()
            for ((id, txt) in doc.notes) {
                if (id !in referenced) {
                    body.append("<text:p>[").append(xml(id)).append("] ")
                        .append(odf(Markdown.inline(txt), doc.notes, styles, noteCounter, allowNotes = false))
                        .append("</text:p>")
                }
            }
        }

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append(
            "<office:document xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" " +
                "xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\" " +
                "xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" " +
                "xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\" " +
                "xmlns:draw=\"urn:oasis:names:tc:opendocument:xmlns:drawing:1.0\" " +
                "xmlns:svg=\"urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0\" " +
                "xmlns:fo=\"urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0\" " +
                "office:version=\"1.3\" office:mimetype=\"application/vnd.oasis.opendocument.text\">\n"
        )
        sb.append("<office:automatic-styles>")
        sb.append("<style:style style:name=\"Tbl\" style:family=\"table\"><style:table-properties style:width=\"17cm\" table:align=\"margins\"/></style:style>")
        sb.append("<style:style style:name=\"Cel\" style:family=\"table-cell\"><style:table-cell-properties fo:padding=\"0.1cm\" fo:border=\"0.5pt solid #000000\"/></style:style>")
        sb.append("<style:style style:name=\"PB\" style:family=\"paragraph\"><style:paragraph-properties fo:break-before=\"page\"/></style:style>")
        for (lvl in 0..3) {
            val left = String.format(Locale.US, "%.1f", 0.6 + 0.6 * lvl)
            sb.append("<style:style style:name=\"L$lvl\" style:family=\"paragraph\"><style:paragraph-properties fo:margin-left=\"${left}cm\" fo:text-indent=\"-0.5cm\"/></style:style>")
        }
        sb.append("<style:style style:name=\"Q\" style:family=\"paragraph\"><style:paragraph-properties fo:margin-left=\"1cm\" fo:padding-left=\"0.2cm\" fo:border-left=\"1.5pt solid #888888\"/><style:text-properties fo:color=\"#444444\"/></style:style>")
        sb.append("<style:style style:name=\"HR\" style:family=\"paragraph\"><style:paragraph-properties fo:border-bottom=\"0.5pt solid #000000\" fo:margin-top=\"0.1cm\" fo:margin-bottom=\"0.1cm\"/></style:style>")
        sb.append("<style:style style:name=\"PN\" style:family=\"paragraph\"><style:paragraph-properties fo:text-align=\"end\"/><style:text-properties fo:font-size=\"9pt\" fo:color=\"#666666\"/></style:style>")
        for (name in styles) sb.append(textStyle(name))
        sb.append("</office:automatic-styles>")
        sb.append("<office:body><office:text>")
        sb.append(body)
        sb.append("</office:text></office:body></office:document>")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    // ---------- .pdf ----------

    private fun spannable(runs: List<Run>, forceBold: Boolean = false): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        val flag = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        for (r in runs) {
            val start = sb.length
            if (r.note != null) {
                sb.append(r.note)
                sb.setSpan(SuperscriptSpan(), start, sb.length, flag)
                sb.setSpan(RelativeSizeSpan(0.7f), start, sb.length, flag)
                continue
            }
            sb.append(r.text)
            val end = sb.length
            if (end == start) continue
            val b = r.bold || forceBold
            if (b && r.italic) sb.setSpan(StyleSpan(Typeface.BOLD_ITALIC), start, end, flag)
            else if (b) sb.setSpan(StyleSpan(Typeface.BOLD), start, end, flag)
            else if (r.italic) sb.setSpan(StyleSpan(Typeface.ITALIC), start, end, flag)
            if (r.strike) sb.setSpan(StrikethroughSpan(), start, end, flag)
            if (r.code) sb.setSpan(TypefaceSpan("monospace"), start, end, flag)
        }
        return sb
    }

    fun pdf(pages: List<Page>, withImages: Boolean): ByteArray {
        val pageW = 595
        val pageH = 842
        val margin = 40
        val width = pageW - 2 * margin

        val body = TextPaint().apply { isAntiAlias = true; color = Color.BLACK; textSize = 11f }
        val small = TextPaint(body).apply { textSize = 10f }
        val noteP = TextPaint(body).apply { textSize = 9.5f }
        val quoteP = TextPaint(body).apply { color = Color.DKGRAY }
        val pageNoP = TextPaint(body).apply { textSize = 9f; color = Color.GRAY }
        fun heading(level: Int) = TextPaint(body).apply {
            typeface = Typeface.DEFAULT_BOLD
            textSize = when (level) { 1 -> 18f; 2 -> 15f; else -> 13f }
        }
        val linePaint = Paint().apply { color = Color.GRAY; style = Paint.Style.STROKE; strokeWidth = 0.8f }

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

        fun draw(
            text: CharSequence,
            paint: TextPaint,
            spacing: Float,
            indent: Float = 0f,
            marker: String? = null,
            bar: Boolean = false,
            align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL
        ) {
            val w = (width - indent).toInt().coerceAtLeast(50)
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, w).setAlignment(align).build()
            if (y + layout.height > pageH - margin && y > margin) newPage()
            val c = page.canvas
            c.save()
            c.translate(margin + indent, y)
            layout.draw(c)
            c.restore()
            if (marker != null) {
                val mw = body.measureText(marker)
                c.drawText(marker, margin + indent - mw - 4f, y + layout.getLineBaseline(0), body)
            }
            if (bar) c.drawLine(margin + indent - 8f, y, margin + indent - 8f, y + layout.height, linePaint)
            y += layout.height + spacing
        }

        fun drawRule() {
            if (y + 12f > pageH - margin) newPage()
            y += 4f
            page.canvas.drawLine(margin.toFloat(), y, (margin + width).toFloat(), y, linePaint)
            y += 8f
        }

        fun drawTable(t: Block.Table) {
            if (t.rows.isEmpty()) return
            val cols = t.rows.maxOf { it.size }.coerceAtLeast(1)
            val colW = width.toFloat() / cols
            t.rows.forEachIndexed { ri, row ->
                val layouts = (0 until cols).map { c ->
                    val cs = spannable(Markdown.inline(row.getOrElse(c) { "" }), forceBold = t.header && ri == 0)
                    StaticLayout.Builder.obtain(cs, 0, cs.length, small, (colW - 8f).toInt().coerceAtLeast(20)).build()
                }
                val h = (layouts.maxOf { it.height } + 8).toFloat()
                if (y + h > pageH - margin && y > margin) newPage()
                for (c in 0 until cols) {
                    val x = margin + c * colW
                    page.canvas.drawRect(x, y, x + colW, y + h, linePaint)
                    page.canvas.save()
                    page.canvas.translate(x + 4f, y + 4f)
                    layouts[c].draw(page.canvas)
                    page.canvas.restore()
                }
                y += h
            }
            y += 6f
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
            val parsed = Markdown.parse(p.text)
            for (b in parsed.blocks) {
                when (b) {
                    is Block.Heading -> draw(spannable(Markdown.inline(b.text)), heading(b.level), 6f)
                    is Block.Para -> draw(spannable(Markdown.inline(b.text)), body, 5f)
                    is Block.Item -> draw(
                        spannable(Markdown.inline(b.text)), body, 3f,
                        indent = 20f + 14f * b.level, marker = b.marker
                    )
                    is Block.Quote -> draw(
                        spannable(Markdown.inline(b.text)), quoteP, 5f, indent = 20f, bar = true
                    )
                    is Block.Rule -> drawRule()
                    is Block.PageNo -> draw(
                        "p. " + b.label, pageNoP, 5f, align = Layout.Alignment.ALIGN_OPPOSITE
                    )
                    is Block.Table -> drawTable(b)
                }
            }
            if (parsed.notes.isNotEmpty()) {
                drawRule()
                for ((id, txt) in parsed.notes) {
                    val runs = listOf(Run("", note = id), Run(" ")) + Markdown.inline(txt)
                    draw(spannable(runs), noteP, 2f)
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
