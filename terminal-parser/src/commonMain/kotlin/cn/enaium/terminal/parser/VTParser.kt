package cn.enaium.terminal.parser

import cn.enaium.terminal.core.Terminal
import cn.enaium.terminal.core.TerminalCharset
import cn.enaium.terminal.core.TerminalColor
import cn.enaium.terminal.unicode.Utf8
import cn.enaium.terminal.unicode.Utf8Decoder

/**
 * The VT/ANSI state machine.
 *
 * Bytes are decoded from UTF-8 and either printed at the cursor or consumed by
 * the escape state machine (Paul Williams' `vt100.net` model: Ground, Escape,
 * CSI, DCS, OSC, SOS/PM/APC), which then calls into [Terminal]. Device
 * responses (DA, DSR, DECRQSS, window operations, ...) are sent back through
 * [Terminal.respond].
 *
 * The parser keeps all of its state, so a byte stream can be fed in arbitrary
 * chunks (including in the middle of a multi-byte character or escape
 * sequence). It is not thread-safe: feed it from one thread (the session's
 * pump).
 */
class VTParser(private val terminal: Terminal) {

    private enum class State {
        GROUND,
        ESCAPE,
        ESCAPE_INTERMEDIATE,
        CSI_ENTRY,
        CSI_PARAM,
        CSI_INTERMEDIATE,
        CSI_IGNORE,
        OSC_STRING,
        DCS_ENTRY,
        DCS_PARAM,
        DCS_INTERMEDIATE,
        DCS_PASSTHROUGH,
        DCS_IGNORE,
        SOS_PM_APC_STRING,
    }

    private val decoder = Utf8Decoder()
    private var state = State.GROUND

    private val params = IntArray(MAX_PARAMS)
    private val paramHasValue = BooleanArray(MAX_PARAMS)
    private val paramIsSub = BooleanArray(MAX_PARAMS)
    private var paramCount = 0
    private var currentParam = 0
    private var currentHasValue = false
    private var nextIsSub = false
    private var privateMarker = 0
    private val intermediates = StringBuilder(4)

    private var stringBytes = ByteArray(256)
    private var stringLength = 0

    /** Feeds [length] bytes of [bytes]. */
    fun feed(bytes: ByteArray, length: Int = bytes.size) {
        val count = length.coerceAtMost(bytes.size)
        for (i in 0 until count) {
            advance(bytes[i].toInt() and 0xFF)
        }
    }

    /** Feeds the UTF-8 encoding of [text]. */
    fun feed(text: String) = feed(Utf8.encode(text))

    /** Drops any partially collected sequence (used when the terminal is reset). */
    fun reset() {
        state = State.GROUND
        decoder.reset()
        resetSequence()
        stringLength = 0
    }

    // =====================================================================
    // State machine
    // =====================================================================

