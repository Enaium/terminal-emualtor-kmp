package cn.enaium.terminal.imgui

/**
 * The code point ranges a terminal font should cover.
 *
 * [ImFontGlyphRanges.chineseFull] is *not* enough on its own: it leaves out
 * box drawing, block elements, arrows and geometric shapes, which is exactly
 * what full-screen applications draw their borders, scrollbars and selection
 * markers with (`nvim`, `htop`, `mc`, ...). Without them ImGui has no glyph in
 * the atlas and those characters show up as tofu boxes.
 *
 * Fonts that do not contain a range simply contribute nothing for it, so one
 * list can be handed to both a Latin terminal face and a CJK fallback.
 */
object TerminalGlyphRanges {

    /** Latin, punctuation, arrows, math, box drawing, symbols, kana and CJK. */
    val terminal: IntArray = intArrayOf(
        0x0020, 0x007E, // ASCII
        0x00A0, 0x00FF, // Latin-1 supplement
        0x0100, 0x017F, // Latin extended-A
        0x0180, 0x024F, // Latin extended-B
        0x0370, 0x03FF, // Greek
        0x0400, 0x04FF, // Cyrillic
        0x2000, 0x206F, // general punctuation
        0x2070, 0x209F, // super/subscripts
        0x20A0, 0x20BF, // currency
        0x2100, 0x214F, // letterlike symbols
        0x2150, 0x218F, // number forms
        0x2190, 0x21FF, // arrows
        0x2200, 0x22FF, // mathematical operators
        0x2300, 0x23FF, // miscellaneous technical
        0x2460, 0x24FF, // enclosed alphanumerics
        0x2500, 0x257F, // box drawing
        0x2580, 0x259F, // block elements
        0x25A0, 0x25FF, // geometric shapes
        0x2600, 0x26FF, // miscellaneous symbols
        0x2700, 0x27BF, // dingbats
        0x2800, 0x28FF, // braille patterns
        0x2E00, 0x2E7F, // supplemental punctuation
        0x3000, 0x303F, // CJK symbols and punctuation
        0x3040, 0x30FF, // hiragana + katakana
        0x3130, 0x318F, // hangul compatibility jamo
        0x4E00, 0x9FFF, // CJK unified ideographs
        0xAC00, 0xD7A3, // hangul syllables
        0xF900, 0xFAFF, // CJK compatibility ideographs
        0xFE00, 0xFE0F, // variation selectors
        0xFF00, 0xFFEF, // halfwidth and fullwidth forms
        0,
    )

    /**
     * The ranges for a *fallback* face: [terminal]'s symbol blocks, kana, the
     * 2500 most common Simplified Chinese characters, emoji and the private use
     * area (Nerd Font, Powerline).
     *
     * It deliberately leaves out the full CJK ideograph block (U+4E00..U+9FFF)
     * and the Hangul syllables: a face that contains them would be rasterized
     * for ~30 000 glyphs, which makes ImGui's atlas overflow - and a glyph that
     * does not fit is *dropped*, so it renders as `?` instead. Use
     * [terminalWithFallbacks] when full CJK coverage matters more than the
     * atlas size (32 MiB at density 1, several times that on a Retina display).
     */
    val fallback: IntArray = intArrayOf(
        0x0020, 0x007E, // ASCII
        0x00A0, 0x00FF, // Latin-1 supplement
        0x0100, 0x024F, // Latin extended
        0x0370, 0x03FF, // Greek
        0x0400, 0x04FF, // Cyrillic
        0x2000, 0x206F, // general punctuation
        0x2070, 0x209F, // super/subscripts
        0x20A0, 0x20BF, // currency
        0x2100, 0x214F, // letterlike symbols
        0x2150, 0x218F, // number forms
        0x2190, 0x21FF, // arrows
        0x2200, 0x22FF, // mathematical operators
        0x2300, 0x23FF, // miscellaneous technical
        0x2460, 0x24FF, // enclosed alphanumerics
        0x2500, 0x257F, // box drawing
        0x2580, 0x259F, // block elements
        0x25A0, 0x25FF, // geometric shapes
        0x2600, 0x26FF, // miscellaneous symbols
        0x2700, 0x27BF, // dingbats
        0x2800, 0x28FF, // braille patterns
        0x2E00, 0x2E7F, // supplemental punctuation
        0x3000, 0x303F, // CJK symbols and punctuation
        0x3040, 0x30FF, // hiragana + katakana
        0x3130, 0x318F, // hangul compatibility jamo
        0x31F0, 0x31FF, // katakana phonetic extensions
        *CommonChineseRanges.RANGES.dropLast(1).toIntArray(),
        0xFF00, 0xFFEF, // halfwidth and fullwidth forms
        0,
    )

    /**
     * [fallback] plus emoji and the private use area, where Nerd Font and
     * Powerline icons live.
     *
     * Only worth it for a face that actually carries them: a CJK face usually
     * has thousands of private-use glyphs, which is why the icons are not part
     * of [fallback].
     */
    val fallbackWithIcons: IntArray = intArrayOf(
        *fallback.dropLast(1).toIntArray(),
        0x1F300, 0x1FAFF, // emoji
        0xE000, 0xF8FF, // private use area (Nerd Font, Powerline)
        0,
    )

    /**
     * [fallbackWithIcons] plus the *full* CJK and Hangul blocks.
     *
     * Only pass this to a face that carries them if the atlas size does not
     * matter: it is the difference between a few megabytes and tens of
     * megabytes, and an atlas that overflows silently drops glyphs (which then
     * render as `?`).
     */
    val terminalWithFallbacks: IntArray = intArrayOf(
        *fallbackWithIcons.dropLast(1).toIntArray(),
        0x4E00, 0x9FFF, // CJK unified ideographs
        0xAC00, 0xD7A3, // hangul syllables
        0xF900, 0xFAFF, // CJK compatibility ideographs
        0xF0000, 0xFFFFD, // supplementary private use area
        0,
    )
}
