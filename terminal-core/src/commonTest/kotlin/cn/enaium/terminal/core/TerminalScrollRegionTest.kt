package cn.enaium.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun Terminal.rowText(row: Int, start: Int = 0, end: Int = -1): String {
    val e = if (end < 0) columns else end
    return lineAt(row).text(start, e)
}

class TerminalScrollRegionTest {

    private fun fiveRows(): Terminal {
        val t = Terminal(columns = 5, rows = 5, scrollbackLimit = 100)
        t.writeLines("AAAAA", "BBBBB", "CCCCC", "DDDDD", "EEEEE")
        return t
    }

    @Test
    fun setScrollRegionClampsAndHomeTheCursor() {
        val t = fiveRows()
        t.setScrollRegion(2, 4)
        assertEquals(1, t.scrollTop)
        assertEquals(3, t.scrollBottom)
        assertEquals(0, t.cursorRow)
        assertEquals(0, t.cursorColumn)
    }

    @Test
    fun invertedScrollRegionResetsToFullScreen() {
        val t = fiveRows()
        t.setScrollRegion(4, 2)
        assertEquals(0, t.scrollTop)
        assertEquals(4, t.scrollBottom)
    }

    @Test
    fun lineFeedAtRegionBottomScrollsOnlyTheRegion() {
        val t = fiveRows()
        t.setScrollRegion(2, 4)
        t.setCursor(3, 0)
        t.lineFeed()
        assertEquals("AAAAA", t.rowText(0))
        assertEquals("CCCCC", t.rowText(1))
        assertEquals("DDDDD", t.rowText(2))
        assertEquals("", t.rowText(3))
        assertEquals("EEEEE", t.rowText(4))
    }

    @Test
    fun insertLinesShiftsRegionDown() {
        val t = fiveRows()
        t.setScrollRegion(2, 4)
        t.setCursor(2, 0)
        t.insertLines(1)
        assertEquals("AAAAA", t.rowText(0))
        assertEquals("BBBBB", t.rowText(1))
        assertEquals("", t.rowText(2))
        assertEquals("CCCCC", t.rowText(3))
        assertEquals("EEEEE", t.rowText(4))
    }

    @Test
    fun deleteLinesShiftsRegionUp() {
        val t = fiveRows()
        t.setScrollRegion(2, 4)
        t.setCursor(1, 0)
        t.deleteLines(1)
        assertEquals("AAAAA", t.rowText(0))
        assertEquals("CCCCC", t.rowText(1))
        assertEquals("DDDDD", t.rowText(2))
        assertEquals("", t.rowText(3))
        assertEquals("EEEEE", t.rowText(4))
    }

    @Test
    fun insertAndDeleteLinesOutsideRegionAreIgnored() {
        val t = fiveRows()
        t.setScrollRegion(2, 4)
        t.setCursor(0, 0)
        t.insertLines(1)
        assertEquals("AAAAA", t.rowText(0))
        t.deleteLines(1)
        assertEquals("AAAAA", t.rowText(0))
    }

    @Test
    fun scrollUpDiscardsTopOfRegion() {
        val t = fiveRows()
        t.setScrollRegion(2, 4)
        t.scrollUp(2)
        assertEquals("AAAAA", t.rowText(0))
        assertEquals("DDDDD", t.rowText(1))
        assertEquals("", t.rowText(2))
        assertEquals("", t.rowText(3))
        assertEquals("EEEEE", t.rowText(4))
    }

    @Test
    fun scrollDownInsertsAtTopOfRegion() {
        val t = fiveRows()
        t.setScrollRegion(2, 4)
        t.scrollDown(1)
        assertEquals("AAAAA", t.rowText(0))
        assertEquals("", t.rowText(1))
        assertEquals("BBBBB", t.rowText(2))
        assertEquals("CCCCC", t.rowText(3))
        assertEquals("EEEEE", t.rowText(4))
    }

    @Test
    fun reverseLineFeedAtRegionTopScrollsDown() {
        val t = fiveRows()
        t.setScrollRegion(2, 4)
        t.setCursor(1, 0)
        t.reverseLineFeed()
        assertEquals("", t.rowText(1))
        assertEquals("BBBBB", t.rowText(2))
        assertEquals("CCCCC", t.rowText(3))
    }

    @Test
    fun reverseLineFeedAboveTopMovesCursorUp() {
        val t = fiveRows()
        t.setCursor(2, 0)
        t.reverseLineFeed()
        assertEquals(1, t.cursorRow)
        assertEquals("BBBBB", t.rowText(1))
    }

