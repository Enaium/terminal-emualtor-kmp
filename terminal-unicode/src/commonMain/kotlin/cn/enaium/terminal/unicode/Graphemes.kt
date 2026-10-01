package cn.enaium.terminal.unicode

/**
 * Grapheme-cluster helpers used for width measurement and terminal output.
 *
 * Everything here is pure arithmetic / range checks; no platform APIs and no
 * allocation in the hot paths.
 */
object Graphemes {

    /** Zero width joiner. */
    const val ZWJ: Int = 0x200D

    /** Text presentation selector. */
    const val VARIATION_SELECTOR_15: Int = 0xFE0E

    /** Emoji presentation selector. */
    const val VARIATION_SELECTOR_16: Int = 0xFE0F

    /** Regional indicator symbols U+1F1E6..U+1F1FF (flag pairs). */
    fun isRegionalIndicator(cp: Int): Boolean = cp in 0x1F1E6..0x1F1FF

    /** Emoji skin tone modifiers U+1F3FB..U+1F3FF. */
    fun isSkinToneModifier(cp: Int): Boolean = cp in 0x1F3FB..0x1F3FF

    /** Tag characters U+E0020..U+E007F (flag tag sequences). */
    fun isTagCharacter(cp: Int): Boolean = cp in 0xE0020..0xE007F

    /** Variation selectors U+FE00..U+FE0F and U+E0100..U+E01EF. */
    fun isVariationSelector(cp: Int): Boolean = cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF

    /** UTF-8 string for a code point. */
    fun codePointToString(cp: Int): String = buildString(2) { appendCodePoint(cp) }

    /** UTF-8 string for a code point sequence. */
    fun toString(codepoints: IntArray, count: Int): String {
        if (count <= 0) return ""
        val builder = StringBuilder(count)
        for (i in 0 until count) {
            builder.appendCodePoint(codepoints[i])
        }
        return builder.toString()
    }

    /**
     * Display width in cells of a grapheme cluster (wide base or emoji
     * presentation => 2, combining-only => 0).
     */
    fun clusterWidth(codepoints: IntArray, count: Int): Int = clusterWidth(codepoints, 0, count)

    /** Offset-aware variant used by [Unicode.stringWidth] to avoid copies. */
    internal fun clusterWidth(codepoints: IntArray, offset: Int, count: Int): Int {
        if (count <= 0) return 0
        val end = offset + count
        var wide = false
        var emojiPresentation = false
        for (i in offset until end) {
            val cp = codepoints[i]
            if (cp == VARIATION_SELECTOR_16) emojiPresentation = true
            if (Unicode.isWide(cp)) wide = true
        }
        if (wide || emojiPresentation) return 2
        return Unicode.cellWidth(codepoints[offset])
    }
}
