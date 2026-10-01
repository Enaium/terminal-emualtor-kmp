package cn.enaium.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun Terminal.rowText(row: Int, start: Int = 0, end: Int = -1): String {
    val e = if (end < 0) columns else end
    return lineAt(row).text(start, e)
}

class TerminalScreenTest {

    private fun terminal(columns: Int = 10, rows: Int = 4) =
        Terminal(columns = columns, rows = rows, scrollbackLimit = 100)

    @Test
    fun plainWrite() {
        val t = terminal()
        t.write("hello")
        assertEquals("hello", t.rowText(0))
        assertEquals(0, t.cursorRow)
        assertEquals(5, t.cursorColumn)
    }

    @Test
    fun writeOverwritesExistingCells() {
        val t = terminal()
        t.write("hello")
        t.setCursor(0, 0)
        t.write("HE")
        assertEquals("HEllo", t.rowText(0))
    }

    @Test
    fun deferredWrapHappensOnlyOnNextCharacter() {
        val t = terminal(columns = 5, rows = 3)
        t.write("abcde")
        assertEquals(4, t.cursorColumn)
        assertEquals(0, t.cursorRow)
        assertFalse(t.lineAt(0).wrapped)
        t.write("f")
        assertEquals(1, t.cursorRow)
        assertEquals(1, t.cursorColumn)
        assertTrue(t.lineAt(0).wrapped)
        assertEquals("f", t.rowText(1))
    }

    @Test
    fun autoWrapDisabledOverwritesLastColumn() {
        val t = terminal(columns = 5, rows = 3)
        t.setPrivateMode(7, false)
        t.write("abcdef")
        assertEquals(0, t.cursorRow)
        assertEquals("abcdf", t.rowText(0))
    }

    @Test
    fun wrappingAtBottomScrollsTheScreen() {
        val t = terminal(columns = 5, rows = 2)
        t.write("abcdefghijk")
        assertEquals(1, t.scrollbackSize)
        assertEquals("abcde", t.normalBuffer.scrollback.line(0).text(0, 5))
        assertEquals("fghij", t.rowText(0))
        assertEquals("k", t.rowText(1))
    }

    @Test
    fun wideCharacterOccupiesTwoCells() {
        val t = terminal()
        t.write("\u4E2D")
        assertEquals(0x4E2D, t.lineAt(0).codepointAt(0))
        assertEquals(2, t.lineAt(0).widthAt(0))
        assertEquals(TerminalCell.WIDE_CONTINUATION, t.lineAt(0).codepointAt(1))
        assertEquals(0, t.lineAt(0).widthAt(1))
        assertEquals(2, t.cursorColumn)
        assertEquals("\u4E2D", t.rowText(0))
    }

    @Test
    fun wideCharacterWrapsWhenOnlyOneColumnLeft() {
        val t = terminal(columns = 4, rows = 2)
        t.write("abc\u4E2D")
        assertEquals("abc", t.rowText(0))
        assertTrue(t.lineAt(0).wrapped)
        assertEquals("\u4E2D", t.rowText(1))
        assertEquals(2, t.cursorColumn)
    }

    @Test
    fun wideCharacterAtLastColumnWithAutoWrapDisabledIsDropped() {
        val t = terminal(columns = 4, rows = 2)
        t.setPrivateMode(7, false)
        t.write("abc\u4E2D")
        assertEquals("abc", t.rowText(0))
        assertEquals("", t.rowText(1))
    }

    @Test
    fun combiningMarkMergesIntoPreviousCell() {
        val t = terminal()
        t.write("e\u0301")
        assertEquals(1, t.cursorColumn)
        assertEquals(1, t.lineAt(0).widthAt(0))
        assertEquals("e\u0301", t.rowText(0))
    }

    @Test
    fun combiningMarkWithoutBaseIsDropped() {
        val t = terminal()
        t.write("\u0301")
        assertEquals(0, t.cursorColumn)
        assertEquals("", t.rowText(0))
    }

