package cn.enaium.terminal.unicode

/**
 * Terminal cell metrics for Unicode code points.
 *
 * All lookups are backed by the compact binary-searchable range tables in
 * [WidthTables] and never allocate.
 */
object Unicode {

    /**
     * Terminal cell width of a single code point: `0` (zero-width/combining),
     * `1` (narrow) or `2` (wide).
     */
    fun cellWidth(cp: Int): Int {
        if (cp < 0 || cp > 0x10FFFF) return 0
        if (isZeroWidth(cp)) return 0
        if (isWide(cp)) return 2
        return 1
    }

    /** East Asian Wide/Fullwidth or emoji presentation. */
    fun isWide(cp: Int): Boolean =
        rangeContains(WidthTables.WIDE, cp) || rangeContains(WidthTables.EMOJI_PRESENTATION, cp)

    /** Combining marks, format characters and other zero-advance code points. */
    fun isZeroWidth(cp: Int): Boolean = rangeContains(WidthTables.ZERO_WIDTH, cp)

    /** Extended pictographic code points (emoji). */
    fun isEmoji(cp: Int): Boolean = rangeContains(WidthTables.EMOJI, cp)

    /**
     * True for code points that extend the previous grapheme cluster
     * (combining marks, variation selectors, ZWJ, skin tone modifiers, tag
     * characters).
     */
    fun isGraphemeExtend(cp: Int): Boolean {
        if (cp < 0) return false
        if (rangeContains(WidthTables.ZERO_WIDTH, cp)) {
            // Cf/Mn/Me plus the explicit format ranges; ZWJ and variation
            // selectors are all contained here.
            return true
        }
        return Graphemes.isSkinToneModifier(cp) || Graphemes.isTagCharacter(cp)
    }

    /** Display width of [text] in terminal cells (grapheme-aware). */
    fun stringWidth(text: String): Int {
        if (text.isEmpty()) return 0
        val cps = Utf8.toCodePoints(text)
        var width = 0
        var i = 0
        val n = cps.size
        while (i < n) {
            val start = i
            i++
            // Regional indicator pairs form a single flag cluster.
            if (Graphemes.isRegionalIndicator(cps[start]) && i < n && Graphemes.isRegionalIndicator(cps[i])) {
                i++
            }
            while (i < n) {
                val c = cps[i]
                if (c == Graphemes.ZWJ) {
                    // ZWJ glues the next code point onto this cluster.
                    i += if (i + 1 < n) 2 else 1
                    continue
                }
                if (isGraphemeExtend(c)) {
                    i++
                    continue
                }
                if (isSpacingMark(c)) {
                    i++
                    continue
                }
                break
            }
            width += Graphemes.clusterWidth(cps, start, i - start)
        }
        return width
    }

    /** Spacing combining marks (Mc) that stay inside the preceding cluster. */
    private fun isSpacingMark(cp: Int): Boolean = rangeContains(WidthTables.SPACING_MARK, cp)
}
