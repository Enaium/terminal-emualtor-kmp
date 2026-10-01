package cn.enaium.terminal.core

import kotlin.jvm.JvmInline

/**
 * A terminal color: the terminal default, a palette index (0..255) or a 24-bit
 * RGB value.
 *
 * The three kinds are packed into a single `Int` when stored in a cell
 * (bits 24-25 = kind, bits 0-23 = value), so a color never allocates inside
 * the screen buffers.
 */
data class TerminalColor private constructor(
    /** One of [KIND_DEFAULT], [KIND_INDEXED], [KIND_RGB]. */
    val kind: Int,
    /** Palette index for [KIND_INDEXED], `0xRRGGBB` for [KIND_RGB], 0 otherwise. */
    val value: Int,
) {

    val isDefault: Boolean get() = kind == KIND_DEFAULT
    val isIndexed: Boolean get() = kind == KIND_INDEXED
    val isRgb: Boolean get() = kind == KIND_RGB

    /** Palette index, or 0 when this is not an indexed color. */
    val index: Int get() = if (kind == KIND_INDEXED) value else 0

    /** Red component (0..255) of an RGB color, or 0. */
    val red: Int get() = if (kind == KIND_RGB) (value shr 16) and 0xFF else 0

    /** Green component (0..255) of an RGB color, or 0. */
    val green: Int get() = if (kind == KIND_RGB) (value shr 8) and 0xFF else 0

    /** Blue component (0..255) of an RGB color, or 0. */
    val blue: Int get() = if (kind == KIND_RGB) value and 0xFF else 0

    /** The `0xRRGGBB` value of an RGB color, or 0. */
    val rgb: Int get() = if (kind == KIND_RGB) value else 0

    override fun toString(): String = when (kind) {
        KIND_INDEXED -> "color($index)"
        KIND_RGB -> "color(#${value.toString(16).padStart(6, '0')})"
        else -> "default"
    }

    companion object {
        const val KIND_DEFAULT = 0
        const val KIND_INDEXED = 1
        const val KIND_RGB = 2

        /** The terminal's configured default color. */
        val Default: TerminalColor = TerminalColor(KIND_DEFAULT, 0)

        /** A palette color (0..255; values are masked to 8 bits). */
        fun indexed(index: Int): TerminalColor = TerminalColor(KIND_INDEXED, index and 0xFF)

        /** A 24-bit RGB color; components are masked to 8 bits. */
        fun rgb(r: Int, g: Int, b: Int): TerminalColor =
            TerminalColor(KIND_RGB, ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF))

        /** A 24-bit RGB color from a packed `0xRRGGBB` value. */
        fun rgb(packed: Int): TerminalColor = TerminalColor(KIND_RGB, packed and 0xFFFFFF)

        /** Decodes the packed representation used by the screen buffers. */
        fun fromPacked(packed: Int): TerminalColor = TerminalColor((packed ushr 24) and 0x3, packed and 0xFFFFFF)

        /** Encodes this color for cell storage. */
        fun toPacked(color: TerminalColor): Int = (color.kind shl 24) or (color.value and 0xFFFFFF)
    }
}

/**
 * The SGR attributes of a cell, packed into an `Int`.
 *
 * Underline variants are mutually exclusive in practice but are stored
 * independently: a terminal that receives `4:3` then `4` simply clears the
 * variant bits.
 */
@JvmInline
value class TerminalAttributes(val bits: Int) {

    val bold: Boolean get() = has(BOLD)
    val dim: Boolean get() = has(DIM)
    val italic: Boolean get() = has(ITALIC)
    val underline: Boolean get() = has(UNDERLINE)
    val doubleUnderline: Boolean get() = has(DOUBLE_UNDERLINE)
    val curlyUnderline: Boolean get() = has(CURLY_UNDERLINE)
    val dottedUnderline: Boolean get() = has(DOTTED_UNDERLINE)
    val dashedUnderline: Boolean get() = has(DASHED_UNDERLINE)
    val strikethrough: Boolean get() = has(STRIKETHROUGH)
    val inverse: Boolean get() = has(INVERSE)
    val hidden: Boolean get() = has(HIDDEN)
    val blink: Boolean get() = has(BLINK)

    /** True when any underline variant is set. */
    val anyUnderline: Boolean get() = bits and UNDERLINE_MASK != 0

    fun has(flag: Int): Boolean = bits and flag != 0

    /** Returns a copy with [flag] set or cleared. */
    fun with(flag: Int, enabled: Boolean): TerminalAttributes =
        TerminalAttributes(if (enabled) bits or flag else bits and flag.inv())

    /** Returns a copy with every bit in [mask] set or cleared. */
    fun withMask(mask: Int, enabled: Boolean): TerminalAttributes =
        TerminalAttributes(if (enabled) bits or mask else bits and mask.inv())

    override fun toString(): String {
        if (bits == 0) return "none"
        val names = mutableListOf<String>()
        if (bold) names += "bold"
        if (dim) names += "dim"
        if (italic) names += "italic"
        if (underline) names += "underline"
        if (doubleUnderline) names += "double-underline"
        if (curlyUnderline) names += "curly-underline"
        if (dottedUnderline) names += "dotted-underline"
        if (dashedUnderline) names += "dashed-underline"
        if (strikethrough) names += "strikethrough"
        if (inverse) names += "inverse"
        if (hidden) names += "hidden"
        if (blink) names += "blink"
        return names.joinToString("|")
    }

    companion object {
        const val BOLD = 1 shl 0
        const val DIM = 1 shl 1
        const val ITALIC = 1 shl 2
        const val UNDERLINE = 1 shl 3
        const val DOUBLE_UNDERLINE = 1 shl 4
        const val CURLY_UNDERLINE = 1 shl 5
        const val DOTTED_UNDERLINE = 1 shl 6
        const val DASHED_UNDERLINE = 1 shl 7
        const val STRIKETHROUGH = 1 shl 8
        const val INVERSE = 1 shl 9
        const val HIDDEN = 1 shl 10
        const val BLINK = 1 shl 11

        /** Every underline variant (plain and styled). */
        const val UNDERLINE_MASK =
            UNDERLINE or DOUBLE_UNDERLINE or CURLY_UNDERLINE or DOTTED_UNDERLINE or DASHED_UNDERLINE

        /** Every intensity attribute ([BOLD] and [DIM]). */
        const val INTENSITY_MASK = BOLD or DIM

        val None: TerminalAttributes = TerminalAttributes(0)
    }
}

/** The shape the terminal cursor is drawn with. */
enum class TerminalCursorShape {
    BLOCK,
    BAR,
    UNDERLINE,
}

/** Cursor shape plus blink state (DECSCUSR). */
data class TerminalCursorStyle(
    val shape: TerminalCursorShape = TerminalCursorShape.BLOCK,
    val blink: Boolean = true,
) {
    companion object {
        val Default: TerminalCursorStyle = TerminalCursorStyle()

        /** Maps a DECSCUSR parameter (0..6) to a style; other values keep [Default]. */
        fun fromDecscusr(param: Int): TerminalCursorStyle = when (param) {
            0, 1 -> TerminalCursorStyle(TerminalCursorShape.BLOCK, true)
            2 -> TerminalCursorStyle(TerminalCursorShape.BLOCK, false)
            3 -> TerminalCursorStyle(TerminalCursorShape.BAR, true)
            4 -> TerminalCursorStyle(TerminalCursorShape.BAR, false)
            5 -> TerminalCursorStyle(TerminalCursorShape.UNDERLINE, true)
            6 -> TerminalCursorStyle(TerminalCursorShape.UNDERLINE, false)
            else -> Default
        }
    }
}
