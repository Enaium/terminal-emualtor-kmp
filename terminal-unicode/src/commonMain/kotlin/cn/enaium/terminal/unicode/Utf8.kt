package cn.enaium.terminal.unicode

/**
 * Incremental UTF-8 decoder.
 *
 * The decoder keeps the partially decoded state of at most one code point
 * between [feed] calls, so it can be fed a byte stream of arbitrary chunking.
 * Invalid input is reported as U+FFFD (the replacement character); overlong
 * encodings, surrogates, values above U+10FFFF and bare continuation bytes are
 * all rejected.
 */
class Utf8Decoder {

    private var needed = 0
    private var accumulator = 0
    private var lowerBound = 0x80
    private var upperBound = 0xBF
    private var lastCallback: ((Int) -> Unit)? = null

    /** Drops any pending partial sequence and flushes it as U+FFFD. */
    fun reset() {
        if (needed != 0) {
            needed = 0
            accumulator = 0
            lastCallback?.invoke(REPLACEMENT)
        }
        lowerBound = 0x80
        upperBound = 0xBF
    }

    /**
     * Feeds a single byte in `0..255` and invokes [onCodePoint] for every code
     * point completed by this byte (zero, one or more of them).
     */
    fun feed(byte: Int, onCodePoint: (Int) -> Unit) {
        lastCallback = onCodePoint
        val b = byte and 0xFF

        if (needed == 0) {
            when {
                b < 0x80 -> onCodePoint(b)

                0xC2 <= b && b <= 0xDF -> start(b and 0x1F, 1, 0x80, 0xBF)
                0xE0 == b -> start(0, 2, 0xA0, 0xBF)
                0xE1 <= b && b <= 0xEC -> start(b and 0x0F, 2, 0x80, 0xBF)
                0xED == b -> start(b and 0x0F, 2, 0x80, 0x9F)
                0xEE <= b && b <= 0xEF -> start(b and 0x0F, 2, 0x80, 0xBF)
                0xF0 == b -> start(0, 3, 0x90, 0xBF)
                0xF1 <= b && b <= 0xF3 -> start(b and 0x07, 3, 0x80, 0xBF)
                0xF4 == b -> start(b and 0x07, 3, 0x80, 0x8F)

                // 0x80..0xC1 (bare continuation / overlong lead) and 0xF5..0xFF.
                else -> onCodePoint(REPLACEMENT)
            }
            return
        }

        if (b < lowerBound || b > upperBound) {
            // The continuation byte is invalid: report the pending sequence and
            // re-examine this byte as the start of a new sequence.
            needed = 0
            accumulator = 0
            lowerBound = 0x80
            upperBound = 0xBF
            onCodePoint(REPLACEMENT)
            feed(b, onCodePoint)
            return
        }

        accumulator = (accumulator shl 6) or (b and 0x3F)
        needed--
        lowerBound = 0x80
        upperBound = 0xBF
        if (needed == 0) {
            onCodePoint(accumulator)
            accumulator = 0
        }
    }

    private fun start(initial: Int, count: Int, lower: Int, upper: Int) {
        accumulator = initial
        needed = count
        lowerBound = lower
        upperBound = upper
    }

    private companion object {
        const val REPLACEMENT = 0xFFFD
    }
}

/** Stateless helpers for encoding and whole-buffer decoding of UTF-8. */
object Utf8 {

    /** Decodes [bytes] to a String, replacing invalid sequences with U+FFFD. */
    fun decode(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): String {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) {
            "offset=$offset length=$length size=${bytes.size}"
        }
        val builder = StringBuilder(length)
        val decoder = Utf8Decoder()
        var i = offset
        val end = offset + length
        while (i < end) {
            decoder.feed(bytes[i].toInt(), builder::appendCodePoint)
            i++
        }
        // Flush a truncated trailing sequence as U+FFFD.
        decoder.reset()
        return builder.toString()
    }

    /** Appends the UTF-8 encoding of [cp] to [out]. */
    fun encode(cp: Int, out: MutableList<Byte>) {
        if (cp < 0 || cp > 0x10FFFF || (cp in 0xD800..0xDFFF)) {
            encode(0xFFFD, out)
            return
        }
        when {
            cp < 0x80 -> {
                out.add(cp.toByte())
            }

            cp < 0x800 -> {
                out.add((0xC0 or (cp ushr 6)).toByte())
                out.add((0x80 or (cp and 0x3F)).toByte())
            }

            cp < 0x10000 -> {
                out.add((0xE0 or (cp ushr 12)).toByte())
                out.add((0x80 or ((cp ushr 6) and 0x3F)).toByte())
                out.add((0x80 or (cp and 0x3F)).toByte())
            }

            else -> {
                out.add((0xF0 or (cp ushr 18)).toByte())
                out.add((0x80 or ((cp ushr 12) and 0x3F)).toByte())
                out.add((0x80 or ((cp ushr 6) and 0x3F)).toByte())
                out.add((0x80 or (cp and 0x3F)).toByte())
            }
        }
    }

    /** UTF-8 encoding of [cp] as a ByteArray. */
    fun encode(cp: Int): ByteArray {
        val out = ArrayList<Byte>(4)
        encode(cp, out)
        return out.toByteArray()
    }

    /** UTF-8 encoding of [text]. */
    fun encode(text: String): ByteArray {
        val out = ArrayList<Byte>(text.length)
        var i = 0
        while (i < text.length) {
            val cp = codePointAt(text, i)
            encode(cp, out)
            i += if (cp > 0xFFFF) 2 else 1
        }
        return out.toByteArray()
    }

    /**
     * Reads the code point starting at [index], combining a well-formed
     * surrogate pair. A lone surrogate decodes to U+FFFD.
     */
    internal fun codePointAt(text: String, index: Int): Int {
        val high = text[index]
        if (high.isHighSurrogate() && index + 1 < text.length) {
            val low = text[index + 1]
            if (low.isLowSurrogate()) {
                return 0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)
            }
        }
        if (high.isSurrogate()) return 0xFFFD
        return high.code
    }

    /** Decodes [text] into a freshly allocated code point array. */
    internal fun toCodePoints(text: String): IntArray {
        val result = IntArray(text.length)
        var count = 0
        var i = 0
        while (i < text.length) {
            val cp = codePointAt(text, i)
            result[count++] = cp
            i += if (cp > 0xFFFF) 2 else 1
        }
        return if (count == result.size) result else result.copyOf(count)
    }
}

/** Appends [cp] to this builder, encoding supplementary code points as a pair. */
internal fun StringBuilder.appendCodePoint(cp: Int) {
    if (cp <= 0xFFFF) {
        append(cp.toChar())
    } else {
        val v = cp - 0x10000
        append((0xD800 or (v ushr 10)).toChar())
        append((0xDC00 or (v and 0x3FF)).toChar())
    }
}