    private fun advance(byte: Int) {
        // CAN and SUB abort whatever sequence is being collected.
        if (byte == 0x18 || byte == 0x1A) {
            if (isStringState()) stringLength = 0
            state = State.GROUND
            return
        }
        when (state) {
            State.GROUND -> when {
                byte == 0x1B -> state = State.ESCAPE
                byte < 0x20 -> executeControl(byte)
                byte == 0x7F -> Unit
                else -> decoder.feed(byte) { cp -> terminal.writeCodePoint(cp) }
            }

            State.ESCAPE -> when {
                byte == 0x1B -> Unit
                byte < 0x20 -> executeControl(byte)
                byte == 0x7F -> Unit
                byte in 0x20..0x2F -> {
                    resetSequence()
                    intermediates.append(byte.toChar())
                    state = State.ESCAPE_INTERMEDIATE
                }

                byte == 'P'.code -> {
                    resetSequence()
                    startString()
                    state = State.DCS_ENTRY
                }

                byte == 'X'.code || byte == '^'.code || byte == '_'.code -> {
                    resetSequence()
                    startString()
                    state = State.SOS_PM_APC_STRING
                }

                byte == '['.code -> {
                    resetSequence()
                    state = State.CSI_ENTRY
                }

                byte == ']'.code -> {
                    resetSequence()
                    startString()
                    state = State.OSC_STRING
                }

                else -> {
                    escapeDispatch(byte)
                    state = State.GROUND
                }
            }

            State.ESCAPE_INTERMEDIATE -> when {
                byte == 0x1B -> state = State.ESCAPE
                byte < 0x20 -> executeControl(byte)
                byte == 0x7F -> Unit
                byte in 0x20..0x2F -> intermediates.append(byte.toChar())
                else -> {
                    escapeDispatch(byte)
                    state = State.GROUND
                }
            }

            State.CSI_ENTRY -> when {
                byte == 0x1B -> state = State.ESCAPE
                byte < 0x20 -> executeControl(byte)
                byte == 0x7F -> Unit
                byte in 0x30..0x39 || byte == ';'.code || byte == ':'.code -> {
                    param(byte)
                    state = State.CSI_PARAM
                }

                byte in 0x3C..0x3F -> {
                    privateMarker = byte
                    state = State.CSI_PARAM
                }

                byte in 0x20..0x2F -> {
                    intermediates.append(byte.toChar())
                    state = State.CSI_INTERMEDIATE
                }

                byte in 0x40..0x7E -> {
                    csiDispatch(byte)
                    state = State.GROUND
                }

                else -> state = State.CSI_IGNORE
            }

            State.CSI_PARAM -> when {
                byte == 0x1B -> state = State.ESCAPE
                byte < 0x20 -> executeControl(byte)
                byte == 0x7F -> Unit
                byte in 0x30..0x39 || byte == ';'.code || byte == ':'.code -> param(byte)
                byte in 0x20..0x2F -> {
                    intermediates.append(byte.toChar())
                    state = State.CSI_INTERMEDIATE
                }

                byte in 0x40..0x7E -> {
                    csiDispatch(byte)
                    state = State.GROUND
                }

                else -> state = State.CSI_IGNORE
            }

            State.CSI_INTERMEDIATE -> when {
                byte == 0x1B -> state = State.ESCAPE
                byte < 0x20 -> executeControl(byte)
                byte == 0x7F -> Unit
                byte in 0x20..0x2F -> intermediates.append(byte.toChar())
                byte in 0x40..0x7E -> {
                    csiDispatch(byte)
                    state = State.GROUND
                }

                else -> state = State.CSI_IGNORE
            }

            State.CSI_IGNORE -> if (byte in 0x40..0x7E) state = State.GROUND

            State.OSC_STRING -> when {
                byte == 0x07 -> {
                    dispatchString()
                    state = State.GROUND
                }

                byte == 0x1B -> {
                    dispatchString()
                    state = State.ESCAPE
                }

                byte < 0x20 -> Unit
                else -> appendString(byte)
            }

            State.DCS_ENTRY, State.DCS_PARAM, State.DCS_INTERMEDIATE, State.DCS_PASSTHROUGH -> when {
                byte == 0x07 -> {
                    dispatchDcs()
                    state = State.GROUND
                }

                byte == 0x1B -> {
                    dispatchDcs()
                    state = State.ESCAPE
                }

                byte < 0x20 -> Unit
                else -> appendString(byte)
            }

            State.DCS_IGNORE -> if (byte == 0x1B) state = State.ESCAPE

            State.SOS_PM_APC_STRING -> when {
                byte == 0x07 -> {
                    stringLength = 0
                    state = State.GROUND
                }

                byte == 0x1B -> {
                    stringLength = 0
                    state = State.ESCAPE
                }

                byte < 0x20 -> Unit
                else -> Unit
            }
        }
    }

    private fun isStringState(): Boolean = state == State.OSC_STRING || state == State.DCS_ENTRY ||
        state == State.DCS_PARAM || state == State.DCS_INTERMEDIATE || state == State.DCS_PASSTHROUGH ||
        state == State.DCS_IGNORE || state == State.SOS_PM_APC_STRING

    private fun executeControl(byte: Int) {
        when (byte) {
            0x05 -> Unit // ENQ (answerback) is ignored
            0x07 -> terminal.bell()
            0x08 -> terminal.backspace()
            0x09 -> terminal.tab()
            0x0A, 0x0B, 0x0C -> terminal.lineFeed()
            0x0D -> terminal.carriageReturn()
            0x0E -> terminal.shiftOut()
            0x0F -> terminal.shiftIn()
            else -> Unit
        }
    }

    // =====================================================================
    // Parameter collection
    // =====================================================================

    private fun resetSequence() {
        paramCount = 0
        currentParam = 0
        currentHasValue = false
        nextIsSub = false
        privateMarker = 0
        intermediates.setLength(0)
    }