    @Test
    fun zwjEmojiStaysInOneWideCell() {
        val t = terminal()
        t.write("\uD83D\uDC68\u200D\uD83D\uDC69")
        assertEquals(2, t.lineAt(0).widthAt(0))
        assertEquals(TerminalCell.WIDE_CONTINUATION, t.lineAt(0).codepointAt(1))
        assertEquals(2, t.cursorColumn)
        assertEquals("\uD83D\uDC68\u200D\uD83D\uDC69", t.rowText(0))
    }

    @Test
    fun variationSelector16WidensTextEmoji() {
        val t = terminal()
        t.write("\u2764\uFE0F")
        assertEquals(2, t.lineAt(0).widthAt(0))
        assertEquals(TerminalCell.WIDE_CONTINUATION, t.lineAt(0).codepointAt(1))
        assertEquals("\u2764\uFE0F", t.rowText(0))
    }

    @Test
    fun overwritingBaseHalfOfWideCharacterClearsContinuation() {
        val t = terminal()
        t.write("\u4E2D")
        t.setCursor(0, 0)
        t.write("a")
        assertEquals('a'.code, t.lineAt(0).codepointAt(0))
        assertTrue(t.lineAt(0).isCellBlank(1))
        assertEquals("a", t.rowText(0))
    }

    @Test
    fun overwritingContinuationHalfOfWideCharacterClearsBase() {
        val t = terminal()
        t.write("\u4E2D")
        t.setCursor(0, 1)
        t.write("b")
        assertTrue(t.lineAt(0).isCellBlank(0))
        assertEquals('b'.code, t.lineAt(0).codepointAt(1))
        assertEquals("b", t.rowText(0, 1))
    }

    @Test
    fun insertCharactersShiftsRight() {
        val t = terminal()
        t.write("abcdef")
        t.setCursor(0, 2)
        t.insertChars(2)
        assertEquals("ab  cdef", t.rowText(0))
    }

    @Test
    fun insertModeShiftsOnWrite() {
        val t = terminal()
        t.write("abc")
        t.setCursor(0, 1)
        t.setMode(4, true)
        t.write("X")
        assertEquals("aXbc", t.rowText(0))
    }

    @Test
    fun deleteCharactersShiftsLeft() {
        val t = terminal()
        t.write("abcdef")
        t.setCursor(0, 1)
        t.deleteChars(2)
        assertEquals("adef", t.rowText(0))
    }

    @Test
    fun eraseCharactersUsesCurrentBackground() {
        val t = terminal()
        t.write("abcdef")
        t.setBackground(TerminalColor.indexed(1))
        t.setCursor(0, 1)
        t.eraseChars(3)
        assertEquals("a   ef", t.rowText(0))
        for (column in 1..3) {
            assertEquals(0, t.lineAt(0).codepointAt(column))
            assertEquals(TerminalColor.indexed(1), t.lineAt(0).backgroundAt(column))
        }
    }

    @Test
    fun eraseInLineModes() {
        val t = terminal()
        t.write("abcdefghij")
        t.setCursor(0, 3)

        t.eraseInLine(0)
        assertEquals("abc", t.rowText(0))

        t.setCursor(0, 0)
        t.write("abcdefghij")
        t.setCursor(0, 3)
        t.eraseInLine(1)
        assertEquals("    efghij", t.rowText(0))

        t.setCursor(0, 0)
        t.write("abcdefghij")
        t.setCursor(0, 3)
        t.eraseInLine(2)
        assertEquals("", t.rowText(0))
    }

