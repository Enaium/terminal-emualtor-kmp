package cn.enaium.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalInputTest {

    private fun terminal(): Terminal = Terminal(columns = 20, rows = 5, scrollbackLimit = 100)

    private fun decode(bytes: ByteArray?): String? = bytes?.decodeToString()

    @Test
    fun plainCharacter() {
        val input = TerminalInput(terminal())
        assertEquals("a", decode(input.key(TerminalKey.Character('a'))))
    }

    @Test
    fun ctrlCharacters() {
        val input = TerminalInput(terminal())
        assertEquals("\u0003", decode(input.key(TerminalKey.Character('c'), ctrl = true)))
        assertEquals("\u0004", decode(input.key(TerminalKey.Character('d'), ctrl = true)))
        assertEquals("\u000C", decode(input.key(TerminalKey.Character('l'), ctrl = true)))
        assertEquals("\u001A", decode(input.key(TerminalKey.Character('z'), ctrl = true)))
        assertEquals("\u0001", decode(input.key(TerminalKey.Character('a'), ctrl = true)))
    }

    @Test
    fun ctrlOnCharacterWithoutControlCodeProducesNothing() {
        val input = TerminalInput(terminal())
        assertTrue(input.key(TerminalKey.Character('1'), ctrl = true).isEmpty())
    }

    @Test
    fun altPrefixesEscape() {
        val input = TerminalInput(terminal())
        assertEquals("\u001Bx", decode(input.key(TerminalKey.Character('x'), alt = true)))
    }

    @Test
    fun arrowsNormalAndApplicationMode() {
        val t = terminal()
        val input = TerminalInput(t)
        assertEquals("\u001B[A", decode(input.key(TerminalKey.Up)))
        assertEquals("\u001B[B", decode(input.key(TerminalKey.Down)))
        assertEquals("\u001B[C", decode(input.key(TerminalKey.Right)))
        assertEquals("\u001B[D", decode(input.key(TerminalKey.Left)))
        t.setPrivateMode(1, true)
        assertEquals("\u001BOA", decode(input.key(TerminalKey.Up)))
    }

    @Test
    fun arrowModifiers() {
        val input = TerminalInput(terminal())
        assertEquals("\u001B[1;5A", decode(input.key(TerminalKey.Up, ctrl = true)))
        assertEquals("\u001B[1;3A", decode(input.key(TerminalKey.Up, alt = true)))
        assertEquals("\u001B[1;2A", decode(input.key(TerminalKey.Up, shift = true)))
    }

    @Test
    fun functionKeys() {
        val input = TerminalInput(terminal())
        assertEquals("\u001BOP", decode(input.key(TerminalKey.Function(1))))
        assertEquals("\u001BOQ", decode(input.key(TerminalKey.Function(2))))
        assertEquals("\u001B[15~", decode(input.key(TerminalKey.Function(5))))
        assertEquals("\u001B[17~", decode(input.key(TerminalKey.Function(6))))
        assertEquals("\u001B[24~", decode(input.key(TerminalKey.Function(12))))
        assertEquals("\u001B[1;5P", decode(input.key(TerminalKey.Function(1), ctrl = true)))
    }

    @Test
    fun navigationKeys() {
        val t = terminal()
        val input = TerminalInput(t)
        assertEquals("\u001B[H", decode(input.key(TerminalKey.Home)))
        assertEquals("\u001B[F", decode(input.key(TerminalKey.End)))
        assertEquals("\u001B[2~", decode(input.key(TerminalKey.Insert)))
        assertEquals("\u001B[3~", decode(input.key(TerminalKey.Delete)))
        assertEquals("\u001B[5~", decode(input.key(TerminalKey.PageUp)))
        assertEquals("\u001B[6~", decode(input.key(TerminalKey.PageDown)))
        t.setPrivateMode(1, true)
        assertEquals("\u001BOH", decode(input.key(TerminalKey.Home)))
    }

    @Test
    fun shiftTab() {
        val input = TerminalInput(terminal())
        assertEquals("\u001B[Z", decode(input.key(TerminalKey.Tab, shift = true)))
        assertEquals("\t", decode(input.key(TerminalKey.Tab)))
    }

    @Test
    fun enterAndEscape() {
        val t = terminal()
        val input = TerminalInput(t)
        assertEquals("\r", decode(input.key(TerminalKey.Enter)))
        assertEquals("\u001B", decode(input.key(TerminalKey.Escape)))
        t.setMode(20, true)
        assertEquals("\r\n", decode(input.key(TerminalKey.Enter)))
    }

    @Test
    fun backspaceSendsDelByDefaultAndBsWithMode() {
        val t = terminal()
        val input = TerminalInput(t)
        assertEquals("\u007F", decode(input.key(TerminalKey.Backspace)))
        t.setPrivateMode(67, true)
        assertEquals("\b", decode(input.key(TerminalKey.Backspace)))
        assertEquals("\b", decode(input.key(TerminalKey.Backspace, ctrl = true)))
    }

    @Test
    fun textIsEncodedVerbatim() {
        val input = TerminalInput(terminal())
        assertEquals("hello", decode(input.text("hello")))
    }

    @Test
    fun pasteNormalisesNewlines() {
        val input = TerminalInput(terminal())
        assertEquals("a\rb", decode(input.paste("a\r\nb")))
        assertEquals("a\rb", decode(input.paste("a\nb")))
    }

    @Test
    fun bracketedPasteWrapsAndStripsEndMarker() {
        val t = terminal()
        val input = TerminalInput(t)
        t.setPrivateMode(2004, true)
        assertEquals("\u001B[200~a\rb\u001B[201~", decode(input.paste("a\nb")))
        assertEquals("\u001B[200~ab\u001B[201~", decode(input.paste("a\u001B[201~b")))
    }

    @Test
    fun focusEventsOnlyWhenEnabled() {
        val t = terminal()
        val input = TerminalInput(t)
        assertNull(input.focus(true))
        t.setPrivateMode(1004, true)
        assertEquals("\u001B[I", decode(input.focus(true)))
        assertEquals("\u001B[O", decode(input.focus(false)))
    }

    @Test
    fun mouseIsIgnoredWithoutTracking() {
        val input = TerminalInput(terminal())
        assertNull(input.mouse(TerminalMouseButton.LEFT, TerminalMouseAction.PRESS, 0, 0))
    }

    @Test
    fun mouseX10Encoding() {
        val t = terminal()
        val input = TerminalInput(t)
        t.setPrivateMode(1000, true)
        assertEquals("\u001B[M !!", decode(input.mouse(TerminalMouseButton.LEFT, TerminalMouseAction.PRESS, 0, 0)))
        assertEquals("\u001B[M#!!", decode(input.mouse(TerminalMouseButton.LEFT, TerminalMouseAction.RELEASE, 0, 0)))
        assertEquals("\u001B[M!!!", decode(input.mouse(TerminalMouseButton.MIDDLE, TerminalMouseAction.PRESS, 0, 0)))
    }

    @Test
    fun mouseX10LevelIgnoresReleases() {
        val t = terminal()
        val input = TerminalInput(t)
        t.setPrivateMode(9, true)
        assertNull(input.mouse(TerminalMouseButton.LEFT, TerminalMouseAction.RELEASE, 0, 0))
    }

    @Test
    fun mouseSgrEncoding() {
        val t = terminal()
        val input = TerminalInput(t)
        t.setPrivateMode(1000, true)
        t.setPrivateMode(1006, true)
        assertEquals("\u001B[<0;3;4M", decode(input.mouse(TerminalMouseButton.LEFT, TerminalMouseAction.PRESS, 2, 3)))
        assertEquals("\u001B[<0;3;4m", decode(input.mouse(TerminalMouseButton.LEFT, TerminalMouseAction.RELEASE, 2, 3)))
        assertEquals("\u001B[<16;3;4M", decode(input.mouse(TerminalMouseButton.LEFT, TerminalMouseAction.PRESS, 2, 3, ctrl = true)))
    }

    @Test
    fun mouseUrxvtEncoding() {
        val t = terminal()
        val input = TerminalInput(t)
        t.setPrivateMode(1000, true)
        t.setPrivateMode(1015, true)
        assertEquals("\u001B[0;3;4M", decode(input.mouse(TerminalMouseButton.LEFT, TerminalMouseAction.PRESS, 2, 3)))
    }

    @Test
    fun mouseMotionOnlyForButtonAndAnyLevels() {
        val t = terminal()
        val input = TerminalInput(t)
        t.setPrivateMode(1000, true)
        t.setPrivateMode(1006, true)
        assertNull(input.mouse(TerminalMouseButton.LEFT, TerminalMouseAction.MOVE, 1, 1))
        t.setPrivateMode(1002, true)
        assertEquals("\u001B[<32;2;2M", decode(input.mouse(TerminalMouseButton.LEFT, TerminalMouseAction.MOVE, 1, 1)))
        t.setPrivateMode(1003, true)
        assertEquals("\u001B[<32;2;2M", decode(input.mouse(TerminalMouseButton.LEFT, TerminalMouseAction.MOVE, 1, 1)))
    }

    @Test
    fun wheelIsButtonFourAndFive() {
        val t = terminal()
        val input = TerminalInput(t)
        t.setPrivateMode(1000, true)
        assertEquals("\u001B[M`!!", decode(input.wheel(up = true, column = 0, row = 0)))
        assertEquals("\u001B[Ma!!", decode(input.wheel(up = false, column = 0, row = 0)))
    }

    @Test
    fun modifierBitsCombine() {
        val t = terminal()
        val input = TerminalInput(t)
        t.setPrivateMode(1000, true)
        t.setPrivateMode(1006, true)
        // shift=4, alt=8, ctrl=16 => 28
        assertEquals("\u001B[<28;1;1M", decode(input.mouse(TerminalMouseButton.LEFT, TerminalMouseAction.PRESS, 0, 0, ctrl = true, alt = true, shift = true)))
    }
}
