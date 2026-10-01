package cn.enaium.terminal.core

import cn.enaium.terminal.unicode.Graphemes

/**
 * An immutable snapshot of one screen cell.
 *
 * Cells are *stored* as parallel primitive arrays inside [TerminalLine]; this
 * class is the convenience view handed to renderers and tests. Hot paths
 * (drawing, selection, reflow) read the arrays through the `*At` accessors on
 * [TerminalLine] instead.
 */
data class TerminalCell(
    /** The base code point, 0 for a blank cell, [WIDE_CONTINUATION] for the second half of a wide character. */
    val codepoint: Int,
    /** Extra code points of the grapheme cluster (combining marks, VS16, ZWJ sequences), or null. */
    val grapheme: String?,
    /** Cells occupied: 2 for a wide character, 1 for a normal one, 0 for a continuation. */
    val width: Int,
    val foreground: TerminalColor,
    val background: TerminalColor,
    val attributes: TerminalAttributes,
    /** Index into the terminal's hyperlink table (0 = no link). */
    val hyperlinkId: Int,
) {

    /** True when nothing is stored in this cell. */
    val isBlank: Boolean get() = codepoint == 0

    /** True when this cell is the right half of a wide character. */
    val isContinuation: Boolean get() = codepoint == WIDE_CONTINUATION

    /** The cell's text (base code point plus grapheme extensions); empty for blank/continuation cells. */
    fun text(): String = when (codepoint) {
        0, WIDE_CONTINUATION -> ""
        else -> Graphemes.codePointToString(codepoint) + (grapheme ?: "")
    }

    companion object {
        /** Sentinel stored in the second half of a wide character. */
        const val WIDE_CONTINUATION = 0x110000
    }
}

/**
 * One row of a screen buffer.
 *
 * Cells live in parallel primitive arrays (`codepoints`, `widths`,
 * `foreground`, `background`, `attributes`, `hyperlinks`) so a 200x60 screen
 * does not allocate 12 000 objects; grapheme extensions are the only per-cell
 * allocation and live in a lazily created array.
 *
 * [version] is bumped on every mutation and is the cache key renderers use to
 * skip rebuilding unchanged rows.
 */
