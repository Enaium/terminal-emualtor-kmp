package cn.enaium.terminal.unicode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnicodeWidthTest {

    @Test
    fun asciiAndLatin1AreNarrow() {
        for (cp in 0x20..0x7E) assertEquals(1, Unicode.cellWidth(cp))
        assertEquals(1, Unicode.cellWidth(0xE9)) // é
        assertEquals(1, Unicode.cellWidth(0xF1)) // ñ
        assertEquals(1, Unicode.cellWidth(0x2764)) // ❤ without VS16
    }

    @Test
    fun cjkIsWide() {
        assertEquals(2, Unicode.cellWidth('中'.code))
        assertEquals(2, Unicode.cellWidth('あ'.code))
        assertEquals(2, Unicode.cellWidth('한'.code))
        assertEquals(2, Unicode.cellWidth('Ａ'.code)) // fullwidth A
        assertTrue(Unicode.isWide('中'.code))
        assertFalse(Unicode.isWide('A'.code))
    }

    @Test
    fun combiningMarkIsZeroWidth() {
        assertEquals(0, Unicode.cellWidth(0x0301)) // combining acute
        assertTrue(Unicode.isZeroWidth(0x0301))
        assertEquals(0, Unicode.cellWidth(0x200D)) // ZWJ
        assertFalse(Unicode.isZeroWidth('A'.code))
    }

    @Test
    fun emojiDetection() {
        assertTrue(Unicode.isEmoji(0x1F600))
        assertTrue(Unicode.isEmoji(0x2764))
        assertFalse(Unicode.isEmoji('A'.code))
        assertEquals(2, Unicode.cellWidth(0x1F600))
    }

    @Test
    fun outOfRangeCodePoints() {
        assertEquals(0, Unicode.cellWidth(-1))
        assertEquals(0, Unicode.cellWidth(0x110000))
    }

    @Test
    fun graphemeExtendPredicate() {
        assertTrue(Unicode.isGraphemeExtend(0x0301))
        assertTrue(Unicode.isGraphemeExtend(Graphemes.ZWJ))
        assertTrue(Unicode.isGraphemeExtend(Graphemes.VARIATION_SELECTOR_16))
        assertTrue(Unicode.isGraphemeExtend(0x1F3FB)) // skin tone modifier
        assertTrue(Unicode.isGraphemeExtend(0xE0061)) // tag character
        assertFalse(Unicode.isGraphemeExtend('A'.code))
    }

    @Test
    fun stringWidthCombiningSequenceIsOne() {
        assertEquals(1, Unicode.stringWidth("e\u0301"))
        assertEquals(1, Unicode.stringWidth("e\u0301\u0302"))
        assertEquals(0, Unicode.stringWidth("\u0301"))
    }

    @Test
    fun stringWidthVs16Upgrade() {
        assertEquals(1, Unicode.stringWidth("\u2764"))
        assertEquals(2, Unicode.stringWidth("\u2764\uFE0F"))
        assertEquals(2, Unicode.stringWidth("1\uFE0F\u20E3")) // keycap
    }

    @Test
    fun stringWidthZwjFamilyIsTwo() {
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66" // 👨‍👩‍👧‍👦
        assertEquals(2, Unicode.stringWidth(family))
    }

    @Test
    fun stringWidthFlagPairIsTwo() {
        val japan = "\uD83C\uDDEF\uD83C\uDDF5" // 🇯🇵
        assertEquals(2, Unicode.stringWidth(japan))
    }

    @Test
    fun stringWidthMixedText() {
        assertEquals(0, Unicode.stringWidth(""))
        assertEquals(3, Unicode.stringWidth("abc"))
        assertEquals(4, Unicode.stringWidth("a中b")) // 1 + 2 + 1
        assertEquals(6, Unicode.stringWidth("中中中"))
        assertEquals(6, Unicode.stringWidth("a中\u2764\uFE0Fb")) // 1 + 2 + 2 + 1
    }
}
