package cn.enaium.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Writes [text] at the cursor, then returns to column 0 and moves down one row. */
private fun Terminal.writeLineAndBreak(text: String) {
    write(text)
    carriageReturn()
    lineFeed()
}

class TerminalScrollbackTest {

    @Test
    fun linesScrolledOffTheTopGoToScrollback() {
        val t = Terminal(columns = 5, rows = 2, scrollbackLimit = 100)
        t.writeLineAndBreak("AAAAA")
        t.writeLineAndBreak("BBBBB")
        t.write("CCCCC")
        assertEquals(1, t.scrollbackSize)
        assertEquals("AAAAA", t.normalBuffer.scrollback.line(0).text(0, 5))
        assertEquals("BBBBB", t.lineAt(0).text(0, 5))
        assertEquals("CCCCC", t.lineAt(1).text(0, 5))
    }

    @Test
    fun scrollbackLimitDropsOldestLines() {
        val t = Terminal(columns = 5, rows = 1, scrollbackLimit = 2)
        t.writeLineAndBreak("AAAAA")
        t.writeLineAndBreak("BBBBB")
        t.writeLineAndBreak("CCCCC")
        t.writeLineAndBreak("DDDDD")
        t.write("EEEEE")
        assertEquals(2, t.scrollbackSize)
        assertEquals("CCCCC", t.normalBuffer.scrollback.line(0).text(0, 5))
        assertEquals("DDDDD", t.normalBuffer.scrollback.line(1).text(0, 5))
        assertEquals("EEEEE", t.lineAt(0).text(0, 5))
    }

    @Test
    fun shrinkingScrollbackLimitTrimsOldestLines() {
        val t = Terminal(columns = 5, rows = 1, scrollbackLimit = 10)
        t.writeLineAndBreak("AAAAA")
        t.writeLineAndBreak("BBBBB")
        t.writeLineAndBreak("CCCCC")
        t.write("DDDDD")
        assertEquals(3, t.scrollbackSize)
        t.setScrollbackLimit(1)
        assertEquals(1, t.scrollbackSize)
        assertEquals("CCCCC", t.normalBuffer.scrollback.line(0).text(0, 5))
    }

    @Test
    fun scrollViewShowsHistoryAndClamps() {
        val t = Terminal(columns = 5, rows = 2, scrollbackLimit = 100)
        t.writeLineAndBreak("AAAAA")
        t.writeLineAndBreak("BBBBB")
        t.write("CCCCC")
        assertEquals(0, t.scrollOffset)
        assertFalse(t.isScrolledBack)

        t.scrollView(1)
        assertEquals(1, t.scrollOffset)
        assertTrue(t.isScrolledBack)
        assertEquals("AAAAA", t.lineAt(0).text(0, 5))
        assertEquals("BBBBB", t.lineAt(1).text(0, 5))

        t.scrollView(100)
        assertEquals(1, t.scrollOffset)

        t.scrollView(-100)
        assertEquals(0, t.scrollOffset)
        assertEquals("BBBBB", t.lineAt(0).text(0, 5))
    }

    @Test
    fun scrollToBottomReturnsToLiveView() {
        val t = Terminal(columns = 5, rows = 2, scrollbackLimit = 100)
        t.writeLineAndBreak("AAAAA")
        t.writeLineAndBreak("BBBBB")
        t.write("CCCCC")
        t.scrollView(1)
        t.scrollToBottom()
        assertEquals(0, t.scrollOffset)
        assertEquals("CCCCC", t.lineAt(1).text(0, 5))
    }

    @Test
    fun viewStaysAnchoredWhileNewOutputArrives() {
        val t = Terminal(columns = 5, rows = 2, scrollbackLimit = 100)
        t.writeLineAndBreak("AAAAA")
        t.writeLineAndBreak("BBBBB")
        t.write("CCCCC")
        t.scrollView(1)
        val anchoredId = t.lineIdAt(0)
        assertEquals("AAAAA", t.lineAt(0).text(0, 5))

        // More output pushes another line into the scrollback.
        t.carriageReturn()
        t.lineFeed()
        t.write("DDDDD")
        assertEquals(2, t.scrollbackSize)
        assertEquals(anchoredId, t.lineIdAt(0))
        assertEquals("AAAAA", t.lineAt(0).text(0, 5))
    }

    @Test
    fun selectionJoinsSoftWrappedRowsWithoutNewline() {
        val t = Terminal(columns = 5, rows = 3, scrollbackLimit = 100)
        t.setCursor(0, 0)
        t.write("abcdefghij")
        t.setCursor(2, 0)
        t.write("klm")
        assertTrue(t.lineAt(0).wrapped)
        assertEquals("fghij", t.lineAt(1).text(0, 5))
        t.setSelection(
            TerminalPosition(t.lineIdAt(0), 0),
            TerminalPosition(t.lineIdAt(2), 2),
        )
        assertEquals("abcdefghij\nklm", t.selectionText())
    }

    @Test
    fun selectionSpansScrollbackAndScreen() {
        val t = Terminal(columns = 5, rows = 2, scrollbackLimit = 100)
        t.writeLineAndBreak("AAAAA")
        t.writeLineAndBreak("BBBBB")
        t.writeLineAndBreak("CCCCC")
        t.write("DDDDD")
        assertEquals(2, t.scrollbackSize)
        val first = t.normalBuffer.scrollback.line(0).id
        t.setSelection(TerminalPosition(first, 0), TerminalPosition(t.lineIdAt(1), 4))
        assertEquals("AAAAA\nBBBBB\nCCCCC\nDDDDD", t.selectionText())
    }

    @Test
    fun lineAtClampsOutOfRangeRows() {
        val t = Terminal(columns = 5, rows = 2, scrollbackLimit = 100)
        t.writeLines("AAAAA", "BBBBB")
        assertEquals("AAAAA", t.lineAt(-5).text(0, 5))
        assertEquals("BBBBB", t.lineAt(99).text(0, 5))
    }

    @Test
    fun rowOfLineIdFindsVisibleLine() {
        val t = Terminal(columns = 5, rows = 3, scrollbackLimit = 100)
        t.writeLines("AAAAA", "BBBBB", "CCCCC")
        assertEquals(1, t.rowOfLineId(t.lineIdAt(1)))
        assertEquals(-1, t.rowOfLineId(-1L))
    }
}
