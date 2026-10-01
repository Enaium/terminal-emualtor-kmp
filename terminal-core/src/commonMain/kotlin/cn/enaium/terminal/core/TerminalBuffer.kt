package cn.enaium.terminal.core

import kotlin.math.max
import kotlin.math.min

/**
 * The scrollback ring buffer: a bounded FIFO of lines that scrolled off the
 * top of the normal screen.
 *
 * Index 0 is the oldest retained line. When the limit is reached the oldest
 * line is evicted and returned to the owning buffer for reuse.
 */
class ScrollbackBuffer(limit: Int) {

    private val lines = ArrayDeque<TerminalLine>()

    /** The maximum number of retained lines; shrinking trims the oldest lines. */
    var limit: Int = limit.coerceAtLeast(0)
        set(value) {
            field = value.coerceAtLeast(0)
            while (lines.size > field) lines.removeFirst()
        }

    /** The number of retained lines. */
    val size: Int get() = lines.size

    /** The retained line at [index] (0 = oldest). */
    fun line(index: Int): TerminalLine = lines[index]

    /**
     * Appends [line] and returns the line evicted to make room, or null when
     * the buffer had space. The caller recycles the evicted line.
     */
    internal fun push(line: TerminalLine): TerminalLine? {
        if (limit == 0) return line
        lines.addLast(line)
        return if (lines.size > limit) lines.removeFirst() else null
    }

    internal fun clear() = lines.clear()

    internal fun replaceAll(newLines: List<TerminalLine>) {
        lines.clear()
        for (line in newLines) lines.addLast(line)
        while (lines.size > limit) lines.removeFirst()
    }
}

/** Monotonic id source shared by both screen buffers. */
internal class LineIds {
    private var next = 1L
    fun next(): Long = next++
}

/**
 * One screen: the normal screen (with scrollback) or the alternate screen.
 *
 * The buffer owns its lines and recycles them, so scrolling reuses line
 * objects instead of allocating five arrays per scrolled row. Every line
 * carries a monotonic [TerminalLine.id], which is what selections are anchored
 * to: ids survive scrolling and are never reused.
 */