    private fun param(byte: Int) {
        when (byte) {
            ';'.code -> {
                finishParam()
                nextIsSub = false
            }

            ':'.code -> {
                finishParam()
                nextIsSub = true
            }

            else -> {
                val digit = byte - '0'.code
                if (currentParam < 65535) {
                    currentParam = currentParam * 10 + digit
                    currentHasValue = true
                }
            }
        }
    }

    private fun finishParam() {
        if (paramCount < MAX_PARAMS) {
            params[paramCount] = if (currentHasValue) currentParam else 0
            paramHasValue[paramCount] = currentHasValue
            paramIsSub[paramCount] = nextIsSub
            paramCount++
        }
        currentParam = 0
        currentHasValue = false
    }

    /** Flushes the parameter the sequence ended on. */
    private fun finalizeParams() {
        if (currentHasValue || paramCount > 0) finishParam()
    }

    /** The value of parameter [index], or [default] when it is missing or empty. */
    private fun paramOr(index: Int, default: Int): Int =
        if (index < paramCount && paramHasValue[index]) params[index] else default

    // =====================================================================
    // Escape dispatch
    // =====================================================================

    private fun escapeDispatch(final: Int) {
        val intermediate = intermediates.toString()
        if (intermediate.isNotEmpty()) {
            when (intermediate) {
                "(" -> terminal.designateCharset(0, charsetOf(final))
                ")" -> terminal.designateCharset(1, charsetOf(final))
                "*" -> terminal.designateCharset(2, charsetOf(final))
                "+" -> terminal.designateCharset(3, charsetOf(final))
                "#" -> if (final == '8'.code) screenAlignmentTest()
            }
            return
        }
        when (final) {
            'D'.code -> terminal.lineFeed()
            'E'.code -> {
                terminal.carriageReturn()
                terminal.lineFeed()
            }

            'H'.code -> terminal.setTabStop()
            'M'.code -> terminal.reverseLineFeed()
            'N'.code, 'O'.code -> Unit // SS2/SS3
            'Z'.code -> terminal.respond("\u001B[?6c")
            '='.code -> terminal.modes.applicationKeypad = true
            '>'.code -> terminal.modes.applicationKeypad = false
            '7'.code -> terminal.saveCursor()
            '8'.code -> terminal.restoreCursor()
            'c'.code -> {
                terminal.reset()
                reset()
            }

            '\\'.code -> Unit
            else -> Unit
        }
    }

    private fun charsetOf(final: Int): TerminalCharset = when (final.toChar()) {
        '0' -> TerminalCharset.DEC_SPECIAL_GRAPHICS
        'A', 'U' -> TerminalCharset.UK
        else -> TerminalCharset.ASCII
    }

    /** DECALN: fills the screen with `E`. */
    private fun screenAlignmentTest() {
        terminal.eraseInDisplay(2)
        val row = "E".repeat(terminal.columns)
        for (line in 1..terminal.rows) {
            terminal.cursorTo(line, 1)
            terminal.write(row)
        }
        terminal.cursorTo(1, 1)
    }

    // =====================================================================
    // CSI dispatch
    // =====================================================================