class TerminalLine internal constructor(
    /** The number of columns of this line. */
    val columns: Int,
    id: Long,
) {

    /** A process-wide unique id, used to anchor selections across scrolling. */
    var id: Long = id
        internal set

    internal val codepoints = IntArray(columns)

    /** Cells occupied by the cell's content: 0 (continuation), 1, or 2 (wide). */
    internal val widths = ByteArray(columns) { 1 }
    internal val foreground = IntArray(columns)
    internal val background = IntArray(columns)
    internal val attributes = IntArray(columns)
    internal val hyperlinks = IntArray(columns)
    internal var graphemes: Array<String?>? = null

    /** True when this line soft-wraps into the next one (no hard newline was written). */
    var wrapped: Boolean = false
        internal set

    /** Bumped on every mutation; renderers use it as a cache key. */
    var version: Int = 0
        internal set

    /** True when every cell is blank. */
    val isBlank: Boolean
        get() {
            for (i in 0 until columns) {
                if (codepoints[i] != 0) return false
            }
            return true
        }

    /** The number of columns occupied by content, i.e. the index after the last non-blank cell. */
    val contentLength: Int
        get() {
            for (i in columns - 1 downTo 0) {
                if (codepoints[i] != 0) return i + 1
            }
            return 0
        }

    /** The base code point at [column] (0 = blank, [TerminalCell.WIDE_CONTINUATION] = wide continuation). */
    fun codepointAt(column: Int): Int = codepoints[column]

    /** The grapheme extensions of [column], or null. */
    fun graphemeAt(column: Int): String? = graphemes?.get(column)

    /** The number of cells the content of [column] occupies: 2 wide, 1 normal, 0 continuation. */
    fun widthAt(column: Int): Int = widths[column].toInt()

    /** True when the cell holds nothing (blank or continuation). */
    fun isCellBlank(column: Int): Boolean = codepoints[column] == 0 || codepoints[column] == TerminalCell.WIDE_CONTINUATION

    fun foregroundAt(column: Int): TerminalColor = TerminalColor.fromPacked(foreground[column])

    fun backgroundAt(column: Int): TerminalColor = TerminalColor.fromPacked(background[column])

    fun attributesAt(column: Int): TerminalAttributes = TerminalAttributes(attributes[column])

    /** The hyperlink table index of [column] (0 = none). */
    fun hyperlinkIdAt(column: Int): Int = hyperlinks[column]

    /** A snapshot of [column]. */
    fun cell(column: Int): TerminalCell = TerminalCell(
        codepoint = codepoints[column],
        grapheme = graphemes?.get(column),
        width = widths[column].toInt(),
        foreground = TerminalColor.fromPacked(foreground[column]),
        background = TerminalColor.fromPacked(background[column]),
        attributes = TerminalAttributes(attributes[column]),
        hyperlinkId = hyperlinks[column],
    )

    /**
     * The text of `[start, end)`; continuation cells contribute nothing and
     * trailing blanks are trimmed when [trimEnd] is set (which is what copying
     * a selection wants).
     */
    fun text(start: Int = 0, end: Int = columns, trimEnd: Boolean = true): String {
        val to = end.coerceAtMost(columns)
        val from = start.coerceAtLeast(0)
        var last = to
        if (trimEnd) {
            while (last > from && isCellBlank(last - 1)) last--
        }
        if (last <= from) return ""
        val sb = StringBuilder(last - from)
        for (i in from until last) {
            when (val cp = codepoints[i]) {
                0 -> sb.append(' ')
                TerminalCell.WIDE_CONTINUATION -> Unit
                else -> {
                    sb.appendCodePointCompat(cp)
                    graphemes?.get(i)?.let { sb.append(it) }
                }
            }
        }
        return sb.toString()
    }

    internal fun bump() {
        version++
    }

    /**
     * Appends the text of [column] to [out] without building intermediate
     * strings (blank and continuation cells append nothing). This is the hot
     * path renderers use to assemble a row's text runs.
     */
    fun appendTextTo(out: StringBuilder, column: Int) {
        when (val cp = codepoints[column]) {
            0, TerminalCell.WIDE_CONTINUATION -> Unit
            else -> {
                out.appendCodePointCompat(cp)
                graphemes?.get(column)?.let { out.append(it) }
            }
        }
    }

    internal fun setGrapheme(column: Int, value: String?) {
        var array = graphemes
        if (value == null) {
            array?.set(column, null)
            return
        }
        if (array == null) {
            array = arrayOfNulls(columns)
            graphemes = array
        }
        array[column] = value
    }

    internal fun appendGrapheme(column: Int, cp: Int) {
        val existing = graphemes?.get(column)
        setGrapheme(column, (existing ?: "") + Graphemes.codePointToString(cp))
    }

    internal fun setCell(
        column: Int,
        codepoint: Int,
        width: Int,
        foregroundColor: Int,
        backgroundColor: Int,
        attributeBits: Int,
        hyperlinkId: Int,
    ) {
        codepoints[column] = codepoint
        widths[column] = width.toByte()
        foreground[column] = foregroundColor
        background[column] = backgroundColor
        attributes[column] = attributeBits
        hyperlinks[column] = hyperlinkId
        graphemes?.set(column, null)
        bump()
    }

    /** Copies cell [column] of this line into [targetColumn] of [target]. */
    internal fun copyCellTo(target: TerminalLine, column: Int, targetColumn: Int) {
        target.codepoints[targetColumn] = codepoints[column]
        target.widths[targetColumn] = widths[column]
        target.foreground[targetColumn] = foreground[column]
        target.background[targetColumn] = background[column]
        target.attributes[targetColumn] = attributes[column]
        target.hyperlinks[targetColumn] = hyperlinks[column]
        target.setGrapheme(targetColumn, graphemes?.get(column))
    }

    /** Blanks `[from, until)` (default colors, no attributes). */
    internal fun clearRange(from: Int, until: Int) = fillRange(from, until, 0, 0)

    /** Blanks `[from, until)` with [backgroundPacked] and [attributeBits] (BCE erase). */
    internal fun fillRange(from: Int, until: Int, backgroundPacked: Int, attributeBits: Int) {
        val start = from.coerceAtLeast(0)
        val end = until.coerceAtMost(columns)
        for (i in start until end) {
            codepoints[i] = 0
            widths[i] = 1
            foreground[i] = 0
            background[i] = backgroundPacked
            attributes[i] = attributeBits
            hyperlinks[i] = 0
            graphemes?.set(i, null as String?)
        }
        bump()
    }

    internal fun clearAll() = clearRange(0, columns)

    /**
     * Shifts the cells of `[from, until)` by [delta] columns (positive =
     * right), filling the vacated columns with blanks.
     */
    internal fun shiftRange(from: Int, until: Int, delta: Int) {
        if (delta == 0) return
        val end = until.coerceAtMost(columns)
        if (delta > 0) {
            for (i in end - 1 downTo from) {
                val target = i + delta
                if (target in 0 until columns) copyWithin(this, i, target)
            }
            for (i in from until (from + delta).coerceAtMost(columns)) blankCell(i)
        } else {
            val shift = -delta
            for (i in from until end) {
                val target = i - shift
                if (target >= from && target < columns) copyWithin(this, i, target)
            }
            for (i in (end - shift).coerceAtLeast(0) until end) blankCell(i)
        }
        bump()
    }

    private fun copyWithin(line: TerminalLine, from: Int, to: Int) {
        line.codepoints[to] = line.codepoints[from]
        line.widths[to] = line.widths[from]
        line.foreground[to] = line.foreground[from]
        line.background[to] = line.background[from]
        line.attributes[to] = line.attributes[from]
        line.hyperlinks[to] = line.hyperlinks[from]
        line.setGrapheme(to, line.graphemes?.get(from))
    }

    private fun blankCell(column: Int) {
        codepoints[column] = 0
        widths[column] = 1
        foreground[column] = 0
        background[column] = 0
        attributes[column] = 0
        hyperlinks[column] = 0
        graphemes?.set(column, null)
    }

    /**
     * Clears orphaned halves of wide characters after a bulk edit: a
     * continuation cell without a wide base to its left, or a wide base whose
     * right half is missing.
     */
    internal fun normalizeWideCells() {
        var changed = false
        var i = 0
        while (i < columns) {
            when {
                widths[i].toInt() == 0 -> {
                    val baseWidth = if (i > 0) widths[i - 1].toInt() else 0
                    if (baseWidth != 2) {
                        blankCell(i)
                        changed = true
                    }
                }

                widths[i].toInt() == 2 -> {
                    val nextWidth = if (i + 1 < columns) widths[i + 1].toInt() else -1
                    if (nextWidth != 0) {
                        blankCell(i)
                        changed = true
                    }
                }
            }
            i++
        }
        if (changed) bump()
    }
}

/** Appends [cp] (which may be outside the BMP) to this builder. */
internal fun StringBuilder.appendCodePointCompat(cp: Int) {
    if (cp <= 0xFFFF) {
        append(cp.toChar())
    } else {
        val v = cp - 0x10000
        append(((v shr 10) + 0xD800).toChar())
        append(((v and 0x3FF) + 0xDC00).toChar())
    }
}

/** The code points of [text] as an [IntArray] (surrogate pairs combined). */
internal fun String.toCodePointArray(): IntArray {
    val out = IntArray(length)
    var count = 0
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) {
            out[count] = 0x10000 + ((c.code - 0xD800) shl 10) + (this[i + 1].code - 0xDC00)
            i += 2
        } else {
            out[count] = c.code
            i++
        }
        count++
    }
    return if (count == out.size) out else out.copyOf(count)
}
