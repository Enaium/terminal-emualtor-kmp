package cn.enaium.terminal.unicode

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Utf8Test {

    private fun decode(vararg bytes: Int): String =
        Utf8.decode(ByteArray(bytes.size) { bytes[it].toByte() })

    private fun decodeAll(bytes: ByteArray): List<Int> {
        val out = ArrayList<Int>()
        val decoder = Utf8Decoder()
        for (b in bytes) decoder.feed(b.toInt(), out::add)
        decoder.reset()
        return out
    }

    @Test
    fun roundTripAllSequenceLengths() {
        val samples = listOf(
            "A",          // 1 byte
            "\u00E9",     // 2 bytes (é)
            "\u4E2D",     // 3 bytes (中)
            "\uD83D\uDE00", // 4 bytes (😀)
        )
        for (s in samples) {
            val encoded = Utf8.encode(s)
            assertEquals(s, Utf8.decode(encoded), "round trip failed for $s")
        }
    }

    @Test
    fun knownEncodings() {
        assertContentEquals(byteArrayOf(0x41), Utf8.encode(0x41))
        assertContentEquals(byteArrayOf(0xC3.toByte(), 0xA9.toByte()), Utf8.encode(0xE9))
        assertContentEquals(
            byteArrayOf(0xE4.toByte(), 0xB8.toByte(), 0xAD.toByte()),
            Utf8.encode(0x4E2D),
        )
        assertContentEquals(
            byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x98.toByte(), 0x80.toByte()),
            Utf8.encode(0x1F600),
        )
    }

    @Test
    fun fourByteBoundaries() {
        assertEquals(Graphemes.codePointToString(0x10000), Utf8.decode(Utf8.encode(0x10000)))
        assertEquals(Graphemes.codePointToString(0x10FFFF), Utf8.decode(Utf8.encode(0x10FFFF)))
    }

    @Test
    fun decodeWithOffsetAndLength() {
        val bytes = byteArrayOf(0x41, 0xE4.toByte(), 0xB8.toByte(), 0xAD.toByte(), 0x42)
        assertEquals("中", Utf8.decode(bytes, offset = 1, length = 3))
        assertEquals("A中B", Utf8.decode(bytes))
    }

    @Test
    fun invalidBytesBecomeReplacement() {
        assertEquals("\uFFFD", decode(0x80)) // bare continuation
        assertEquals("\uFFFD", decode(0xFF)) // invalid lead
        assertEquals("\uFFFD", decode(0xC0)) // overlong 2-byte lead
        assertEquals("\uFFFD\uFFFD", decode(0xC0, 0x80)) // overlong encoding
        assertEquals("\uFFFD\uFFFD\uFFFD", decode(0xE0, 0x80, 0x80)) // overlong 3-byte
        assertEquals("\uFFFD\uFFFD\uFFFD", decode(0xED, 0xA0, 0x80)) // surrogate
        assertEquals("\uFFFD\uFFFD\uFFFD\uFFFD", decode(0xF4, 0x90, 0x80, 0x80)) // > U+10FFFF
        assertEquals("\uFFFD", decode(0xF5))
    }

    @Test
    fun truncatedSequenceAtEndOfBuffer() {
        assertEquals("\uFFFD", decode(0xE4))
        assertEquals("\uFFFD", decode(0xE4, 0xB8))
        assertEquals("\uFFFD", decode(0xF0, 0x9F, 0x98))
        assertEquals("A\uFFFD", decode(0x41, 0xE4, 0xB8))
    }

    @Test
    fun resetFlushesPendingSequence() {
        val out = ArrayList<Int>()
        val decoder = Utf8Decoder()
        decoder.feed(0xF0, out::add)
        decoder.feed(0x9F, out::add)
        assertTrue(out.isEmpty(), "no code point should be complete yet")
        decoder.reset()
        assertEquals(listOf(0xFFFD), out)
    }

    @Test
    fun decoderReuseAfterReset() {
        val out = ArrayList<Int>()
        val decoder = Utf8Decoder()
        for (b in Utf8.encode("A")) decoder.feed(b.toInt(), out::add)
        decoder.reset()
        for (b in Utf8.encode("中")) decoder.feed(b.toInt(), out::add)
        decoder.reset()
        assertEquals(listOf(0x41, 0x4E2D), out)
    }

    @Test
    fun byteAtATimeMatchesWholeBufferDecode() {
        val text = "aé中😀\u0301!"
        val bytes = Utf8.encode(text)
        assertEquals(text, Utf8.decode(bytes))
        assertEquals(Utf8.toCodePoints(text).toList(), decodeAll(bytes))
    }

    @Test
    fun encodeRejectsSurrogatesAndOutOfRange() {
        val expected = byteArrayOf(0xEF.toByte(), 0xBF.toByte(), 0xBD.toByte()) // U+FFFD
        assertContentEquals(expected, Utf8.encode(0xD800))
        assertContentEquals(expected, Utf8.encode(0xDFFF))
        assertContentEquals(expected, Utf8.encode(0x110000))
        assertContentEquals(expected, Utf8.encode(-1))
    }

    @Test
    fun encodeTextWithSurrogatePair() {
        val bytes = Utf8.encode("\uD83D\uDE00")
        assertContentEquals(Utf8.encode(0x1F600), bytes)
    }
}