    private fun csiDispatch(final: Int) {
        finalizeParams()
        val ch = final.toChar()
        val intermediate = intermediates.toString()

        when (privateMarker.toChar()) {
            '?' -> {
                when (ch) {
                    'h' -> setPrivateModes(true)
                    'l' -> setPrivateModes(false)
                    'J' -> terminal.eraseInDisplay(paramOr(0, 0))
                    'K' -> terminal.eraseInLine(paramOr(0, 0))
                    'n' -> deviceStatusReport(private = true)
                    'p' -> requestMode(private = true)
                    's' -> terminal.saveCursor()
                    'u' -> terminal.restoreCursor()
                    'c' -> terminal.respond("\u001B[?62;1;2;6;9;15;22c")
                    else -> Unit
                }
                return
            }

            '>' -> {
                when (ch) {
                    'c' -> terminal.respond("\u001B[>0;1;0c")
                    'n' -> deviceStatusReport(private = true)
                    'p' -> Unit
                    'q' -> Unit
                    else -> Unit
                }
                return
            }

            '<' -> {
                if (ch == 'c') terminal.respond("\u001B[?62;1;2;6;9;15;22c")
                return
            }

            '=' -> return
        }

        if (intermediate.isNotEmpty()) {
            when {
                intermediate == " " && ch == 'q' -> terminal.setCursorShape(paramOr(0, 0))
                intermediate == "!" && ch == 'p' -> {
                    terminal.softReset()
                    reset()
                }

                intermediate == "\$" && ch == 'p' -> requestMode(private = false)
                intermediate == "\$" && ch == 'q' -> Unit
                intermediate == "\"" && ch == 'q' -> Unit // DECSCA
                intermediate == "'" && (ch == '}' || ch == '~') -> Unit // DECIC/DECDC
                else -> Unit
            }
            return
        }

        when (ch) {
            '@' -> terminal.insertChars(paramOr(0, 1))
            'A' -> terminal.cursorUp(paramOr(0, 1))
            'B' -> terminal.cursorDown(paramOr(0, 1))
            'C' -> terminal.cursorForward(paramOr(0, 1))
            'D' -> terminal.cursorBackward(paramOr(0, 1))
            'E' -> terminal.cursorNextLine(paramOr(0, 1))
            'F' -> terminal.cursorPreviousLine(paramOr(0, 1))
            'G', '`' -> terminal.cursorToColumn(paramOr(0, 1))
            'H', 'f' -> terminal.cursorTo(paramOr(0, 1), paramOr(1, 1))
            'I' -> repeat(paramOr(0, 1)) { terminal.tab() }
            'J' -> terminal.eraseInDisplay(paramOr(0, 0))
            'K' -> terminal.eraseInLine(paramOr(0, 0))
            'L' -> terminal.insertLines(paramOr(0, 1))
            'M' -> terminal.deleteLines(paramOr(0, 1))
            'P' -> terminal.deleteChars(paramOr(0, 1))
            'S' -> terminal.scrollUp(paramOr(0, 1))
            'T' -> terminal.scrollDown(paramOr(0, 1))
            'X' -> terminal.eraseChars(paramOr(0, 1))
            'Z' -> terminal.backTab(paramOr(0, 1))
            'a' -> terminal.cursorForward(paramOr(0, 1))
            'b' -> terminal.repeatLastCharacter(paramOr(0, 1))
            'c' -> terminal.respond("\u001B[?62;1;2;6;9;15;22c")
            'd' -> terminal.cursorToRow(paramOr(0, 1))
            'e' -> terminal.cursorDown(paramOr(0, 1))
            'g' -> terminal.clearTabStop(paramOr(0, 0))
            'h' -> for (i in 0 until paramCount) terminal.setMode(paramOr(i, 0), true)
            'l' -> for (i in 0 until paramCount) terminal.setMode(paramOr(i, 0), false)
            'm' -> Sgr.apply(terminal, params, paramHasValue, paramIsSub, paramCount)
            'n' -> deviceStatusReport(private = false)
            'p' -> Unit
            'q' -> Unit
            'r' -> terminal.setScrollRegion(paramOr(0, 1), paramOr(1, terminal.rows))
            's' -> terminal.saveCursor()
            't' -> windowOperation()
            'u' -> terminal.restoreCursor()
            'x' -> Unit
            else -> Unit
        }
    }

    private fun setPrivateModes(enabled: Boolean) {
        for (i in 0 until paramCount) {
            val mode = paramOr(i, 0)
            if (mode > 0) terminal.setPrivateMode(mode, enabled)
        }
    }

    private fun deviceStatusReport(private: Boolean) {
        when (paramOr(0, 0)) {
            5 -> terminal.respond("\u001B[0n")
            6 -> {
                val row = terminal.cursorRow + 1
                val column = terminal.cursorColumn + 1
                terminal.respond(if (private) "\u001B[?$row;${column}R" else "\u001B[$row;${column}R")
            }

            15 -> terminal.respond("\u001B[?13n")
            25 -> terminal.respond("\u001B[?20n")
            26 -> terminal.respond("\u001B[?27;1;0;0n")
            else -> Unit
        }
    }

    /** DECRQM / DECRQM-ANSI: reports whether a mode is set. */
    private fun requestMode(private: Boolean) {
        val mode = paramOr(0, 0)
        val status = if (private) privateModeStatus(mode) else ansiModeStatus(mode)
        val prefix = if (private) "?" else ""
        terminal.respond("\u001B[$prefix$mode;$status\$y")
    }

