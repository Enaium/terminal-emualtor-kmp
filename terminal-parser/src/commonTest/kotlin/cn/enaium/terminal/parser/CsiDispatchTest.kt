package cn.enaium.terminal.parser

import cn.enaium.terminal.core.Terminal
import cn.enaium.terminal.core.TerminalCursorShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun terminal(columns: Int = 20, rows: Int = 6) = Terminal(columns, rows, scrollbackLimit = 100)

class CsiDispatchTest {

    @Test
    fun cursorMovements() {
        val t = terminal()
        val p = VTParser(t)
        t.setCursor(3, 3)
        p.feed("\u001B[2A")
        assertEquals(1, t.cursorRow)
        p.feed("\u001B[1B")
        assertEquals(2, t.cursorRow)
        p.feed("\u001B[3C")
        assertEquals(6, t.cursorColumn)
        p.feed("\u001B[2D")
        assertEquals(4, t.cursorColumn)
    }

    @Test
    fun cursorNextAndPreviousLine() {
        val t = terminal()
        val p = VTParser(t)
        t.setCursor(1, 5)
        p.feed("\u001B[2E")
        assertEquals(3, t.cursorRow)
        assertEquals(0, t.cursorColumn)
        p.feed("\u001B[1F")
        assertEquals(2, t.cursorRow)
        assertEquals(0, t.cursorColumn)
    }

    @Test
    fun cursorHorizontalAbsolute() {
        val t = terminal()
        val p = VTParser(t)
        p.feed("\u001B[5G")
        assertEquals(4, t.cursorColumn)
        p.feed("\u001B[7`")
        assertEquals(6, t.cursorColumn)
    }

    @Test
    fun cursorPosition() {
        val t = terminal()
        val p = VTParser(t)
        p.feed("\u001B[3;4H")
        assertEquals(2, t.cursorRow)
        assertEquals(3, t.cursorColumn)
        p.feed("\u001B[2;1f")
        assertEquals(1, t.cursorRow)
        assertEquals(0, t.cursorColumn)
    }

    @Test
    fun verticalPositionAbsolute() {
        val t = terminal()
        val p = VTParser(t)
        t.setCursor(0, 4)
        p.feed("\u001B[3d")
        assertEquals(2, t.cursorRow)
        assertEquals(4, t.cursorColumn)
    }

    @Test
    fun eraseInDisplayAndLine() {
        val t = terminal(columns = 5, rows = 3)
        val p = VTParser(t)
        t.writeLines("aaaaa", "bbbbb", "ccccc")
        t.setCursor(1, 2)
        p.feed("\u001B[0J")
        assertEquals("bb   ", t.lineAt(1).text(0, 5, trimEnd = false))
        assertEquals("     ", t.lineAt(2).text(0, 5, trimEnd = false))

        t.writeLines("aaaaa", "bbbbb", "ccccc")
        t.setCursor(0, 2)
        p.feed("\u001B[1K")
        assertEquals("   aa", t.lineAt(0).text(0, 5, trimEnd = false))
        assertEquals("bbbbb", t.lineAt(1).text(0, 5, trimEnd = false))
    }

    @Test
    fun insertDeleteAndEraseCharacters() {
        val t = terminal(columns = 10, rows = 2)
        val p = VTParser(t)
        t.write("abcdef")
        t.setCursor(0, 1)
        p.feed("\u001B[2@")
        assertEquals("a  bcdef", t.lineAt(0).text(0, 10))

        t.eraseInDisplay(2)
        t.setCursor(0, 0)
        t.write("abcdef")
        t.setCursor(0, 1)
        p.feed("\u001B[2P")
        assertEquals("adef", t.lineAt(0).text(0, 10))

        t.eraseInDisplay(2)
        t.setCursor(0, 0)
        t.write("abcdef")
        t.setCursor(0, 1)
        p.feed("\u001B[3X")
        assertEquals("a   ef", t.lineAt(0).text(0, 10))
    }

    @Test
    fun insertAndDeleteLines() {
        val t = terminal(columns = 5, rows = 5)
        val p = VTParser(t)
        t.writeLines("AAAAA", "BBBBB", "CCCCC", "DDDDD", "EEEEE")
        t.setCursor(2, 0)
        p.feed("\u001B[1L")
        assertEquals("BBBBB", t.lineAt(1).text(0, 5))
        assertEquals("", t.lineAt(2).text(0, 5))

        t.eraseInDisplay(2)
        t.writeLines("AAAAA", "BBBBB", "CCCCC", "DDDDD", "EEEEE")
        t.setCursor(1, 0)
        p.feed("\u001B[1M")
        assertEquals("CCCCC", t.lineAt(1).text(0, 5))
    }

