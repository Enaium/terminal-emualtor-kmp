package cn.enaium.terminal.unicode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphemesTest {

    @Test
    fun constantsMatchUnicode() {
        assertEquals(0x200D, Graphemes.ZWJ)
        assertEquals(0xFE0E, Graphemes.VARIATION_SELECTOR_15)
        assertEquals(0xFE0F, Graphemes.VARIATION_SELECTOR_16)
    }

    @Test
    fun regionalIndicators() {
        assertTrue(Graphemes.isRegionalIndicator(0x1F1E6))
        assertTrue(Graphemes.isRegionalIndicator(0x1F1FF))
        assertFalse(Graphemes.isRegionalIndicator(0x1F1E5))
        assertFalse(Graphemes.isRegionalIndicator(0x1F200))
    }

    @Test
    fun skinToneModifiers() {
        assertTrue(Graphemes.isSkinToneModifier(0x1F3FB))
        assertTrue(Graphemes.isSkinToneModifier(0x1F3FF))
        assertFalse(Graphemes.isSkinToneModifier(0x1F3FA))
        assertFalse(Graphemes.isSkinToneModifier(0x1F400))
    }

    @Test
    fun tagCharacters() {
        assertTrue(Graphemes.isTagCharacter(0xE0020))
        assertTrue(Graphemes.isTagCharacter(0xE007F))
        assertFalse(Graphemes.isTagCharacter(0xE001F))
        assertFalse(Graphemes.isTagCharacter(0xE0080))
    }

    @Test
    fun variationSelectors() {
        assertTrue(Graphemes.isVariationSelector(0xFE00))
        assertTrue(Graphemes.isVariationSelector(0xFE0F))
        assertTrue(Graphemes.isVariationSelector(0xE0100))
        assertTrue(Graphemes.isVariationSelector(0xE01EF))
        assertFalse(Graphemes.isVariationSelector(0xFDFF))
        assertFalse(Graphemes.isVariationSelector(0xE01F0))
    }

    @Test
    fun codePointToStringHandlesSurrogates() {
        assertEquals("A", Graphemes.codePointToString(0x41))
        assertEquals("中", Graphemes.codePointToString(0x4E2D))
        assertEquals("\uD83D\uDE00", Graphemes.codePointToString(0x1F600))
    }

    @Test
    fun toStringSequence() {
        assertEquals("Hi", Graphemes.toString(intArrayOf(0x48, 0x69), 2))
        assertEquals("", Graphemes.toString(intArrayOf(0x48, 0x69), 0))
        // only the first `count` entries are consumed
        assertEquals("H", Graphemes.toString(intArrayOf(0x48, 0x69), 1))
    }

    @Test
    fun clusterWidthSingleCodePoints() {
        assertEquals(1, Graphemes.clusterWidth(intArrayOf(0x41), 1))
        assertEquals(2, Graphemes.clusterWidth(intArrayOf(0x4E2D), 1))
        assertEquals(0, Graphemes.clusterWidth(intArrayOf(0x0301), 1))
        assertEquals(0, Graphemes.clusterWidth(intArrayOf(0x41), 0))
    }

    @Test
    fun clusterWidthEmojiSequences() {
        val family = intArrayOf(0x1F468, Graphemes.ZWJ, 0x1F469, Graphemes.ZWJ, 0x1F467, Graphemes.ZWJ, 0x1F466)
        assertEquals(2, Graphemes.clusterWidth(family, family.size))

        val keycap = intArrayOf(0x31, Graphemes.VARIATION_SELECTOR_16, 0x20E3)
        assertEquals(2, Graphemes.clusterWidth(keycap, keycap.size))

        val flag = intArrayOf(0x1F1EF, 0x1F1F5)
        assertEquals(2, Graphemes.clusterWidth(flag, flag.size))

        val heartText = intArrayOf(0x2764, Graphemes.VARIATION_SELECTOR_15)
        assertEquals(1, Graphemes.clusterWidth(heartText, heartText.size))
    }
}
