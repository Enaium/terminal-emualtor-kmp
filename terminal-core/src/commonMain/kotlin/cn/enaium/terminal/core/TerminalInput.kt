package cn.enaium.terminal.core

import cn.enaium.terminal.unicode.Utf8

/** A key press the host wants to forward to the application. */
sealed class TerminalKey {

    /** A printable character produced by the keyboard layout (or an IME). */
    data class Character(val value: Char) : TerminalKey()

    data object Enter : TerminalKey()
    data object Backspace : TerminalKey()
    data object Tab : TerminalKey()
    data object Escape : TerminalKey()
    data object Delete : TerminalKey()
    data object Insert : TerminalKey()
    data object Home : TerminalKey()
    data object End : TerminalKey()
    data object PageUp : TerminalKey()
    data object PageDown : TerminalKey()
    data object Up : TerminalKey()
    data object Down : TerminalKey()
    data object Left : TerminalKey()
    data object Right : TerminalKey()

    /** A function key (1..24). */
    data class Function(val number: Int) : TerminalKey()
}

/** Mouse buttons as reported to the application. */
enum class TerminalMouseButton {
    LEFT,
    MIDDLE,
    RIGHT,
    WHEEL_UP,
    WHEEL_DOWN,
    /** Buttons 8 and 9 (back/forward in most terminals). */
    BUTTON_8,
    BUTTON_9,
}

/** What happened to the mouse button. */
enum class TerminalMouseAction {
    PRESS,
    RELEASE,
    MOVE,
}

/**
 * Translates host input into the byte sequences a terminal application
 * expects, honouring the DEC modes the application set (application cursor
 * keys, bracketed paste, mouse tracking level and encoding).
 *
 * The host is responsible for the host-side bindings (copy/paste shortcuts,
 * font zoom, ...); everything that must reach the application goes through
 * this class.
 */
class TerminalInput(private val terminal: Terminal) {

    /** Encodes a key press, or an empty array when the key produces nothing. */
    fun key(
        key: TerminalKey,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
    ): ByteArray {
        val modifier = modifierParameter(ctrl, alt, shift)
        val application = terminal.modes.applicationCursorKeys
        val bytes: String = when (key) {
            is TerminalKey.Character -> {
                val ch = key.value
                val encoded = when {
                    ctrl -> controlCharacter(ch)
                    else -> ch
                }
                if (encoded == null) {
                    return ByteArray(0)
                } else {
                    val text = if (ctrl) encoded.toString() else ch.toString()
                    if (alt) "\u001B$text" else text
                }
            }

            TerminalKey.Enter -> if (terminal.modes.newLine) "\r\n" else "\r"

            TerminalKey.Backspace -> {
                val value = if (terminal.modes.backspaceSendsBs || ctrl) '\b' else 0x7F.toChar()
                if (alt) "\u001B$value" else value.toString()
            }

            TerminalKey.Tab -> when {
                shift -> "\u001B[Z"
                alt -> "\u001B\t"
                else -> "\t"
            }

            TerminalKey.Escape -> "\u001B"

            TerminalKey.Up -> cursorKey('A', application, modifier)
            TerminalKey.Down -> cursorKey('B', application, modifier)
            TerminalKey.Right -> cursorKey('C', application, modifier)
            TerminalKey.Left -> cursorKey('D', application, modifier)
            TerminalKey.Home -> cursorKey('H', application, modifier)
            TerminalKey.End -> cursorKey('F', application, modifier)

            TerminalKey.Insert -> tildeKey(2, modifier)
            TerminalKey.Delete -> tildeKey(3, modifier)
            TerminalKey.PageUp -> tildeKey(5, modifier)
            TerminalKey.PageDown -> tildeKey(6, modifier)

            is TerminalKey.Function -> functionKey(key.number, modifier)
        }
        return Utf8.encode(bytes)
    }

    /** Encodes typed text (including IME output). */
    fun text(text: String): ByteArray = Utf8.encode(text)

    /**
     * Encodes pasted text: wrapped in `ESC [ 200 ~` / `ESC [ 201 ~` when the
     * application enabled bracketed paste, with embedded end markers removed
     * and newlines normalised to carriage returns.
     */
    fun paste(text: String): ByteArray {
        val normalized = text.replace("\r\n", "\n").replace('\n', '\r')
        return if (terminal.modes.bracketedPaste) {
            val safe = normalized.replace("\u001B[201~", "")
            Utf8.encode("\u001B[200~$safe\u001B[201~")
        } else {
            Utf8.encode(normalized)
        }
    }

    /** Encodes a focus change, or null when the application does not want them (`?1004`). */
    fun focus(gained: Boolean): ByteArray? {
        if (!terminal.modes.focusReporting) return null
        return Utf8.encode(if (gained) "\u001B[I" else "\u001B[O")
    }

