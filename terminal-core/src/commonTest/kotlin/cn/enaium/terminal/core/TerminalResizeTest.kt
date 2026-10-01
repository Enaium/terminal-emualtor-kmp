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
        assertEquals("abcde", t.normalBuffer.scrollback.line(0).text(0, 5))
        assertEquals("fghij", t.lineAt(0).text(0, 5))
        assertEquals("klmno", t.lineAt(1).text(0, 5))
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
}
