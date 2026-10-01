package cn.enaium.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalColorTest {

    @Test
    fun defaultColor() {
        val c = TerminalColor.Default
        assertTrue(c.isDefault)
        assertFalse(c.isIndexed)
        assertFalse(c.isRgb)
        assertEquals(0, c.index)
    }

    @Test
    fun indexedColorMasksToEightBits() {
        val c = TerminalColor.indexed(0x1FF)
        assertTrue(c.isIndexed)
        assertEquals(0xFF, c.index)
    }

    @Test
    fun rgbColorFromComponents() {
        val c = TerminalColor.rgb(0x12, 0x34, 0x56)
        assertTrue(c.isRgb)
        assertEquals(0x12, c.red)
        assertEquals(0x34, c.green)
        assertEquals(0x56, c.blue)
        assertEquals(0x123456, c.rgb)
    }

    @Test
    fun packedRoundTrip() {
        for (color in listOf(TerminalColor.Default, TerminalColor.indexed(7), TerminalColor.rgb(1, 2, 3))) {
            assertEquals(color, TerminalColor.fromPacked(TerminalColor.toPacked(color)))
        }
    }

    @Test
    fun attributesFlags() {
        val a = TerminalAttributes.None
            .with(TerminalAttributes.BOLD, true)
            .with(TerminalAttributes.ITALIC, true)
        assertTrue(a.bold)
        assertTrue(a.italic)
        assertFalse(a.underline)
        assertEquals(a, a.with(TerminalAttributes.BOLD, false).with(TerminalAttributes.BOLD, true))
    }

    @Test
    fun attributesUnderlineMask() {
        val a = TerminalAttributes.None
            .with(TerminalAttributes.UNDERLINE, true)
            .with(TerminalAttributes.CURLY_UNDERLINE, true)
        assertTrue(a.anyUnderline)
        val cleared = a.withMask(TerminalAttributes.UNDERLINE_MASK, false)
        assertFalse(cleared.anyUnderline)
    }

    @Test
    fun cursorStyleFromDecscusr() {
        assertEquals(TerminalCursorStyle(TerminalCursorShape.BLOCK, true), TerminalCursorStyle.fromDecscusr(1))
        assertEquals(TerminalCursorStyle(TerminalCursorShape.BLOCK, false), TerminalCursorStyle.fromDecscusr(2))
        assertEquals(TerminalCursorStyle(TerminalCursorShape.BAR, true), TerminalCursorStyle.fromDecscusr(3))
        assertEquals(TerminalCursorStyle(TerminalCursorShape.BAR, false), TerminalCursorStyle.fromDecscusr(4))
        assertEquals(TerminalCursorStyle(TerminalCursorShape.UNDERLINE, true), TerminalCursorStyle.fromDecscusr(5))
        assertEquals(TerminalCursorStyle(TerminalCursorShape.UNDERLINE, false), TerminalCursorStyle.fromDecscusr(6))
        assertEquals(TerminalCursorStyle.Default, TerminalCursorStyle.fromDecscusr(99))
    }

    @Test
    fun paletteSystemColors() {
        val palette = TerminalPalette()
        assertEquals(0x000000, palette.rgb(0))
        assertEquals(0xCD0000, palette.rgb(1))
        assertEquals(0xFFFFFF, palette.rgb(15))
        assertTrue(palette.isDefault(1))
    }

    @Test
    fun paletteColorCubeAndGrayscale() {
        val palette = TerminalPalette()
        assertEquals(0x000000, palette.rgb(16))
        assertEquals(0xFFFFFF, palette.rgb(231))
        assertEquals(0x080808, palette.rgb(232))
        assertEquals(0xEEEEEE, palette.rgb(255))
    }

    @Test
    fun paletteOverrideAndReset() {
        val palette = TerminalPalette()
        palette.set(1, 0x123456)
        assertEquals(0x123456, palette.rgb(1))
        assertFalse(palette.isDefault(1))
        palette.reset(1)
        assertEquals(0xCD0000, palette.rgb(1))
        assertTrue(palette.isDefault(1))
    }

    @Test
    fun paletteResetAll() {
        val palette = TerminalPalette()
        palette.set(1, 0x111111)
        palette.set(2, 0x222222)
        palette.reset()
        assertTrue(palette.isDefault(1))
        assertTrue(palette.isDefault(2))
    }

    @Test
    fun terminalPaletteOverrides() {
        val t = Terminal(columns = 10, rows = 3, scrollbackLimit = 100)
        t.setPaletteColor(1, TerminalColor.rgb(0x12, 0x34, 0x56))
        assertEquals(0x123456, t.paletteRgb(1))
        t.resetPalette(1)
        assertEquals(0xCD0000, t.paletteRgb(1))
    }

    @Test
    fun terminalDefaultColorOverrides() {
        val t = Terminal(columns = 10, rows = 3, scrollbackLimit = 100)
        t.setDefaultColor(10, TerminalColor.rgb(0xAB, 0xCD, 0xEF))
        assertEquals(TerminalColor.rgb(0xAB, 0xCD, 0xEF), t.defaultColor(10))
        t.resetDefaultColor(10)
        assertNull(t.defaultColor(10))
    }
}
