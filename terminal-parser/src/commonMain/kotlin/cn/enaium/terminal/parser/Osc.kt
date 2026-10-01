package cn.enaium.terminal.parser

import cn.enaium.terminal.core.ShellMarkType
import cn.enaium.terminal.core.Terminal
import cn.enaium.terminal.core.TerminalColor

/**
 * The OSC (Operating System Command) handler: window title, icon name,
 * working directory, hyperlinks, palette manipulation, clipboard and shell
 * integration.
 *
 * Unknown commands are ignored, which is what makes a terminal usable with
 * applications that probe for features.
 */
internal object OscHandler {

    fun handle(terminal: Terminal, payload: String) {
        val separator = payload.indexOf(';')
        val command = if (separator < 0) payload else payload.substring(0, separator)
        val rest = if (separator < 0) "" else payload.substring(separator + 1)
        when (command) {
            "0" -> {
                terminal.setTitle(rest)
                terminal.setIconName(rest)
            }

            "1" -> terminal.setIconName(rest)
            "2" -> terminal.setTitle(rest)
            "4" -> handlePalette(terminal, rest)
            "7" -> terminal.setWorkingDirectory(rest)
            "8" -> handleHyperlink(terminal, rest)
            "10" -> handleDefaultColor(terminal, slot = 10, rest)
            "11" -> handleDefaultColor(terminal, slot = 11, rest)
            "12" -> handleDefaultColor(terminal, slot = 12, rest)
            "52" -> handleClipboard(terminal, rest)
            "104" -> {
                val index = rest.substringBefore(';').toIntOrNull()
                terminal.resetPalette(index)
            }

            "110" -> terminal.resetDefaultColor(10)
            "111" -> terminal.resetDefaultColor(11)
            "112" -> terminal.resetDefaultColor(12)
            "133" -> handleShellIntegration(terminal, rest)
            else -> Unit
        }
    }

    /** OSC 4: `index;spec[;index;spec]...` with `?` as the query form. */
    private fun handlePalette(terminal: Terminal, rest: String) {
        val parts = rest.split(';')
        val responses = StringBuilder()
        var i = 0
        while (i + 1 < parts.size) {
            val index = parts[i].toIntOrNull()
            val spec = parts[i + 1]
            if (index == null) {
                i += 2
                continue
            }
            if (spec == "?") {
                val color = TerminalColor.rgb(terminal.paletteRgb(index))
                responses.append(index).append(';').append(formatColor(color, terminal)).append(';')
            } else {
                parseColor(spec)?.let { terminal.setPaletteColor(index, it) }
            }
            i += 2
        }
        if (responses.isNotEmpty()) {
            responses.setLength(responses.length - 1)
            terminal.respond("\u001B]4;$responses\u001B\\")
        }
    }

    /** OSC 8: `params;uri`, an empty URI closes the current hyperlink. */
    private fun handleHyperlink(terminal: Terminal, rest: String) {
        val separator = rest.indexOf(';')
        val params = if (separator < 0) "" else rest.substring(0, separator)
        val uri = if (separator < 0) rest else rest.substring(separator + 1)
        val id = params.split(':').firstOrNull { it.startsWith("id=") }?.removePrefix("id=")
        terminal.setHyperlink(uri.ifEmpty { null }, id)
    }

    /** OSC 10/11/12: `?` queries, anything else sets the color. */
    private fun handleDefaultColor(terminal: Terminal, slot: Int, rest: String) {
        val spec = rest.substringBefore(';')
        if (spec == "?") {
            val current = terminal.defaultColor(slot)
                ?: when (slot) {
                    10 -> terminal.foreground
                    11 -> terminal.background
                    else -> TerminalColor.Default
                }
            terminal.respond("\u001B]$slot;${formatColor(current, terminal)}\u001B\\")
        } else {
            parseColor(spec)?.let { terminal.setDefaultColor(slot, it) }
        }
    }

    /** OSC 52: `selection;base64`, with `?` requesting the clipboard contents. */
    private fun handleClipboard(terminal: Terminal, rest: String) {
        val separator = rest.indexOf(';')
        val selection = if (separator < 0) "c" else rest.substring(0, separator).ifEmpty { "c" }
        val data = if (separator < 0) "" else rest.substring(separator + 1)
        if (data == "?") {
            val text = terminal.requestClipboard(selection) ?: return
            terminal.respond("\u001B]52;$selection;${Base64.encode(text)}\u001B\\")
        } else {
            Base64.decodeToString(data)?.let { terminal.setClipboard(it, selection) }
        }
    }

    /** OSC 133: shell integration (prompt/command marks). */
    private fun handleShellIntegration(terminal: Terminal, rest: String) {
        val parts = rest.split(';')
        when (parts.firstOrNull()) {
            "A" -> terminal.addShellMark(ShellMarkType.PROMPT_START)
            "B" -> terminal.addShellMark(ShellMarkType.PROMPT_END)
            "C" -> terminal.addShellMark(ShellMarkType.COMMAND_START)
            "D" -> terminal.addShellMark(ShellMarkType.COMMAND_FINISHED, parts.getOrNull(1)?.toIntOrNull() ?: -1)
        }
    }

    /**
     * Parses an X11 color specification: `rgb:RR/GG/BB` (1-4 hex digits per
     * component) or `#RRGGBB`.
     */
    fun parseColor(spec: String): TerminalColor? {
        if (spec.startsWith("rgb:")) {
            val parts = spec.removePrefix("rgb:").split('/')
            if (parts.size < 3) return null
            val r = parseHexComponent(parts[0]) ?: return null
            val g = parseHexComponent(parts[1]) ?: return null
            val b = parseHexComponent(parts[2]) ?: return null
            return TerminalColor.rgb(r, g, b)
        }
        if (spec.startsWith("#")) {
            val hex = spec.substring(1)
            return when (hex.length) {
                3 -> {
                    val r = hex[0].digitToIntOrNull(16) ?: return null
                    val g = hex[1].digitToIntOrNull(16) ?: return null
                    val b = hex[2].digitToIntOrNull(16) ?: return null
                    TerminalColor.rgb(r * 17, g * 17, b * 17)
                }

                6 -> {
                    val value = hex.toLongOrNull(16) ?: return null
                    TerminalColor.rgb(value.toInt())
                }

                else -> null
            }
        }
        return null
    }

    /** Scales a 1-4 digit hex component to 0..255. */
    private fun parseHexComponent(text: String): Int? {
        if (text.isEmpty() || text.length > 4) return null
        val value = text.toIntOrNull(16) ?: return null
        return when (text.length) {
            1 -> value * 17
            2 -> value
            3 -> value shr 4
            else -> value shr 8
        }
    }

    /** Formats a color as `rgb:RRRR/GGGG/BBBB`, resolving indexed colors through the palette. */
    private fun formatColor(color: TerminalColor, terminal: Terminal): String {
        val rgb = when {
            color.isRgb -> color.rgb
            color.isIndexed -> terminal.paletteRgb(color.index)
            else -> 0
        }
        return "rgb:${hex4((rgb shr 16) and 0xFF)}/${hex4((rgb shr 8) and 0xFF)}/${hex4(rgb and 0xFF)}"
    }

    /** A byte as four hex digits (X11 doubles the byte). */
    private fun hex4(value: Int): String {
        val scaled = value * 257
        val digits = "0123456789abcdef"
        val sb = StringBuilder(4)
        for (shift in 12 downTo 0 step 4) {
            sb.append(digits[(scaled shr shift) and 0xF])
        }
        return sb.toString()
    }
}
