package cn.enaium.terminal.parser

import cn.enaium.terminal.core.Terminal
import cn.enaium.terminal.core.TerminalCell
import cn.enaium.terminal.core.TerminalColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun terminal() = Terminal(columns = 20, rows = 4, scrollbackLimit = 100)

class ParserChunkingTest {

    @Test
    fun longEscapeSequenceFedByteByByte() {
        val t = terminal()
        val p = VTParser(t)
        val bytes = "\u001B[38;5;196;48;2;1;2;3m".encodeToByteArray()
        for (byte in bytes) {
            p.feed(byteArrayOf(byte))
        }
        assertEquals(TerminalColor.indexed(196), t.foreground)
        assertEquals(TerminalColor.rgb(1, 2, 3), t.background)
    }

    @Test
    fun csiSplitAtEveryBoundary() {
        val t = terminal()
        val p = VTParser(t)
        p.feed("\u001B[")
        p.feed("1;2")
        p.feed("H")
        assertEquals(0, t.cursorRow)
        assertEquals(1, t.cursorColumn)
    }

    @Test
    fun utf8SplitAcrossChunkBoundaries() {
        val t = terminal()
        val p = VTParser(t)
        for (byte in "\u4E2D".encodeToByteArray()) {
            p.feed(byteArrayOf(byte))
        }
        assertEquals(0x4E2D, t.lineAt(0).codepointAt(0))
        assertEquals(2, t.lineAt(0).widthAt(0))
        assertEquals(TerminalCell.WIDE_CONTINUATION, t.lineAt(0).codepointAt(1))
    }

    @Test
    fun feedHonoursTheLengthArgument() {
        val t = terminal()
        val p = VTParser(t)
        val bytes = "ab\u001B[31mcd".encodeToByteArray()
        p.feed(bytes, 2)
        assertEquals("ab", t.lineAt(0).text(0, 20))
        assertEquals(TerminalColor.Default, t.foreground)

        p.feed(bytes.copyOfRange(2, bytes.size))
        assertEquals("abcd", t.lineAt(0).text(0, 20))
        assertEquals(TerminalColor.indexed(1), t.foreground)
    }

    @Test
    fun oscSplitAcrossChunks() {
        val t = terminal()
        val p = VTParser(t)
        p.feed("\u001B]2;he")
        p.feed("llo")
        p.feed("\u0007")
        assertEquals("hello", t.title)
    }

    @Test
    fun canAbortsCsiSequence() {
        val t = terminal()
        val p = VTParser(t)
        p.feed("\u001B[31")
        p.feed("\u0018")
        p.feed("X")
        assertEquals("X", t.lineAt(0).text(0, 20))
        assertEquals(TerminalColor.Default, t.foreground)
    }

    @Test
    fun subAbortsOscSequence() {
        val t = terminal()
        val p = VTParser(t)
        p.feed("\u001B]2;hello\u001A")
        p.feed("X")
        assertNull(t.title)
        assertEquals("X", t.lineAt(0).text(0, 20))
    }

    @Test
    fun resetDropsPartialSequence() {
        val t = terminal()
        val p = VTParser(t)
        p.feed("\u001B[31")
        p.reset()
        p.feed("Y")
        assertEquals("Y", t.lineAt(0).text(0, 20))
        assertEquals(TerminalColor.Default, t.foreground)
    }

    @Test
    fun unknownSequencesAreIgnored() {
        val t = terminal()
        val p = VTParser(t)
        p.feed("\u001B[?9999h")
        p.feed("\u001B]999;x\u0007")
        p.feed("\u001B_ignored\u001B\\")
        p.feed("z")
        assertEquals("z", t.lineAt(0).text(0, 20))
        assertTrue(t.modes.cursorVisible)
    }
}