    private fun privateModeStatus(mode: Int): Int {
        val modes = terminal.modes
        return when (mode) {
            1 -> if (modes.applicationCursorKeys) 1 else 2
            5 -> if (modes.reverseVideo) 1 else 2
            6 -> if (modes.origin) 1 else 2
            7 -> if (modes.autoWrap) 1 else 2
            12 -> if (modes.cursorBlink) 1 else 2
            25 -> if (modes.cursorVisible) 1 else 2
            67 -> if (modes.backspaceSendsBs) 1 else 2
            1000, 1002, 1003, 9 -> if (modes.mouseTracking) 1 else 2
            1004 -> if (modes.focusReporting) 1 else 2
            1006 -> if (modes.mouseEncoding == cn.enaium.terminal.core.MouseEncoding.SGR) 1 else 2
            1007 -> if (modes.alternateScroll) 1 else 2
            1049 -> if (terminal.isAlternateScreen) 1 else 2
            2004 -> if (modes.bracketedPaste) 1 else 2
            2026 -> if (modes.synchronizedOutput) 1 else 2
            else -> 0
        }
    }

    private fun ansiModeStatus(mode: Int): Int = when (mode) {
        4 -> if (terminal.modes.insert) 1 else 2
        20 -> if (terminal.modes.newLine) 1 else 2
        else -> 0
    }

    /** XTWINOPS (`CSI Ps ; ... t`). */
    private fun windowOperation() {
        when (paramOr(0, 0)) {
            11 -> terminal.respond("\u001B[1t")
            18 -> terminal.respond("\u001B[8;${terminal.rows};${terminal.columns}t")
            21 -> terminal.respond("\u001B]l${terminal.title ?: ""}\u001B\\")
            else -> Unit
        }
    }

    // =====================================================================
    // String sequences
    // =====================================================================

    private fun startString() {
        stringLength = 0
    }

    private fun appendString(byte: Int) {
        if (stringLength >= MAX_STRING_LENGTH) return
        if (stringLength == stringBytes.size) {
            stringBytes = stringBytes.copyOf(stringBytes.size * 2)
        }
        stringBytes[stringLength++] = byte.toByte()
    }

    private fun dispatchString() {
        if (stringLength == 0) {
            OscHandler.handle(terminal, "")
            return
        }
        val payload = Utf8.decode(stringBytes, 0, stringLength)
        stringLength = 0
        OscHandler.handle(terminal, payload)
    }

    /**
     * DCS payloads are only used for DECRQSS (`DCS $ q <setting> ST`); every
     * other device control string is ignored, which is what applications
     * expect from a terminal that does not implement it.
     */
    private fun dispatchDcs() {
        if (stringLength == 0) return
        val payload = Utf8.decode(stringBytes, 0, stringLength)
        stringLength = 0
        if (!payload.startsWith("\$q")) return
        val setting = payload.removePrefix("\$q")
        when (setting) {
            "r" -> terminal.respond("\u001BP1\$r${terminal.scrollTop + 1};${terminal.scrollBottom + 1}r\u001B\\")
            "m" -> terminal.respond("\u001BP1\$r${currentSgr()}m\u001B\\")
            else -> terminal.respond("\u001BP0\$r$setting\u001B\\")
        }
    }

    /** The SGR parameters describing the terminal's current pen. */
    private fun currentSgr(): String {
        val parts = mutableListOf<String>()
        val attributes = terminal.attributes
        if (attributes.bold) parts += "1"
        if (attributes.dim) parts += "2"
        if (attributes.italic) parts += "3"
        when {
            attributes.curlyUnderline -> parts += "4:3"
            attributes.doubleUnderline -> parts += "4:2"
            attributes.dottedUnderline -> parts += "4:4"
            attributes.dashedUnderline -> parts += "4:5"
            attributes.underline -> parts += "4"
        }
        if (attributes.blink) parts += "5"
        if (attributes.inverse) parts += "7"
        if (attributes.hidden) parts += "8"
        if (attributes.strikethrough) parts += "9"
        parts += sgrColor(terminal.foreground, foreground = true)
        parts += sgrColor(terminal.background, foreground = false)
        val filtered = parts.filter { it.isNotEmpty() }
        return if (filtered.isEmpty()) "0" else filtered.joinToString(";")
    }

    private fun sgrColor(color: TerminalColor, foreground: Boolean): String = when {
        color.isDefault -> if (foreground) "39" else "49"
        color.isIndexed -> "${if (foreground) 38 else 48};5;${color.index}"
        else -> "${if (foreground) 38 else 48};2;${color.red};${color.green};${color.blue}"
    }

    companion object {
        private const val MAX_PARAMS = 64
        private const val MAX_STRING_LENGTH = 16 * 1024
    }
}
