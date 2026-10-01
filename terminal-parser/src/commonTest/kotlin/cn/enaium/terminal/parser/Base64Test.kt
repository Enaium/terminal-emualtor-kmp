package cn.enaium.terminal.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Base64Test {

    @Test
    fun encodeStrings() {
        assertEquals("", Base64.encode(""))
        assertEquals("YQ==", Base64.encode("a"))
        assertEquals("YWI=", Base64.encode("ab"))
        assertEquals("YWJj", Base64.encode("abc"))
        assertEquals("aGVsbG8=", Base64.encode("hello"))
    }

    @Test
    fun encodeBytes() {
        assertEquals("AAEC", Base64.encode(byteArrayOf(0, 1, 2)))
    }

    @Test
    fun decodeWithPadding() {
        assertEquals("hello", Base64.decodeToString("aGVsbG8="))
        assertEquals("a", Base64.decodeToString("YQ=="))
    }

    @Test
    fun decodeWithoutPaddingAndWithWhitespace() {
        assertEquals("hello", Base64.decodeToString("aGVsbG8"))
        assertEquals("hello", Base64.decodeToString("aGVs\nbG8="))
        assertEquals("hello", Base64.decodeToString("aGVs bG8="))
    }

    @Test
    fun decodeMultibyteUtf8() {
        assertEquals("\u4E2D", Base64.decodeToString("5Lit"))
    }

    @Test
    fun decodeInvalidReturnsNull() {
        assertNull(Base64.decode("!!!!"))
        assertNull(Base64.decodeToString("a!"))
    }

    @Test
    fun roundTripBytes() {
        val bytes = byteArrayOf(0, 127, -1, 42, 99)
        val encoded = Base64.encode(bytes)
        val decoded = Base64.decode(encoded)
        assertEquals(bytes.toList(), decoded?.toList())
    }
}
