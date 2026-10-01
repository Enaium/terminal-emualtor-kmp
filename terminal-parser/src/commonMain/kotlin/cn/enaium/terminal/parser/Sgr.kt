package cn.enaium.terminal.parser

import cn.enaium.terminal.core.Terminal
import cn.enaium.terminal.core.TerminalAttributes
import cn.enaium.terminal.core.TerminalColor

/**
 * The `CSI ... m` (Select Graphic Rendition) handler.
 *
 * Parameters may be separated by `;` or `:` (the latter introduces
 * sub-parameters, as in `4:3` for a curly underline or `38:2::r:g:b` for a
 * true color with an explicit color space). [apply] walks the flat parameter
 * arrays the parser produced and updates the terminal's pen.
 */
object Sgr {

    /**
     * Applies [count] parameters.
     *
     * @param params the parsed values
     * @param hasValue false for an empty parameter (`;;`), which means "default" (0 for SGR)
     * @param isSub true when the parameter was introduced by a colon
     */
    fun apply(
        terminal: Terminal,
        params: IntArray,
        hasValue: BooleanArray,
        isSub: BooleanArray,
        count: Int,
    ) {
        if (count == 0) {
            terminal.resetPen()
            return
        }
        var i = 0
        while (i < count) {
            val value = if (hasValue[i]) params[i] else 0
            if (isSub[i]) {
                // A sub-parameter is consumed by its parent; a stray one is ignored.
                i++
                continue
            }
            when {
                value == 0 -> {
                    terminal.resetPen()
                    i++
                }

                value == 1 -> {
                    terminal.setAttributes(terminal.attributes.with(TerminalAttributes.BOLD, true))
                    i++
                }

                value == 2 -> {
                    terminal.setAttributes(terminal.attributes.with(TerminalAttributes.DIM, true))
                    i++
                }

                value == 3 -> {
                    terminal.setAttributes(terminal.attributes.with(TerminalAttributes.ITALIC, true))
                    i++
                }

                value == 4 -> {
                    // 4 is a plain underline; 4:n selects a style, 4:0 turns it off.
                    var attributes = terminal.attributes
                    val hasVariant = i + 1 < count && isSub[i + 1]
                    val variant = if (hasVariant && hasValue[i + 1]) params[i + 1] else if (hasVariant) 0 else 1
                    attributes = when (variant) {
                        0 -> attributes.withMask(TerminalAttributes.UNDERLINE_MASK, false)
                        1 -> attributes.withMask(TerminalAttributes.UNDERLINE_MASK, false)
                            .with(TerminalAttributes.UNDERLINE, true)

                        2 -> attributes.withMask(TerminalAttributes.UNDERLINE_MASK, false)
                            .with(TerminalAttributes.DOUBLE_UNDERLINE, true)

                        3 -> attributes.withMask(TerminalAttributes.UNDERLINE_MASK, false)
                            .with(TerminalAttributes.CURLY_UNDERLINE, true)

                        4 -> attributes.withMask(TerminalAttributes.UNDERLINE_MASK, false)
                            .with(TerminalAttributes.DOTTED_UNDERLINE, true)

                        5 -> attributes.withMask(TerminalAttributes.UNDERLINE_MASK, false)
                            .with(TerminalAttributes.DASHED_UNDERLINE, true)

                        else -> attributes.with(TerminalAttributes.UNDERLINE, true)
                    }
                    terminal.setAttributes(attributes)
                    i += if (hasVariant) 2 else 1
                }

                value == 5 || value == 6 -> {
                    terminal.setAttributes(terminal.attributes.with(TerminalAttributes.BLINK, true))
                    i++
                }

                value == 7 -> {
                    terminal.setAttributes(terminal.attributes.with(TerminalAttributes.INVERSE, true))
                    i++
                }

                value == 8 -> {
                    terminal.setAttributes(terminal.attributes.with(TerminalAttributes.HIDDEN, true))
                    i++
                }

                value == 9 -> {
                    terminal.setAttributes(terminal.attributes.with(TerminalAttributes.STRIKETHROUGH, true))
                    i++
                }

                value == 21 -> {
                    // Doubly underlined (ecma-48) - treated as a styled underline.
                    terminal.setAttributes(terminal.attributes.with(TerminalAttributes.DOUBLE_UNDERLINE, true))
                    i++
                }

                value == 22 -> {
                    terminal.setAttributes(terminal.attributes.withMask(TerminalAttributes.INTENSITY_MASK, false))
                    i++
                }

                value == 23 -> {
                    terminal.setAttributes(terminal.attributes.with(TerminalAttributes.ITALIC, false))
                    i++
                }

                value == 24 -> {
                    terminal.setAttributes(terminal.attributes.withMask(TerminalAttributes.UNDERLINE_MASK, false))
                    i++
                }

                value == 25 -> {
                    terminal.setAttributes(terminal.attributes.with(TerminalAttributes.BLINK, false))
                    i++
                }

                value == 27 -> {
                    terminal.setAttributes(terminal.attributes.with(TerminalAttributes.INVERSE, false))
                    i++
                }

                value == 28 -> {
                    terminal.setAttributes(terminal.attributes.with(TerminalAttributes.HIDDEN, false))
                    i++
                }

                value == 29 -> {
                    terminal.setAttributes(terminal.attributes.with(TerminalAttributes.STRIKETHROUGH, false))
                    i++
                }

                value in 30..37 -> {
                    terminal.setForeground(TerminalColor.indexed(value - 30))
                    i++
                }

                value == 38 -> {
                    val (color, next) = readExtendedColor(params, hasValue, isSub, count, i)
                    if (color != null) terminal.setForeground(color)
                    i = next
                }

                value == 39 -> {
                    terminal.setForeground(TerminalColor.Default)
                    i++
                }

                value in 40..47 -> {
                    terminal.setBackground(TerminalColor.indexed(value - 40))
                    i++
                }

                value == 48 -> {
                    val (color, next) = readExtendedColor(params, hasValue, isSub, count, i)
                    if (color != null) terminal.setBackground(color)
                    i = next
                }

                value == 49 -> {
                    terminal.setBackground(TerminalColor.Default)
                    i++
                }

                value == 58 -> {
                    val (color, next) = readExtendedColor(params, hasValue, isSub, count, i)
                    if (color != null) terminal.setUnderlineColor(color)
                    i = next
                }

                value == 59 -> {
                    terminal.resetUnderlineColor()
                    i++
                }

                value in 90..97 -> {
                    terminal.setForeground(TerminalColor.indexed(value - 90 + 8))
                    i++
                }

                value in 100..107 -> {
                    terminal.setBackground(TerminalColor.indexed(value - 100 + 8))
                    i++
                }

                else -> i++
            }
        }
    }