class TerminalBuffer internal constructor(
    /** True for the alternate screen (no scrollback, no reflow). */
    val isAlternate: Boolean,
    scrollbackLimit: Int,
    private val ids: LineIds,
) {

    /** The number of columns. */
    var columns: Int = 0
        private set

    /** The number of visible rows. */
    var rows: Int = 0
        private set

    /** The lines that scrolled off the top (empty for the alternate screen). */
    val scrollback: ScrollbackBuffer = ScrollbackBuffer(if (isAlternate) 0 else scrollbackLimit)

    internal var lines: Array<TerminalLine> = emptyArray()
    private val pool = ArrayDeque<TerminalLine>()

    /** The visible line at [row] (0 = top). */
    fun line(row: Int): TerminalLine = lines[row]

    /** Every visible line, top to bottom. */
    fun visibleLines(): List<TerminalLine> = lines.toList()

    internal fun initialize(columns: Int, rows: Int) {
        this.columns = columns
        this.rows = rows
        lines = Array(rows) { newLine() }
        scrollback.clear()
        pool.clear()
    }

    internal fun newLine(): TerminalLine = TerminalLine(columns, ids.next())

    private fun obtainLine(): TerminalLine = pool.removeLastOrNull()?.also { it.reset(ids.next()) } ?: newLine()

    private fun recycle(line: TerminalLine) {
        if (line.columns == columns && pool.size < 64) {
            line.reset(0)
            pool.addLast(line)
        }
    }

    private fun recycleAll(candidates: Iterable<TerminalLine>, keep: Set<Long>) {
        for (line in candidates) {
            if (!keep.contains(line.id)) recycle(line)
        }
    }

    internal fun clearRow(row: Int) {
        lines[row].clearAll()
    }

    internal fun clearAll() {
        lines.forEach { it.clearAll() }
    }

    /** Clears the cells of [row] and marks it as a hard (non-wrapped) line. */
    internal fun resetRow(row: Int) {
        val line = lines[row]
        line.clearAll()
        line.wrapped = false
    }

    /**
     * Scrolls the region `[top, bottom]` up by [count] lines.
     *
     * When [intoScrollback] is set the line leaving the top of the region goes
     * onto the scrollback, otherwise it is recycled.
     */
    internal fun scrollUp(top: Int, bottom: Int, count: Int, intoScrollback: Boolean) {
        if (top > bottom) return
        repeat(count.coerceAtMost(bottom - top + 1)) {
            val first = lines[top]
            if (intoScrollback) {
                scrollback.push(first)?.let { recycle(it) }
            } else {
                recycle(first)
            }
            for (i in top until bottom) lines[i] = lines[i + 1]
            lines[bottom] = obtainLine()
        }
    }

    /** Scrolls the region `[top, bottom]` down by [count] lines. */
    internal fun scrollDown(top: Int, bottom: Int, count: Int) {
        if (top > bottom) return
        repeat(count.coerceAtMost(bottom - top + 1)) {
            recycle(lines[bottom])
            for (i in bottom downTo top + 1) lines[i] = lines[i - 1]
            lines[top] = obtainLine()
        }
    }

    /** Inserts [count] blank lines at [at] inside `[top, bottom]`. */
    internal fun insertLines(top: Int, bottom: Int, at: Int, count: Int) {
        if (at !in top..bottom) return
        repeat(count.coerceAtMost(bottom - at + 1)) {
            recycle(lines[bottom])
            for (i in bottom downTo at + 1) lines[i] = lines[i - 1]
            lines[at] = obtainLine()
        }
    }

    /** Deletes [count] lines at [at] inside `[top, bottom]`. */
    internal fun deleteLines(top: Int, bottom: Int, at: Int, count: Int) {
        if (at !in top..bottom) return
        repeat(count.coerceAtMost(bottom - at + 1)) {
            recycle(lines[at])
            for (i in at until bottom) lines[i] = lines[i + 1]
            lines[bottom] = obtainLine()
        }
    }

    /**
     * Resizes without reflowing (the alternate screen path): content is
     * copied top-left, everything else is blank, and the caller clamps the
     * cursor.
     */
    internal fun resizeSimple(newColumns: Int, newRows: Int) {
        if (newColumns == columns && newRows == rows) return
        val old = lines
        val newLines = Array(newRows) { TerminalLine(newColumns, ids.next()) }
        for (row in 0 until min(rows, newRows)) {
            val source = old[row]
            val target = newLines[row]
            for (column in 0 until min(columns, newColumns)) {
                source.copyCellTo(target, column, column)
            }
            target.wrapped = source.wrapped
        }
        lines = newLines
        columns = newColumns
        rows = newRows
        pool.clear()
    }

    /**
     * Reflows to [newColumns] x [newRows], re-wrapping logical lines (a run of
     * rows joined by [TerminalLine.wrapped]).
     *
     * [cursorLineId] / [cursorColumn] describe the cursor before the reflow;
     * the result is its new `(row, column)`, or null when the cursor's line was
     * trimmed away.
     */
    internal fun reflow(newColumns: Int, newRows: Int, cursorLineId: Long, cursorColumn: Int): IntArray? {
        if (newColumns == columns && newRows == rows) return intArrayOf(-1, -1)
        val oldColumns = columns

        // 1. Every line, oldest scrollback line first.
        val sources = ArrayList<TerminalLine>(scrollback.size + rows)
        for (i in 0 until scrollback.size) sources.add(scrollback.line(i))
        for (i in 0 until rows) sources.add(lines[i])

        // 2. Group into logical lines.
        val logical = ArrayList<MutableList<TerminalLine>>()
        var current = ArrayList<TerminalLine>()
        for (source in sources) {
            current.add(source)
            if (!source.wrapped) {
                logical.add(current)
                current = ArrayList()
            }
        }
        if (current.isNotEmpty()) logical.add(current)

        // 3. Re-wrap.
        val output = ArrayList<TerminalLine>(sources.size)
        var cursorRow = -1
        var cursorColumnOut = 0
        for (segments in logical) {
            val totalLength = segments.size * oldColumns
            // Trim the logical line's trailing blanks so an almost empty row
            // does not re-wrap into a chain of empty rows.
            var length = totalLength
            while (length > 0) {
                val segment = segments[(length - 1) / oldColumns]
                if (segment.isCellBlank((length - 1) % oldColumns)) length-- else break
            }
            // False for a logical line that holds no character at all (a blank
            // row, or a row of nothing but spaces).
            val hasContent = length > 0
            if (length == 0) length = 1

            // Where the cursor sits inside this logical line, if anywhere.
            var cursorAbsolute = -1
            var offset = 0
            for (segment in segments) {
                if (segment.id == cursorLineId) {
                    cursorAbsolute = offset + min(cursorColumn, oldColumns - 1).coerceAtLeast(0)
                    break
                }
                offset += oldColumns
            }

            var emitted = 0
            while (emitted < length) {
                val target = TerminalLine(newColumns, ids.next())
                val chunk = min(newColumns, length - emitted)
                for (i in 0 until chunk) {
                    val absolute = emitted + i
                    if (absolute == cursorAbsolute) {
                        cursorRow = output.size
                        cursorColumnOut = i
                    }
                    segments[absolute / oldColumns].copyCellTo(target, absolute % oldColumns, i)
                }
                emitted += chunk
                target.wrapped = emitted < length
                output.add(target)
            }

            // The cursor may sit at (or past) the end of the logical line: the
            // cells that were never written are trimmed above, so a cursor
            // moved past the text ends up past the trimmed end. Keep it at the
            // end of the content instead of dropping it - a dropped cursor
            // sends the caller to the bottom row.
            if (cursorRow < 0 && cursorAbsolute >= 0 && cursorAbsolute >= length && length > 0) {
                cursorRow = output.size - 1
                cursorColumnOut = if (hasContent) {
                    // One past the last character, wrapped onto the last row.
                    (length - (length - 1) / newColumns * newColumns).coerceAtMost(newColumns - 1)
                } else {
                    // Nothing to align to: keep the column the cursor was on.
                    cursorColumn.coerceIn(0, newColumns - 1)
                }
            }
        }

        // 4. The tail stays on screen, the rest goes back into the scrollback.
        //
        // Trailing blank rows are padding, not content: they are dropped first
        // (never the cursor's row), otherwise a line that re-wrapped into more
        // rows than before would push its own beginning into the scrollback -
        // the screen then shows the middle of the line with the cursor on the
        // wrong row.
        var contentEnd = output.size
        while (contentEnd > 1 && contentEnd - 1 > cursorRow && output[contentEnd - 1].isBlank) contentEnd--
        val content: List<TerminalLine> = if (contentEnd == output.size) output else output.subList(0, contentEnd)

        val keep = max(1, newRows)
        // The window normally shows the tail of the content. When the cursor
        // would be cut off - the screen got shorter and the cursor sits above
        // the tail - the window is anchored on the cursor's row instead, so the
        // cursor always stays on its own line.
        val tail = max(0, content.size - keep)
        val start = if (cursorRow in 0 until tail) cursorRow else tail
        val scrollbackLines: List<TerminalLine> = content.subList(0, start)
        val screenLines: List<TerminalLine> = content.subList(start, min(content.size, start + keep))

        val retained = HashSet<Long>()
        scrollbackLines.forEach { retained.add(it.id) }
        screenLines.forEach { retained.add(it.id) }
        recycleAll(sources, retained)
        pool.clear()
        scrollback.replaceAll(scrollbackLines)

        val newLines = Array(newRows) { TerminalLine(newColumns, ids.next()) }
        for (i in screenLines.indices) newLines[i] = screenLines[i]
        lines = newLines
        columns = newColumns
        rows = newRows

        if (cursorRow < 0) return null
        // `cursorRow` indexes `output`; the lines that went to the scrollback
        // are no longer on screen, so shift the row into screen coordinates.
        return intArrayOf(cursorRow - scrollbackLines.size, cursorColumnOut)
    }

    /** The id of the oldest line still retained. */
    internal fun oldestLineId(): Long = if (scrollback.size > 0) scrollback.line(0).id else lines[0].id

    internal fun findLineById(id: Long): TerminalLine? {
        for (i in 0 until scrollback.size) {
            val line = scrollback.line(i)
            if (line.id == id) return line
        }
        for (i in 0 until rows) {
            val line = lines[i]
            if (line.id == id) return line
        }
        return null
    }

    /** The row (0 = top of the screen) of the line with [id], or -1 when it is not visible. */
    internal fun rowOfLineId(id: Long): Int {
        for (i in 0 until rows) {
            if (lines[i].id == id) return i
        }
        return -1
    }
}

internal fun TerminalLine.reset(newId: Long) {
    id = newId
    clearAll()
    wrapped = false
    version = 0
}
