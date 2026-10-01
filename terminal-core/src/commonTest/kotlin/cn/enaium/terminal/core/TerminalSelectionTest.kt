package cn.enaium.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalSelectionTest {

    private fun terminal(): Terminal {
        val t = Terminal(columns = 12, rows = 4, scrollbackLimit = 100)
        t.writeLines("foo bar_baz", "second", "third")
        return t
    }

    @Test
    fun linearSelectionText() {
        val t = terminal()
        t.setSelection(TerminalPosition(t.lineIdAt(0), 0), TerminalPosition(t.lineIdAt(0), 2))
        assertEquals("foo", t.selectionText())
    }

    @Test
    fun reversedSelectionIsNormalised() {
        val t = terminal()
        t.setSelection(TerminalPosition(t.lineIdAt(0), 2), TerminalPosition(t.lineIdAt(0), 0))
        assertEquals("foo", t.selectionText())
        assertEquals(0, t.selection!!.from.column)
        assertEquals(2, t.selection!!.to.column)
    }

    @Test
    fun linearSelectionSpansLines() {
        val t = terminal()
        t.setSelection(TerminalPosition(t.lineIdAt(0), 8), TerminalPosition(t.lineIdAt(1), 2))
        assertEquals("baz\nsec", t.selectionText())
    }

    @Test
    fun isSelectedAndSelectedColumns() {
        val t = terminal()
        val line = t.lineIdAt(0)
        t.setSelection(TerminalPosition(line, 1), TerminalPosition(line, 3))
        assertTrue(t.isSelected(line, 1))
        assertTrue(t.isSelected(line, 3))
        assertFalse(t.isSelected(line, 0))
        assertEquals(1..3, t.selectedColumns(line))
        assertNull(t.selectedColumns(-1L))
    }

    @Test
    fun blockSelectionUsesAColumnRectangle() {
        val t = terminal()
        t.setSelection(
            TerminalPosition(t.lineIdAt(0), 1),
            TerminalPosition(t.lineIdAt(2), 3),
            TerminalSelectionMode.BLOCK,
        )
        assertEquals("oo \neco\nhir", t.selectionText())
        assertEquals(1..3, t.selectedColumns(t.lineIdAt(1)))
        assertTrue(t.isSelected(t.lineIdAt(1), 2))
        assertFalse(t.isSelected(t.lineIdAt(1), 4))
    }

    @Test
    fun selectWordPicksIdentifierRun() {
        val t = terminal()
        val line = t.lineIdAt(0)
        t.selectWord(line, 1)
        assertEquals("foo", t.selectionText())

        t.selectWord(line, 5)
        assertEquals("bar_baz", t.selectionText())
    }

    @Test
    fun selectWordOnSpaceSelectsTheSpace() {
        val t = terminal()
        val line = t.lineIdAt(0)
        t.selectWord(line, 3)
        assertEquals(3..3, t.selectedColumns(line))
    }

    @Test
    fun selectLineSelectsWholeRow() {
        val t = terminal()
        t.selectLine(t.lineIdAt(1))
        assertEquals("second", t.selectionText())
    }

    @Test
    fun selectAllCoversEverythingRetained() {
        val t = terminal()
        t.selectAll()
        assertEquals("foo bar_baz\nsecond\nthird", t.selectionText())
    }

    @Test
    fun selectionSurvivesScrolling() {
        val t = Terminal(columns = 5, rows = 2, scrollbackLimit = 100)
        t.write("AAAAA\r\nBBBBB\r\nCCCCC\r\nDDDDD")
        val line = t.normalBuffer.scrollback.line(0).id
        t.setSelection(TerminalPosition(line, 0), TerminalPosition(line, 4))
        t.scrollView(2)
        assertTrue(t.isSelected(line, 2))
        assertEquals("AAAAA", t.selectionText())
    }

    @Test
    fun noSelectionMeansNullText() {
        val t = terminal()
        assertNull(t.selectionText())
        // The ends of a selection are inclusive, so a selection whose start
        // equals its end covers exactly one cell and copies it.
        t.setSelection(TerminalPosition(t.lineIdAt(0), 1), TerminalPosition(t.lineIdAt(0), 1))
        assertEquals(t.lineAt(0).text(1, 2, trimEnd = false), t.selectionText())
    }

    @Test
    fun clearSelection() {
        val t = terminal()
        t.setSelection(TerminalPosition(t.lineIdAt(0), 0), TerminalPosition(t.lineIdAt(0), 2))
        t.clearSelection()
        assertNull(t.selection)
        assertNull(t.selectionText())
    }

    @Test
    fun terminalPositionOrdersByLineThenColumn() {
        assertTrue(TerminalPosition(1, 0) < TerminalPosition(2, 0))
        assertTrue(TerminalPosition(1, 0) < TerminalPosition(1, 1))
        assertEquals(0, TerminalPosition(1, 1).compareTo(TerminalPosition(1, 1)))
    }
}