    @Test
    fun scrollUpAndDown() {
        val t = terminal(columns = 5, rows = 3)
        val p = VTParser(t)
        t.writeLines("AAAAA", "BBBBB", "CCCCC")
        p.feed("\u001B[S")
        assertEquals("BBBBB", t.lineAt(0).text(0, 5))
        assertEquals("CCCCC", t.lineAt(1).text(0, 5))

        t.eraseInDisplay(2)
        t.writeLines("AAAAA", "BBBBB", "CCCCC")
        p.feed("\u001B[T")
        assertEquals("", t.lineAt(0).text(0, 5))
        assertEquals("AAAAA", t.lineAt(1).text(0, 5))
    }

    @Test
    fun setScrollRegion() {
        val t = terminal(columns = 10, rows = 6)
        val p = VTParser(t)
        p.feed("\u001B[2;4r")
        assertEquals(1, t.scrollTop)
        assertEquals(3, t.scrollBottom)
        assertEquals(0, t.cursorRow)
    }

    @Test
    fun repeatLastCharacter() {
        val t = terminal()
        val p = VTParser(t)
        t.write("x")
        p.feed("\u001B[3b")
        assertEquals("xxxx", t.lineAt(0).text(0, 20))
    }

    @Test
    fun clearTabStops() {
        val t = terminal(columns = 20, rows = 2)
        val p = VTParser(t)
        p.feed("\u001B[3g")
        p.feed("\u001B[I")
        assertEquals(19, t.cursorColumn)
    }

    @Test
    fun setAndClearSingleTabStop() {
        val t = terminal(columns = 20, rows = 2)
        val p = VTParser(t)
        t.setCursor(0, 4)
        p.feed("\u001BH") // HTS: set a stop at column 4
        t.setCursor(0, 0)
        p.feed("\u001B[I")
        assertEquals(4, t.cursorColumn)

        p.feed("\u001B[0g") // TBC 0: clear the stop at the cursor
        t.setCursor(0, 0)
        p.feed("\u001B[I")
        assertEquals(8, t.cursorColumn)
    }

    @Test
    fun backTab() {
        val t = terminal(columns = 30, rows = 2)
        val p = VTParser(t)
        t.setCursor(0, 20)
        p.feed("\u001B[Z")
        assertEquals(16, t.cursorColumn)
    }

    @Test
    fun cursorShape() {
        val t = terminal()
        val p = VTParser(t)
        p.feed("\u001B[3 q")
        assertEquals(TerminalCursorShape.BAR, t.cursorStyle.shape)
        assertTrue(t.cursorStyle.blink)
        p.feed("\u001B[4 q")
        assertEquals(TerminalCursorShape.BAR, t.cursorStyle.shape)
        assertFalse(t.cursorStyle.blink)
    }

    @Test
    fun decPrivateModesSetAndReset() {
        val t = terminal()
        val p = VTParser(t)
        p.feed("\u001B[?25l")
        assertFalse(t.modes.cursorVisible)
        p.feed("\u001B[?25h")
        assertTrue(t.modes.cursorVisible)
        p.feed("\u001B[?2004h")
        assertTrue(t.modes.bracketedPaste)
    }

    @Test
    fun ansiModesSetAndReset() {
        val t = terminal()
        val p = VTParser(t)
        p.feed("\u001B[4h")
        assertTrue(t.modes.insert)
        p.feed("\u001B[4l")
        assertFalse(t.modes.insert)
        p.feed("\u001B[20h")
        assertTrue(t.modes.newLine)
    }

    @Test
    fun saveAndRestoreCursorViaCsi() {
        val t = terminal()
        val p = VTParser(t)
        t.setCursor(2, 3)
        p.feed("\u001B[s")
        t.setCursor(0, 0)
        p.feed("\u001B[u")
        assertEquals(2, t.cursorRow)
        assertEquals(3, t.cursorColumn)
    }

    @Test
    fun unknownCsiIsIgnored() {
        val t = terminal()
        val p = VTParser(t)
        p.feed("\u001B[99z")
        p.feed("abc")
        assertEquals("abc", t.lineAt(0).text(0, 20))
    }
}