    @Test
    fun eraseInDisplayModes() {
        val t = terminal(columns = 5, rows = 3)
        t.writeLines("aaaaa", "bbbbb", "ccccc")

        t.setCursor(1, 2)
        t.eraseInDisplay(0)
        assertEquals("aaaaa", t.lineAt(0).text(0, 5, trimEnd = false))
        assertEquals("bb   ", t.lineAt(1).text(0, 5, trimEnd = false))
        assertEquals("     ", t.lineAt(2).text(0, 5, trimEnd = false))

        t.writeLines("aaaaa", "bbbbb", "ccccc")
        t.setCursor(1, 2)
        t.eraseInDisplay(1)
        assertEquals("     ", t.lineAt(0).text(0, 5, trimEnd = false))
        assertEquals("   bb", t.lineAt(1).text(0, 5, trimEnd = false))
        assertEquals("ccccc", t.lineAt(2).text(0, 5, trimEnd = false))

        t.eraseInDisplay(2)
        assertEquals("     ", t.lineAt(0).text(0, 5, trimEnd = false))
        assertEquals("     ", t.lineAt(1).text(0, 5, trimEnd = false))
        assertEquals("     ", t.lineAt(2).text(0, 5, trimEnd = false))
    }

    @Test
    fun eraseInDisplayThreeClearsScrollback() {
        val t = terminal(columns = 5, rows = 2)
        t.write("abcdefghijk")
        assertEquals(1, t.scrollbackSize)
        t.eraseInDisplay(3)
        assertEquals(0, t.scrollbackSize)
    }

    @Test
    fun eraseInLineUsesBackgroundColor() {
        val t = terminal()
        t.write("abcdef")
        t.setBackground(TerminalColor.rgb(0x11, 0x22, 0x33))
        t.setCursor(0, 3)
        t.eraseInLine(0)
        assertEquals(TerminalColor.rgb(0x11, 0x22, 0x33), t.lineAt(0).backgroundAt(3))
        assertEquals(0, t.lineAt(0).codepointAt(3))
    }

    @Test
    fun zeroCodePointIsIgnored() {
        val t = terminal()
        t.writeCodePoint(0)
        assertEquals(0, t.cursorColumn)
        assertEquals("", t.rowText(0))
    }

    @Test
    fun cellSnapshotReflectsStoredState() {
        val t = terminal()
        t.setForeground(TerminalColor.indexed(2))
        t.setBackground(TerminalColor.rgb(1, 2, 3))
        t.setAttributes(TerminalAttributes.None.with(TerminalAttributes.BOLD, true))
        t.write("A")
        val cell = t.lineAt(0).cell(0)
        assertEquals('A'.code, cell.codepoint)
        assertEquals(1, cell.width)
        assertEquals(TerminalColor.indexed(2), cell.foreground)
        assertEquals(TerminalColor.rgb(1, 2, 3), cell.background)
        assertTrue(cell.attributes.bold)
        assertEquals("A", cell.text())
    }

    @Test
    fun repeatLastCharacterRepeatsTheLastPrintedCodePoint() {
        val t = terminal()
        t.write("x")
        t.repeatLastCharacter(3)
        assertEquals("xxxx", t.rowText(0))
    }

    @Test
    fun lineFeedAndCarriageReturn() {
        val t = terminal()
        t.write("ab")
        t.carriageReturn()
        assertEquals(0, t.cursorColumn)
        t.lineFeed()
        assertEquals(1, t.cursorRow)
        t.write("cd")
        assertEquals("ab", t.rowText(0))
        assertEquals("cd", t.rowText(1))
    }

    @Test
    fun backspaceCancelsPendingWrap() {
        val t = terminal(columns = 5, rows = 2)
        t.write("abcde")
        assertEquals(4, t.cursorColumn)
        t.backspace()
        assertEquals(4, t.cursorColumn)
        t.write("X")
        assertEquals("abcdX", t.rowText(0))
    }

    @Test
    fun newLineModeMakesLineFeedReturnCarriage() {
        val t = terminal()
        t.setMode(20, true)
        t.write("ab")
        t.lineFeed()
        assertEquals(0, t.cursorColumn)
        assertEquals(1, t.cursorRow)
    }
}
