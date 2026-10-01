package cn.enaium.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalModesTest {

    private fun terminal() = Terminal(columns = 20, rows = 5, scrollbackLimit = 100)

    @Test
    fun defaultModes() {
        val m = terminal().modes
        assertFalse(m.insert)
        assertFalse(m.newLine)
        assertTrue(m.autoWrap)
        assertFalse(m.origin)
        assertFalse(m.reverseVideo)
        assertTrue(m.cursorVisible)
        assertTrue(m.cursorBlink)
        assertFalse(m.applicationCursorKeys)
        assertFalse(m.backspaceSendsBs)
        assertFalse(m.focusReporting)
        assertTrue(m.alternateScroll)
        assertFalse(m.bracketedPaste)
        assertFalse(m.synchronizedOutput)
        assertEquals(MouseReporting.NONE, m.mouseReporting)
        assertEquals(MouseEncoding.X10, m.mouseEncoding)
        assertFalse(m.mouseTracking)
    }

    @Test
    fun decPrivateModeOneEnablesApplicationCursorKeys() {
        val t = terminal()
        t.setPrivateMode(1, true)
        assertTrue(t.modes.applicationCursorKeys)
        t.setPrivateMode(1, false)
        assertFalse(t.modes.applicationCursorKeys)
    }

    @Test
    fun decPrivateModeFiveIsReverseVideo() {
        val t = terminal()
        t.setPrivateMode(5, true)
        assertTrue(t.modes.reverseVideo)
    }

    @Test
    fun decPrivateModeSixIsOriginMode() {
        val t = terminal()
        t.setScrollRegion(2, 4)
        t.setPrivateMode(6, true)
        assertTrue(t.modes.origin)
        assertEquals(1, t.cursorRow)
        t.setPrivateMode(6, false)
        assertFalse(t.modes.origin)
        assertEquals(0, t.cursorRow)
    }

    @Test
    fun decPrivateModeSevenIsAutoWrap() {
        val t = terminal()
        t.setPrivateMode(7, false)
        assertFalse(t.modes.autoWrap)
    }

    @Test
    fun decPrivateModesTwelveAndTwentyFiveControlCursor() {
        val t = terminal()
        t.setPrivateMode(12, false)
        assertFalse(t.modes.cursorBlink)
        t.setPrivateMode(25, false)
        assertFalse(t.modes.cursorVisible)
    }

    @Test
    fun decPrivateModeSixtySevenBackspaceSendsBs() {
        val t = terminal()
        t.setPrivateMode(67, true)
        assertTrue(t.modes.backspaceSendsBs)
    }

    @Test
    fun mouseReportingModesMapToLevels() {
        val t = terminal()
        t.setPrivateMode(1000, true)
        assertEquals(MouseReporting.NORMAL, t.modes.mouseReporting)
        t.setPrivateMode(1002, true)
        assertEquals(MouseReporting.BUTTON, t.modes.mouseReporting)
        t.setPrivateMode(1003, true)
        assertEquals(MouseReporting.ANY, t.modes.mouseReporting)
        t.setPrivateMode(9, true)
        assertEquals(MouseReporting.X10, t.modes.mouseReporting)
        t.setPrivateMode(1000, false)
        assertEquals(MouseReporting.NONE, t.modes.mouseReporting)
    }

    @Test
    fun mouseEncodingModes() {
        val t = terminal()
        t.setPrivateMode(1006, true)
        assertEquals(MouseEncoding.SGR, t.modes.mouseEncoding)
        t.setPrivateMode(1015, true)
        assertEquals(MouseEncoding.URXVT, t.modes.mouseEncoding)
        t.setPrivateMode(1005, true)
        assertEquals(MouseEncoding.UTF8, t.modes.mouseEncoding)
    }

    @Test
    fun focusBracketedPasteAndSynchronizedOutputModes() {
        val t = terminal()
        t.setPrivateMode(1004, true)
        assertTrue(t.modes.focusReporting)
        t.setPrivateMode(1007, false)
        assertFalse(t.modes.alternateScroll)
        t.setPrivateMode(2004, true)
        assertTrue(t.modes.bracketedPaste)
        t.setPrivateMode(2026, true)
        assertTrue(t.modes.synchronizedOutput)
    }

    @Test
    fun ansiModesInsertAndNewLine() {
        val t = terminal()
        t.setMode(4, true)
        assertTrue(t.modes.insert)
        t.setMode(20, true)
        assertTrue(t.modes.newLine)
        t.setMode(4, false)
        assertFalse(t.modes.insert)
    }

    @Test
    fun resetRestoresDefaultsAndClearsScreen() {
        val t = terminal()
        t.write("hello")
        t.setPrivateMode(1, true)
        t.setPrivateMode(7, false)
        t.setMode(4, true)
        t.setScrollRegion(2, 4)
        t.setForeground(TerminalColor.indexed(3))
        t.setTitle("title")
        t.reset()
        assertEquals("", t.lineAt(0).text(0, 20))
        assertFalse(t.modes.applicationCursorKeys)
        assertTrue(t.modes.autoWrap)
        assertFalse(t.modes.insert)
        assertEquals(0, t.scrollTop)
        assertEquals(4, t.scrollBottom)
        assertEquals(TerminalColor.Default, t.foreground)
        assertNull(t.title)
        assertEquals(0, t.cursorRow)
        assertEquals(0, t.cursorColumn)
    }

    @Test
    fun softResetKeepsScreenContents() {
        val t = terminal()
        t.write("hello")
        t.setPrivateMode(1, true)
        t.setScrollRegion(2, 4)
        t.softReset()
        assertEquals("hello", t.lineAt(0).text(0, 20))
        assertFalse(t.modes.applicationCursorKeys)
        assertEquals(0, t.scrollTop)
        assertEquals(4, t.scrollBottom)
    }
}