    /**
     * Reads the color of an `38`/`48`/`58` parameter, in both the colon form
     * (`38:5:196`, `38:2::r:g:b`) and the semicolon form (`38;5;196`,
     * `38;2;r;g;b`).
     *
     * Returns the color (or null when the sequence is malformed) and the index
     * of the next parameter to process.
     */
    private fun readExtendedColor(
        params: IntArray,
        hasValue: BooleanArray,
        isSub: BooleanArray,
        count: Int,
        start: Int,
    ): Pair<TerminalColor?, Int> {
        var i = start + 1
        if (i >= count || !hasValue[i]) return null to (start + 1)
        val mode = params[i]
        val colonForm = isSub[i]
        i++
        when (mode) {
            5 -> {
                if (i >= count || !hasValue[i]) return null to i
                return TerminalColor.indexed(params[i]) to (i + 1)
            }

            2 -> {
                // The colon form may carry an empty color-space slot: 38:2::r:g:b
                if (colonForm && i < count && !hasValue[i]) i++
                if (i + 2 >= count || !hasValue[i] || !hasValue[i + 1] || !hasValue[i + 2]) {
                    return null to i
                }
                val color = TerminalColor.rgb(
                    params[i].coerceIn(0, 255),
                    params[i + 1].coerceIn(0, 255),
                    params[i + 2].coerceIn(0, 255),
                )
                return color to (i + 3)
            }

            else -> return null to i
        }
    }
}
