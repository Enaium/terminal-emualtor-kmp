package cn.enaium.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalAlternateScreenTest {

    @Test
    fun oneZeroFourNineSavesRestoresAndClears() {
        val t = Terminal(columns = 10, rows = 3, scrollbackLimit = 100)
        t.write("normal")
        t.setCursor(1, 2)

        t.setPrivateMode(1049, true)
        assertTrue(t.isAlternateScreen)
        assertEquals(0, t.cursorRow)
        assertEquals(0, t.cursorColumn)
        t.write("alternate")

        t.setPrivateMode(1049, false)
        assertFalse(t.isAlternateScreen)
        assertEquals("normal", t.lineAt(0).text(0, 10))
        assertEquals(1, t.cursorRow)
        assertEquals(2, t.cursorColumn)

        t.setPrivateMode(1049, true)
        assertEquals("", t.lineAt(0).text(0, 10))
    }

    @Test
    fun oneZeroFourSevenClearsAlternateOnEntryAndKeepsNormal() {
        val t = Terminal(columns = 10, rows = 3, scrollbackLimit = 100)
        t.write("normal")

        t.setPrivateMode(1047, true)
        assertTrue(t.isAlternateScreen)
        t.write("alternate")

        t.setPrivateMode(1047, false)
        assertFalse(t.isAlternateScreen)
        assertEquals("normal", t.lineAt(0).text(0, 10))

        t.setPrivateMode(1047, true)
        assertEquals("", t.lineAt(0).text(0, 10))
    }

    @Test
    fun oneZeroFourEightOnlySavesAndRestoresTheCursor() {
        val t = Terminal(columns = 10, rows = 3, scrollbackLimit = 100)
        t.setCursor(1, 4)
        t.setPrivateMode(1048, true)
        assertFalse(t.isAlternateScreen)
        t.setCursor(2, 0)
        t.setPrivateMode(1048, false)
        assertEquals(1, t.cursorRow)
        assertEquals(4, t.cursorColumn)
    }

    @Test
    fun alternateScreenHasNoScrollback() {
        val t = Terminal(columns = 5, rows = 1, scrollbackLimit = 100)
        t.setPrivateMode(1049, true)
        t.setCursor(0, 0)
        t.write("AAAAA")
        t.carriageReturn()
        t.lineFeed()
        t.write("BBBBB")
        assertEquals(0, t.scrollbackSize)
        assertTrue(t.buffer.isAlternate)
    }

    @Test
    fun switchingScreensResetsScrollOffset() {
        val t = Terminal(columns = 5, rows = 2, scrollbackLimit = 100)
        t.write("AAAAA\r\nBBBBB\r\nCCCCC")
        t.scrollView(1)
        assertEquals(1, t.scrollOffset)
        t.setPrivateMode(1049, true)
        assertEquals(0, t.scrollOffset)
    }

    @Test
    fun normalBufferIsDistinctFromAlternate() {
        val t = Terminal(columns = 5, rows = 2, scrollbackLimit = 100)
        assertFalse(t.normalBuffer.isAlternate)
        assertTrue(t.alternateBuffer.isAlternate)
        assertTrue(t.buffer === t.normalBuffer)
    }
}
