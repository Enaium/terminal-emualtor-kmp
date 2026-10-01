package cn.enaium.terminal.parser

import cn.enaium.terminal.core.Terminal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun terminal(columns: Int = 10, rows: Int = 4) = Terminal(columns, rows, scrollbackLimit = 100)

class ParserEscapeTest {

    @Test
    fun verticalTabAndFormFeedActAsLineFeed() {
        val t = terminal()
        val p = VTParser(t)
        p.feed("a\u000Bb")
        assertEquals("a", t.lineAt(0).text(0, 10))
        assertEquals(" b", t.lineAt(1).text(0, 10))

        val t2 = terminal()
        VTParser(t2).feed("a\u000Cb")
        assertEquals("a", t2.lineAt(0).text(0, 10))
        assertEquals(" b", t2.lineAt(1).text(0, 10))
    }

    @Test
    fun decSpecialGraphicsInG0() {
        val t = terminal()
        val p = VTParser(t)
        p.feed("\u001B(0")
        p.feed("q")
        assertEquals(0x2500, t.lineAt(0).codepointAt(0))
    }

    @Test
    fun decSpecialGraphicsViaShiftOutAndShiftIn() {
        val t = terminal()
        val p = VTParser(t)
        p.feed("\u001B)0")
        p.feed("\u000E") // SO selects G1
        p.feed("q")
        assertEquals(0x2500, t.lineAt(0).codepointAt(0))
        p.feed("\u000F") // SI selects G0 (ASCII)
        p.feed("q")
        assertEquals('q'.code, t.lineAt(0).codepointAt(1))
    }

    @Test
    fun ukCharsetReplacesHash() {
        val t = terminal()
        val p = VTParser(t)
        p.feed("\u001B(A")
        p.feed("#")
        assertEquals(0x00A3, t.lineAt(0).codepointAt(0))
    }

    @Test
    fun screenAlignmentTest() {
        val t = terminal(columns = 5, rows = 2)
        val p = VTParser(t)
        p.feed("\u001B#8")
        assertEquals("EEEEE", t.lineAt(0).text(0, 5))
        assertEquals("EEEEE", t.lineAt(1).text(0, 5))
        assertEquals(0, t.cursorRow)
        assertEquals(0, t.cursorColumn)
    }

    @Test
    fun escapeSaveAndRestoreCursor() {
        val t = terminal()
        val p = VTParser(t)
        t.setCursor(2, 3)
        p.feed("\u001B7")
        t.setCursor(0, 0)
        p.feed("\u001B8")
        assertEquals(2, t.cursorRow)
        assertEquals(3, t.cursorColumn)
    }

    @Test
    fun escapeLineFeedAndReverseLineFeed() {
        val t = terminal()
        val p = VTParser(t)
        p.feed("a")
        p.feed("\u001BD")
        assertEquals(1, t.cursorRow)
        p.feed("\u001BM")
        assertEquals(0, t.cursorRow)

        p.feed("b")
        p.feed("\u001BE")
        assertEquals(1, t.cursorRow)
        assertEquals(0, t.cursorColumn)
    }

    @Test
    fun applicationKeypadModes() {
        val t = terminal()
        val p = VTParser(t)
        p.feed("\u001B=")
        assertTrue(t.modes.applicationKeypad)
        p.feed("\u001B>")
        assertFalse(t.modes.applicationKeypad)
    }

    @Test
    fun fullResetSequence() {
        val t = terminal()
        val p = VTParser(t)
        t.write("hello")
        p.feed("\u001B[1;31m")
        p.feed("\u001Bc")
        assertEquals("", t.lineAt(0).text(0, 10))
        assertEquals(0, t.cursorRow)
        assertEquals(0, t.cursorColumn)
        assertEquals(cn.enaium.terminal.core.TerminalColor.Default, t.foreground)
        p.feed("x")
        assertEquals("x", t.lineAt(0).text(0, 10))
    }

    @Test
    fun sosAndPmApcStringsAreDiscarded() {
        val t = terminal()
        val p = VTParser(t)
        p.feed("\u001BXignored\u001B\\")
        p.feed("a")
        p.feed("\u001B^ignored\u001B\\")
        p.feed("b")
        assertEquals("ab", t.lineAt(0).text(0, 10))
    }
}
