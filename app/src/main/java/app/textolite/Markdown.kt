package app.textolite

/** Trecho de texto com formatação. Se [note] não for nulo, é uma chamada de nota de rodapé. */
class Run(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    val note: String? = null
)

sealed class Block {
    class Heading(val level: Int, val text: String) : Block()
    class Para(val text: String) : Block()
    class Item(val marker: String, val text: String, val level: Int) : Block()
    class Quote(val text: String) : Block()
    object Rule : Block()
    class Table(val rows: List<List<String>>, val header: Boolean) : Block()
    class PageNo(val label: String) : Block()
}

/** Uma página já interpretada: blocos + definições de notas de rodapé. */
class Doc(val blocks: List<Block>, val notes: Map<String, String>)

/**
 * Interpretador de Markdown usado pelos exportadores (.pdf e .fodt).
 * Elementos: títulos, negrito, itálico, riscado, código, listas, citações, linha horizontal,
 * tabelas, notas de rodapé ([^1] e [^1]: texto) e número de página ([p. 12]).
 */
object Markdown {

    private val heading = Regex("^(#{1,6})\\s+(.*)$")
    private val noteDef = Regex("^\\[\\^([^\\]\\s]+)]:\\s*(.*)$")
    private val pageNo = Regex("^\\[p\\.\\s*([^\\]]+)]$", RegexOption.IGNORE_CASE)
    private val numbered = Regex("^(\\d{1,3}[.)])\\s+(.*)$")
    private val bullet = Regex("^[-*+]\\s+(.*)$")
    private val rule = Regex("^([-*_])(\\s*\\1){2,}$")

    fun isTableRow(line: String): Boolean {
        val t = line.trim()
        return t.length > 1 && t.startsWith("|") && t.endsWith("|")
    }

    private fun isSeparator(line: String): Boolean =
        line.trim().replace("|", "").replace("-", "").replace(":", "").replace(" ", "").isEmpty()

    private fun cells(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

    fun parse(md: String): Doc {
        val blocks = mutableListOf<Block>()
        val notes = LinkedHashMap<String, String>()
        val lines = md.lines()
        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val t = raw.trim()
            if (t.isEmpty()) {
                i++
                continue
            }
            if (isTableRow(t)) {
                val rows = mutableListOf<List<String>>()
                var header = false
                while (i < lines.size && isTableRow(lines[i])) {
                    val l = lines[i].trim()
                    if (isSeparator(l)) {
                        if (rows.size == 1) header = true
                    } else {
                        rows.add(cells(l))
                    }
                    i++
                }
                if (rows.isNotEmpty()) blocks.add(Block.Table(rows, header))
                continue
            }
            i++

            val nd = noteDef.find(t)
            val h = heading.find(t)
            val pn = pageNo.find(t)
            val nb = numbered.find(t)
            val bl = bullet.find(t)
            val lead = raw.length - raw.trimStart().length
            if (nd != null) {
                notes[nd.groupValues[1]] = nd.groupValues[2]
            } else if (pn != null) {
                blocks.add(Block.PageNo(pn.groupValues[1].trim()))
            } else if (h != null) {
                blocks.add(Block.Heading(h.groupValues[1].length, h.groupValues[2].trim()))
            } else if (rule.matches(t)) {
                blocks.add(Block.Rule)
            } else if (t.startsWith(">")) {
                blocks.add(Block.Quote(t.removePrefix(">").trim()))
            } else if (bl != null) {
                blocks.add(Block.Item("•", bl.groupValues[1], (lead / 2).coerceIn(0, 3)))
            } else if (nb != null) {
                blocks.add(Block.Item(nb.groupValues[1], nb.groupValues[2], (lead / 2).coerceIn(0, 3)))
            } else {
                blocks.add(Block.Para(t))
            }
        }
        return Doc(blocks, notes)
    }

    /** Existe um fechamento válido do marcador simples (* ou _) depois de [from]? */
    private fun closerExists(s: String, c: Char, from: Int): Boolean {
        var j = from
        while (j < s.length) {
            if (s[j] == c && s[j - 1] != c && s.getOrNull(j + 1) != c && !s[j - 1].isWhitespace()) return true
            j++
        }
        return false
    }

    fun inline(s: String): List<Run> {
        val out = mutableListOf<Run>()
        val sb = StringBuilder()
        var bold = false
        var italic = false
        var strike = false

        fun flush() {
            if (sb.isNotEmpty()) {
                out.add(Run(sb.toString(), bold, italic, strike))
                sb.clear()
            }
        }

        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length && "\\`*_[]#~|>".indexOf(s[i + 1]) >= 0) {
                sb.append(s[i + 1])
                i += 2
            } else if (c == '`') {
                val j = s.indexOf('`', i + 1)
                if (j > i) {
                    flush()
                    out.add(Run(s.substring(i + 1, j), bold, italic, strike, code = true))
                    i = j + 1
                } else {
                    sb.append(c)
                    i++
                }
            } else if (c == '[' && s.startsWith("[^", i)) {
                val j = s.indexOf(']', i)
                if (j > i + 2) {
                    flush()
                    out.add(Run("", note = s.substring(i + 2, j)))
                    i = j + 1
                } else {
                    sb.append(c)
                    i++
                }
            } else if (s.startsWith("**", i) || s.startsWith("__", i)) {
                val m = s.substring(i, i + 2)
                val next = s.getOrNull(i + 2)
                val canOpen = !bold && next != null && !next.isWhitespace() && s.indexOf(m, i + 2) >= 0
                val canClose = bold && i > 0 && !s[i - 1].isWhitespace()
                if (canOpen || canClose) {
                    flush()
                    bold = !bold
                } else {
                    sb.append(m)
                }
                i += 2
            } else if (s.startsWith("~~", i)) {
                val next = s.getOrNull(i + 2)
                val canOpen = !strike && next != null && !next.isWhitespace() && s.indexOf("~~", i + 2) >= 0
                val canClose = strike && i > 0 && !s[i - 1].isWhitespace()
                if (canOpen || canClose) {
                    flush()
                    strike = !strike
                } else {
                    sb.append("~~")
                }
                i += 2
            } else if (c == '*' || c == '_') {
                val prev = s.getOrNull(i - 1)
                val next = s.getOrNull(i + 1)
                val canOpen = !italic && next != null && !next.isWhitespace() &&
                    (c == '*' || prev == null || !prev.isLetterOrDigit()) && closerExists(s, c, i + 1)
                val canClose = italic && prev != null && !prev.isWhitespace() &&
                    (c == '*' || next == null || !next.isLetterOrDigit())
                if (canOpen || canClose) {
                    flush()
                    italic = !italic
                } else {
                    sb.append(c)
                }
                i++
            } else {
                sb.append(c)
                i++
            }
        }
        flush()
        return out
    }
}
