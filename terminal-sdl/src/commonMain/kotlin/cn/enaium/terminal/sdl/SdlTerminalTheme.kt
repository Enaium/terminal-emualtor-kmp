package cn.enaium.terminal.sdl

import cn.enaium.sdl.SDLColor
import cn.enaium.terminal.core.Terminal
import cn.enaium.terminal.core.TerminalColor
import cn.enaium.terminal.core.TerminalPalette

/**
 * The colors an SDL terminal draws with.
 *
 * The shape mirrors `cn.enaium.terminal.imgui.TerminalTheme`: cell colors are
 * resolved through the terminal first ([Terminal.paletteRgb] reflects `OSC 4`
 * overrides, [Terminal.defaultColor] reflects `OSC 10/11`) so applications that
 * restyle the terminal are honoured.
 */
class SdlTerminalTheme(
    /** Packed `0xRRGGBB` of the default text color. */
    val foreground: Int = 0xD4D4D4,
    /** Packed `0xRRGGBB` of the default background. */
    val background: Int = 0x101014,
    /** Packed `0xRRGGBB` of the cursor block. */
    val cursor: Int = 0xD4D4D4,
    /** Packed `0xRRGGBB` of the character under the cursor block. */
    val cursorText: Int = 0x101014,
    /** Packed `0xRRGGBB` of the selection highlight. */
    val selectionBackground: Int = 0x264F78,
    /** Packed `0xRRGGBB` of the scrollback thumb. */
    val scrollbar: Int = 0x4A4A55,
    /** The 256 palette entries as packed `0xRRGGBB`. */
    val palette: IntArray = TerminalPalette.DEFAULT_COLORS,
) {

    /** The RGB the terminal should draw [color] with. */
    fun resolveForeground(terminal: Terminal, color: TerminalColor): Int = when {
        color.isRgb -> color.rgb
        color.isIndexed -> terminal.paletteRgb(color.index)
        else -> terminal.defaultColor(10)?.rgb ?: foreground
    }

    /** The RGB the terminal should draw the background [color] with. */
    fun resolveBackground(terminal: Terminal, color: TerminalColor): Int = when {
        color.isRgb -> color.rgb
        color.isIndexed -> terminal.paletteRgb(color.index)
        else -> terminal.defaultColor(11)?.rgb ?: background
    }

    companion object {
        /** A dark theme close to the common terminal defaults. */
        val Default: SdlTerminalTheme = SdlTerminalTheme()
    }
}

/**
 * Packs an `0xRRGGBB` value into an [SDLColor].
 *
 * Kept next to the theme (rather than on the renderer) so callers can build
 * colors for hosts that draw their own chrome around the terminal.
 */
fun sdlColor(rgb: Int, alpha: Int = 255): SDLColor =
    SDLColor((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, alpha)
