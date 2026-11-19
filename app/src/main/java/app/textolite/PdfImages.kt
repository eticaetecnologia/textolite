package app.textolite

import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

/**
 * Gera um PDF que contém apenas as imagens (um JPEG por página), sem texto.
 * Os JPEGs são gravados como estão, sem recompressão: o arquivo fica leve e sem perda extra de qualidade.
 * Cada página tem o formato da própria imagem, limitado ao tamanho de uma folha A4.
 */
object PdfImages {

    fun build(files: List<File>): ByteArray {
        val out = ByteArrayOutputStream()
        val offsets = ArrayList<Int>()

        fun w(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        fun obj(n: Int) {
            while (offsets.size < n) offsets.add(0)
            offsets[n - 1] = out.size()
            w("$n 0 obj\n")
        }

        w("%PDF-1.4\n")
        val n = files.size

        // 1 = catálogo, 2 = lista de páginas; para a página i: 3+3i = página, 4+3i = conteúdo, 5+3i = imagem
        obj(1)
        w("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        obj(2)
        w("<< /Type /Pages /Count $n /Kids [" + (0 until n).joinToString(" ") { "${3 + 3 * it} 0 R" } + "] >>\nendobj\n")

        files.forEachIndexed { i, f ->
            val bytes = f.readBytes()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val iw = bounds.outWidth.coerceAtLeast(1)
            val ih = bounds.outHeight.coerceAtLeast(1)

            // Cabe numa folha A4 (595 x 842 pontos), na orientação da imagem
            val longSide = maxOf(iw, ih).toFloat()
            val shortSide = minOf(iw, ih).toFloat()
            var s = 842f / longSide
            if (shortSide * s > 595f) s = 595f / shortSide
            val pw = String.format(Locale.US, "%.2f", iw * s)
            val ph = String.format(Locale.US, "%.2f", ih * s)

            val pageObj = 3 + 3 * i
            val contObj = 4 + 3 * i
            val imgObj = 5 + 3 * i

            obj(pageObj)
            w("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $pw $ph] /Resources << /XObject << /Im0 $imgObj 0 R >> >> /Contents $contObj 0 R >>\nendobj\n")

            val content = "q $pw 0 0 $ph 0 0 cm /Im0 Do Q"
            obj(contObj)
            w("<< /Length ${content.length} >>\nstream\n$content\nendstream\nendobj\n")

            obj(imgObj)
            w("<< /Type /XObject /Subtype /Image /Width $iw /Height $ih /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${bytes.size} >>\nstream\n")
            out.write(bytes)
            w("\nendstream\nendobj\n")
        }

        val xrefPos = out.size()
        val total = 2 + 3 * n
        w("xref\n0 ${total + 1}\n")
        w("0000000000 65535 f \n")
        for (k in 0 until total) w(String.format(Locale.US, "%010d 00000 n \n", offsets[k]))
        w("trailer\n<< /Size ${total + 1} /Root 1 0 R >>\nstartxref\n$xrefPos\n%%EOF\n")
        return out.toByteArray()
    }
}