    /** Encodes a mouse button event, or null when the application does not track the mouse. */
    fun mouse(
        button: TerminalMouseButton,
        action: TerminalMouseAction,
        column: Int,
        row: Int,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
    ): ByteArray? {
        val modes = terminal.modes
        if (!modes.mouseTracking) return null
        if (action == TerminalMouseAction.MOVE && modes.mouseReporting != MouseReporting.BUTTON &&
            modes.mouseReporting != MouseReporting.ANY
        ) {
            return null
        }
        if (action == TerminalMouseAction.RELEASE && modes.mouseReporting == MouseReporting.X10) {
            return null
        }

        var code = when (button) {
            TerminalMouseButton.LEFT -> 0
            TerminalMouseButton.MIDDLE -> 1
            TerminalMouseButton.RIGHT -> 2
            TerminalMouseButton.WHEEL_UP -> 64
            TerminalMouseButton.WHEEL_DOWN -> 65
            TerminalMouseButton.BUTTON_8 -> 128
            TerminalMouseButton.BUTTON_9 -> 129
        }
        if (action == TerminalMouseAction.MOVE) code = code or 32
        if (action == TerminalMouseAction.RELEASE && modes.mouseEncoding != MouseEncoding.SGR) code = 3
        if (shift) code = code or 4
        if (alt) code = code or 8
        if (ctrl) code = code or 16

        val x = column + 1
        val y = row + 1
        val sequence = when (modes.mouseEncoding) {
            MouseEncoding.SGR -> {
                val final = if (action == TerminalMouseAction.RELEASE) 'm' else 'M'
                "\u001B[<$code;$x;$y$final"
            }

            MouseEncoding.URXVT -> "\u001B[$code;$x;${y}M"

            MouseEncoding.UTF8 -> {
                val sb = StringBuilder()
                sb.append("\u001B[M")
                appendUtf8(sb, code + 32)
                appendUtf8(sb, x + 32)
                appendUtf8(sb, y + 32)
                sb.toString()
            }

            MouseEncoding.X10 -> {
                if (x + 32 > 255 || y + 32 > 255) return null
                "\u001B[M" + (code + 32).toChar() + (x + 32).toChar() + (y + 32).toChar()
            }
        }
        return Utf8.encode(sequence)
    }

    /** Encodes a wheel event; the application receives it as a mouse button 4/5 event. */
    fun wheel(
        up: Boolean,
        column: Int,
        row: Int,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
    ): ByteArray? = mouse(
        button = if (up) TerminalMouseButton.WHEEL_UP else TerminalMouseButton.WHEEL_DOWN,
        action = TerminalMouseAction.PRESS,
        column = column,
        row = row,
        ctrl = ctrl,
        alt = alt,
        shift = shift,
    )

    private fun appendUtf8(sb: StringBuilder, value: Int) {
        if (value < 0x80) {
            sb.append(value.toChar())
        } else {
            sb.append(((value shr 6) or 0xC0).toChar())
            sb.append(((value and 0x3F) or 0x80).toChar())
        }
    }

    private fun modifierParameter(ctrl: Boolean, alt: Boolean, shift: Boolean): Int =
        1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)

    private fun cursorKey(final: Char, application: Boolean, modifier: Int): String = when {
        modifier > 1 -> "\u001B[1;$modifier$final"
        application -> "\u001BO$final"
        else -> "\u001B[$final"
    }

    private fun tildeKey(number: Int, modifier: Int): String =
        if (modifier > 1) "\u001B[$number;$modifier~" else "\u001B[$number~"

    private fun functionKey(number: Int, modifier: Int): String = when {
        number in 1..4 -> {
            val final = ('P' + (number - 1))
            if (modifier > 1) "\u001B[1;$modifier$final" else "\u001BO$final"
        }

        number == 5 -> tildeKey(15, modifier)
        number == 6 -> tildeKey(17, modifier)
        number == 7 -> tildeKey(18, modifier)
        number == 8 -> tildeKey(19, modifier)
        number == 9 -> tildeKey(20, modifier)
        number == 10 -> tildeKey(21, modifier)
        number == 11 -> tildeKey(23, modifier)
        number == 12 -> tildeKey(24, modifier)
        else -> ""
    }

    /** Maps a letter/digit/punctuation character to its control code, or null when it has none. */
    private fun controlCharacter(ch: Char): Char? {
        val upper = ch.uppercaseChar()
        return when {
            upper in 'A'..'Z' -> (upper.code - 'A'.code + 1).toChar()
            ch == ' ' || ch == '@' -> '\u0000'
            ch == '[' -> '\u001B'
            ch == '\\' -> '\u001C'
            ch == ']' -> '\u001D'
            ch == '^' -> '\u001E'
            ch == '_' -> '\u001F'
            ch == '?' -> '\u007F'
            ch == '8' -> '\u007F'
            else -> null
        }
    }
}
