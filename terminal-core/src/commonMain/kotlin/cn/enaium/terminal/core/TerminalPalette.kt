package cn.enaium.terminal.core

/**
 * The 256 color palette used to resolve indexed colors.
 *
 * The defaults follow the xterm table (16 system colors, the 6x6x6 color cube
 * and 24 grayscale steps); applications can override entries with `OSC 4` and
 * reset them with `OSC 104`. Renderers read [rgb] to turn a
 * [TerminalColor.indexed] cell color into pixels.
 */
class TerminalPalette {

    private val colors = IntArray(256) { DEFAULT_COLORS[it] }

    /** The packed `0xRRGGBB` value of palette entry [index] (masked to 8 bits). */
    fun rgb(index: Int): Int = colors[index and 0xFF]

    /** Overrides palette entry [index] with a packed `0xRRGGBB` value. */
    fun set(index: Int, rgb: Int) {
        colors[index and 0xFF] = rgb and 0xFFFFFF
    }

    /** Restores the default of [index], or the whole palette when null. */
    fun reset(index: Int? = null) {
        if (index == null) {
            for (i in colors.indices) colors[i] = DEFAULT_COLORS[i]
        } else {
            colors[index and 0xFF] = DEFAULT_COLORS[index and 0xFF]
        }
    }

    /** True when [index] still holds its default value. */
    fun isDefault(index: Int): Boolean = colors[index and 0xFF] == DEFAULT_COLORS[index and 0xFF]

    companion object {
        /** The 16 system colors (ANSI + bright) as `0xRRGGBB`. */
        val SYSTEM_COLORS: IntArray = intArrayOf(
            0x000000, 0xCD0000, 0x00CD00, 0xCDCD00, 0x0000EE, 0xCD00CD, 0x00CDCD, 0xE5E5E5,
            0x7F7F7F, 0xFF0000, 0x00FF00, 0xFFFF00, 0x5C5CFF, 0xFF00FF, 0x00FFFF, 0xFFFFFF,
        )

        /** The full xterm-compatible 256 entry palette. */
        val DEFAULT_COLORS: IntArray = IntArray(256) { index ->
            when {
                index < 16 -> SYSTEM_COLORS[index]
                index < 232 -> {
                    val cube = index - 16
                    val steps = intArrayOf(0, 95, 135, 175, 215, 255)
                    val r = steps[cube / 36]
                    val g = steps[(cube / 6) % 6]
                    val b = steps[cube % 6]
                    (r shl 16) or (g shl 8) or b
                }

                else -> {
                    val level = 8 + (index - 232) * 10
                    (level shl 16) or (level shl 8) or level
                }
            }
        }
    }
}
