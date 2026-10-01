package cn.enaium.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalResizeTest {

    @Test
    fun shrinkingColumnsRewrapsLogicalLines() {
        val t = Terminal(columns = 10, rows = 3, scrollbackLimit = 100)
        t.setCursor(0, 0)
        t.write("abcdefghijklmno")
        assertTrue(t.lineAt(0).wrapped)
        t.setCursor(1, 2)
        t.resize(5, 3)
        assertEquals(5, t.columns)
        assertEquals(3, t.rows)
        // The re-wrapped line fits the screen exactly, so nothing scrolls off.
        assertEquals(0, t.scrollbackSize)
        assertEquals("abcde", t.lineAt(0).text(0, 5))
        assertEquals("fghij", t.lineAt(1).text(0, 5))
        assertEquals("klmno", t.lineAt(2).text(0, 5))
        assertEquals("m", t.lineAt(t.cursorRow).text(t.cursorColumn, t.cursorColumn + 1))
    }

    @Test
    fun growingColumnsJoinsSoftWrappedLines() {
        val t = Terminal(columns = 5, rows = 3, scrollbackLimit = 100)
        t.setCursor(0, 0)
        t.write("abcdefghijklm")
        t.setCursor(1, 2)
        t.resize(10, 3)
        assertEquals(10, t.columns)
        assertEquals("abcdefghij", t.lineAt(0).text(0, 10))
        assertEquals("klm", t.lineAt(1).text(0, 10))
        assertEquals("h", t.lineAt(t.cursorRow).text(t.cursorColumn, t.cursorColumn + 1))
    }

    @Test
    fun cursorKeepsItsPlaceWhenReflowPushesLinesToScrollback() {
        val t = Terminal(columns = 10, rows = 3, scrollbackLimit = 100)
        t.setCursor(0, 0)
        t.write("abcdefghijklmnopqrst")
        t.setCursor(1, 2)
        t.resize(6, 3)
        assertEquals("m", t.lineAt(t.cursorRow).text(t.cursorColumn, t.cursorColumn + 1))
    }

    @Test
    fun growingRowsKeepsContentAndCursor() {
        val t = Terminal(columns = 10, rows = 2, scrollbackLimit = 100)
        t.write("abc")
        t.resize(10, 4)
        assertEquals(4, t.rows)
        assertEquals("abc", t.lineAt(0).text(0, 10))
        assertEquals(3, t.cursorColumn)
        assertEquals(0, t.cursorRow)
    }

    @Test
    fun cursorAtEndOfLineStaysAtEndAfterResize() {
        val t = Terminal(columns = 10, rows = 2, scrollbackLimit = 100)
        t.write("abcdefgh")
        t.resize(20, 2)
        assertEquals(0, t.cursorRow)
        assertEquals(8, t.cursorColumn)
    }

    @Test
    fun resizingSameSizeIsANoOp() {
        val t = Terminal(columns = 10, rows = 2, scrollbackLimit = 100)
        t.write("abc")
        val revision = t.revision
        t.resize(10, 2)
        assertEquals(revision, t.revision)
    }

    @Test
    fun resizeResetsScrollRegionAndTabStops() {
        val t = Terminal(columns = 20, rows = 5, scrollbackLimit = 100)
        t.setScrollRegion(2, 4)
        t.resize(10, 3)
        assertEquals(0, t.scrollTop)
        assertEquals(2, t.scrollBottom)
        t.setCursor(0, 0)
        t.tab()
        assertEquals(8, t.cursorColumn)
    }

    @Test
    fun alternateScreenResizeKeepsTopLeftCorner() {
        val t = Terminal(columns = 5, rows = 2, scrollbackLimit = 100)
        t.setPrivateMode(1049, true)
        t.setCursor(0, 0)
        t.write("abcde")
        t.setCursor(1, 0)
        t.write("fghij")
        t.resize(10, 3)
        assertEquals("abcde", t.lineAt(0).text(0, 10))
        assertEquals("fghij", t.lineAt(1).text(0, 10))
        assertEquals("", t.lineAt(2).text(0, 10))
        assertTrue(t.isAlternateScreen)
    }

    @Test
    fun resizeClampsCursorIntoNewBounds() {
        val t = Terminal(columns = 10, rows = 5, scrollbackLimit = 100)
        t.setCursor(4, 8)
        t.resize(4, 2)
        assertEquals(4, t.columns)
        assertEquals(2, t.rows)
        assertTrue(t.cursorRow in 0..1)
        assertTrue(t.cursorColumn in 0..3)
    }

    @Test
    fun reflowIgnoresBlankRowsBelowTheContent() {
        // Blank rows below the content are padding. A line that re-wraps into
        // more rows than before must not push its own beginning into the
        // scrollback: the screen then shows the middle of the line and the
        // cursor lands on the wrong row.
        val t = Terminal(columns = 20, rows = 10, scrollbackLimit = 100)
        t.write("x".repeat(30))
        assertEquals(1, t.cursorRow)
        t.resize(10, 10)
        assertEquals(0, t.scrollbackSize)
        assertEquals("xxxxxxxxxx", t.lineAt(0).text(0, 10))
        assertEquals("xxxxxxxxxx", t.lineAt(1).text(0, 10))
        assertEquals("xxxxxxxxxx", t.lineAt(2).text(0, 10))
        assertEquals("", t.lineAt(3).text(0, 10))
        assertEquals(2, t.cursorRow)
        assertEquals(9, t.cursorColumn)
    }

    @Test
    fun cursorPastTheWrittenContentKeepsItsLine() {
        // The re-wrap trims the cells that were never written, so a cursor
        // that was moved past the last character of its line ends up past the
        // trimmed end: it must keep its line instead of falling back to the
        // bottom row (which is where a dropped cursor lands).
        val t = Terminal(columns = 40, rows = 6, scrollbackLimit = 100)
        t.write("ab")
        t.setCursor(0, 10)
        assertEquals(10, t.cursorColumn)
        t.resize(20, 6)
        assertEquals(0, t.cursorRow)
        assertEquals(2, t.cursorColumn)
        assertEquals("ab", t.lineAt(0).text(0, 2))

        // A row with nothing written at all: the cursor keeps its column.
        val blank = Terminal(columns = 40, rows = 6, scrollbackLimit = 100)
        blank.lineFeed()
        blank.carriageReturn()
        blank.setCursor(1, 5)
        blank.resize(20, 6)
        assertEquals(1, blank.cursorRow)
        assertEquals(5, blank.cursorColumn)
    }

    @Test
    fun shrinkingRowsKeepsCursorOnItsLine() {
        // When the screen gets shorter and the cursor sits above the tail, the
        // window follows the cursor: it must stay on the line it was on rather
        // than on whatever line the clamped row happens to show.
        val t = Terminal(columns = 20, rows = 10, scrollbackLimit = 100)
        for (row in 0 until 10) t.writeLine(row, "row-$row")
        t.setCursor(0, 0)
        t.resize(20, 5)
        assertEquals(0, t.cursorRow)
        assertEquals("row-0", t.lineAt(t.cursorRow).text(0, 5))
    }

    @Test
    fun repeatedResizesConserveContentAndCursor() {
        // Dragging a window resizes many times in a row: every step must keep
        // the retained text and the cursor's line intact (no duplicated rows,
        // no drifting content), and returning to the starting size must give
        // back the starting screen.
        val t = Terminal(columns = 40, rows = 10, scrollbackLimit = 200)
        t.write("$ for i in \$(seq 1 30); do echo item-\$i; done")
        for (i in 1..30) {
            t.carriageReturn()
            t.lineFeed()
            t.write("item-$i")
        }
        t.carriageReturn()
        t.lineFeed()
        t.write("long:" + "x".repeat(50))
        t.carriageReturn()
        t.lineFeed()
        t.write("$ ")
        val before = retainedText(t)
        for (width in intArrayOf(39, 41, 20, 21, 60, 59, 80, 40, 40)) {
            t.resize(width, 10)
            val after = retainedText(t)
            assertTrue(before == after, "retained text changed at width $width:\n$before\n---\n$after")
            assertTrue(
                t.lineAt(t.cursorRow).text(0, t.columns).startsWith("$ "),
                "cursor left its line at width $width",
            )
        }
    }

    /**
     * The text the terminal retains: scrollback plus screen, soft wraps joined.
     *
     * A row is padded with cells that were never written; only the padding at
     * the end of a *logical* line is dropped (trimming every row would eat the
     * space a soft wrap happened to fall after, which depends on the width).
     */
    private fun retainedText(t: Terminal): String {
        val sb = StringBuilder()
        val pending = StringBuilder()
        fun endLine() {
            sb.append(pending.toString().trimEnd(' ')).append('\n')
            pending.setLength(0)
        }
        val scrollback = t.normalBuffer.scrollback
        for (i in 0 until scrollback.size) {
            val line = scrollback.line(i)
            pending.append(line.text(0, line.columns))
            if (!line.wrapped) endLine()
        }
        for (row in 0 until t.rows) {
            val line = t.lineAt(row)
            pending.append(line.text(0, line.columns))
            if (!line.wrapped) endLine()
        }
        return sb.toString()
    }
}