    @Test
    fun tabStopsDefaultToEveryEightColumns() {
        val t = Terminal(columns = 30, rows = 2)
        t.tab()
        assertEquals(8, t.cursorColumn)
        t.tab()
        assertEquals(16, t.cursorColumn)
    }

    @Test
    fun setTabStopThenTab() {
        val t = Terminal(columns = 30, rows = 2)
        t.setCursor(0, 3)
        t.setTabStop()
        t.setCursor(0, 0)
        t.tab()
        assertEquals(3, t.cursorColumn)
    }

    @Test
    fun clearTabStopAtCursor() {
        val t = Terminal(columns = 30, rows = 2)
        t.setCursor(0, 8)
        t.clearTabStop(0)
        t.setCursor(0, 0)
        t.tab()
        assertEquals(16, t.cursorColumn)
    }

    @Test
    fun clearAllTabStops() {
        val t = Terminal(columns = 10, rows = 2)
        t.clearTabStop(3)
        t.tab()
        assertEquals(9, t.cursorColumn)
    }

    @Test
    fun backTabMovesToPreviousStop() {
        val t = Terminal(columns = 30, rows = 2)
        t.setCursor(0, 20)
        t.backTab(1)
        assertEquals(16, t.cursorColumn)
        t.backTab(2)
        assertEquals(0, t.cursorColumn)
    }

    @Test
    fun tabAtOrPastLastStopGoesToLastColumn() {
        val t = Terminal(columns = 10, rows = 2)
        t.setCursor(0, 9)
        t.tab()
        assertEquals(9, t.cursorColumn)
    }

    @Test
    fun originModeConstrainsCursorMovement() {
        val t = fiveRows()
        t.setScrollRegion(3, 5)
        t.setPrivateMode(6, true)
        assertEquals(2, t.cursorRow)
        t.cursorUp(1)
        assertEquals(2, t.cursorRow)
        t.cursorDown(10)
        assertEquals(4, t.cursorRow)
        t.cursorTo(1, 1)
        assertEquals(2, t.cursorRow)
        t.setPrivateMode(6, false)
        t.cursorTo(1, 1)
        assertEquals(0, t.cursorRow)
    }

    @Test
    fun saveAndRestoreCursor() {
        val t = fiveRows()
        t.setCursor(1, 3)
        t.setForeground(TerminalColor.indexed(4))
        t.saveCursor()
        t.setCursor(4, 0)
        t.setForeground(TerminalColor.indexed(1))
        t.restoreCursor()
        assertEquals(1, t.cursorRow)
        assertEquals(3, t.cursorColumn)
        assertEquals(TerminalColor.indexed(4), t.foreground)
    }

    @Test
    fun restoreWithoutSaveHomesTheCursor() {
        val t = fiveRows()
        t.setCursor(3, 3)
        t.restoreCursor()
        assertEquals(0, t.cursorRow)
        assertEquals(0, t.cursorColumn)
    }

    @Test
    fun cursorMovementClampsToScreen() {
        val t = Terminal(columns = 10, rows = 5)
        t.cursorUp(10)
        assertEquals(0, t.cursorRow)
        t.cursorDown(10)
        assertEquals(4, t.cursorRow)
        t.cursorForward(100)
        assertEquals(9, t.cursorColumn)
        t.cursorBackward(100)
        assertEquals(0, t.cursorColumn)
    }

    @Test
    fun cursorNextAndPreviousLineGoToColumnZero() {
        val t = Terminal(columns = 10, rows = 5)
        t.setCursor(1, 5)
        t.cursorNextLine(2)
        assertEquals(3, t.cursorRow)
        assertEquals(0, t.cursorColumn)
        t.cursorPreviousLine(1)
        assertEquals(2, t.cursorRow)
        assertEquals(0, t.cursorColumn)
    }

    @Test
    fun cursorToColumnIsOneBased() {
        val t = Terminal(columns = 10, rows = 2)
        t.cursorToColumn(4)
        assertEquals(3, t.cursorColumn)
    }

    @Test
    fun cursorToRowIsOneBased() {
        val t = Terminal(columns = 10, rows = 5)
        t.setCursor(0, 3)
        t.cursorToRow(3)
        assertEquals(2, t.cursorRow)
        assertEquals(3, t.cursorColumn)
    }

    @Test
    fun lineIsBlankAfterEraseAndContentLengthTracksText() {
        val t = Terminal(columns = 10, rows = 2)
        t.write("abc")
        assertEquals(3, t.lineAt(0).contentLength)
        t.eraseInLine(2)
        assertTrue(t.lineAt(0).isBlank)
        assertEquals(0, t.lineAt(0).contentLength)
    }
}
