package cn.enaium.terminal.parser

import cn.enaium.terminal.core.Terminal
import cn.enaium.terminal.core.TerminalAttributes
import cn.enaium.terminal.core.TerminalColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun terminal() = Terminal(columns = 20, rows = 4, scrollbackLimit = 100)

class SgrTest {

    private fun sgr(sequence: String): Terminal {
        val t = terminal()
        VTParser(t).feed(sequence)
        return t
    }

    @Test
    fun resetPenOnEmptyOrZero() {
        val t = sgr("\u001B[1;31m")
        assertTrue(t.attributes.bold)
        VTParser(t).feed("\u001B[m")
        assertEquals(TerminalAttributes.None, t.attributes)
        assertEquals(TerminalColor.Default, t.foreground)

        VTParser(t).feed("\u001B[1;31m\u001B[0m")
        assertEquals(TerminalAttributes.None, t.attributes)
        assertEquals(TerminalColor.Default, t.foreground)
    }

    @Test
    fun intensityAndStyleAttributes() {
        val t = sgr("\u001B[1m")
        assertTrue(t.attributes.bold)
        VTParser(t).feed("\u001B[2m")
        assertTrue(t.attributes.dim)
        VTParser(t).feed("\u001B[3m")
        assertTrue(t.attributes.italic)
        VTParser(t).feed("\u001B[5m")
        assertTrue(t.attributes.blink)
        VTParser(t).feed("\u001B[7m")
        assertTrue(t.attributes.inverse)
        VTParser(t).feed("\u001B[8m")
        assertTrue(t.attributes.hidden)
        VTParser(t).feed("\u001B[9m")
        assertTrue(t.attributes.strikethrough)
    }

    @Test
    fun attributeResets() {
        val t = sgr("\u001B[1;2;3;4;5;7;8;9m")
        VTParser(t).feed("\u001B[22m")
        assertFalse(t.attributes.bold)
        assertFalse(t.attributes.dim)
        VTParser(t).feed("\u001B[23m")
        assertFalse(t.attributes.italic)
        VTParser(t).feed("\u001B[24m")
        assertFalse(t.attributes.anyUnderline)
        VTParser(t).feed("\u001B[25m")
        assertFalse(t.attributes.blink)
        VTParser(t).feed("\u001B[27m")
        assertFalse(t.attributes.inverse)
        VTParser(t).feed("\u001B[28m")
        assertFalse(t.attributes.hidden)
        VTParser(t).feed("\u001B[29m")
        assertFalse(t.attributes.strikethrough)
    }

    @Test
    fun basicForegroundAndBackground() {
        val t = sgr("\u001B[31;44m")
        assertEquals(TerminalColor.indexed(1), t.foreground)
        assertEquals(TerminalColor.indexed(4), t.background)
        VTParser(t).feed("\u001B[39;49m")
        assertEquals(TerminalColor.Default, t.foreground)
        assertEquals(TerminalColor.Default, t.background)
    }

    @Test
    fun brightForegroundAndBackground() {
        val t = sgr("\u001B[91;104m")
        assertEquals(TerminalColor.indexed(9), t.foreground)
        assertEquals(TerminalColor.indexed(12), t.background)
    }

    @Test
    fun indexedExtendedColors() {
        val t = sgr("\u001B[38;5;196m")
        assertEquals(TerminalColor.indexed(196), t.foreground)
        VTParser(t).feed("\u001B[48;5;21m")
        assertEquals(TerminalColor.indexed(21), t.background)
    }

    @Test
    fun rgbExtendedColors() {
        val t = sgr("\u001B[38;2;10;20;30m")
        assertEquals(TerminalColor.rgb(10, 20, 30), t.foreground)
        VTParser(t).feed("\u001B[48;2;1;2;3m")
        assertEquals(TerminalColor.rgb(1, 2, 3), t.background)
    }

    @Test
    fun colonFormRgbWithColorSpace() {
        val t = sgr("\u001B[38:2::10:20:30m")
        assertEquals(TerminalColor.rgb(10, 20, 30), t.foreground)
    }

    @Test
    fun colonFormIndexed() {
        val t = sgr("\u001B[38:5:196m")
        assertEquals(TerminalColor.indexed(196), t.foreground)
    }

    @Test
    fun colonFormUnderlineStyles() {
        val t = sgr("\u001B[4:3m")
        assertTrue(t.attributes.curlyUnderline)
        VTParser(t).feed("\u001B[4:2m")
        assertTrue(t.attributes.doubleUnderline)
        assertFalse(t.attributes.curlyUnderline)
        VTParser(t).feed("\u001B[4:4m")
        assertTrue(t.attributes.dottedUnderline)
        VTParser(t).feed("\u001B[4:5m")
        assertTrue(t.attributes.dashedUnderline)
        VTParser(t).feed("\u001B[4:0m")
        assertFalse(t.attributes.anyUnderline)
    }

    @Test
    fun plainUnderlineAndDoubleUnderlineParam() {
        val t = sgr("\u001B[4m")
        assertTrue(t.attributes.underline)
        VTParser(t).feed("\u001B[21m")
        assertTrue(t.attributes.doubleUnderline)
    }

    @Test
    fun underlineColor() {
        val t = sgr("\u001B[58;5;100m")
        assertEquals(TerminalColor.indexed(100), t.underlineColor)
        VTParser(t).feed("\u001B[58;2;1;2;3m")
        assertEquals(TerminalColor.rgb(1, 2, 3), t.underlineColor)
        VTParser(t).feed("\u001B[59m")
        assertEquals(TerminalColor.Default, t.underlineColor)
    }

    @Test
    fun emptyParameterMeansZero() {
        val t = sgr("\u001B[1;31m")
        VTParser(t).feed("\u001B[;m")
        assertEquals(TerminalAttributes.None, t.attributes)
        assertEquals(TerminalColor.Default, t.foreground)
    }

    @Test
    fun malformedExtendedColorIsIgnored() {
        val t = sgr("\u001B[38;5m")
        assertEquals(TerminalColor.Default, t.foreground)
        VTParser(t).feed("\u001B[38;9;1m")
        assertEquals(TerminalColor.Default, t.foreground)
    }

    @Test
    fun sgrIsStoredInCells() {
        val t = sgr("\u001B[1;31;44m")
        t.write("A")
        val cell = t.lineAt(0).cell(0)
        assertTrue(cell.attributes.bold)
        assertEquals(TerminalColor.indexed(1), cell.foreground)
        assertEquals(TerminalColor.indexed(4), cell.background)
    }
}
