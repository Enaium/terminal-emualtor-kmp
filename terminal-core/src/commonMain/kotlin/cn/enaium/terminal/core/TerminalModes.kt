package cn.enaium.terminal.core

/** Mouse tracking level requested by the application (DECSET 9/1000/1002/1003). */
enum class MouseReporting {
    /** No mouse tracking; the host uses the mouse for selection. */
    NONE,

    /** X10 compatibility: button presses only. */
    X10,

    /** VT200: presses and releases. */
    NORMAL,

    /** Button-event tracking: presses, releases and motion while a button is held. */
    BUTTON,

    /** Any-event tracking: all motion is reported. */
    ANY,
}

/** Wire format used for mouse reports (DECSET 1005/1006/1015). */
enum class MouseEncoding {
    /** `ESC [ M` with three bytes offset by 32. */
    X10,

    /** X10 with UTF-8 encoded coordinates. */
    UTF8,

    /** `ESC [ < b ; x ; y M/m` (SGR). */
    SGR,

    /** `ESC [ b ; x ; y M` (urxvt). */
    URXVT,
}

/**
 * The DEC/xterm mode flags of a [Terminal].
 *
 * These are set through `CSI ? h/l` (DEC private modes), `CSI h/l` (ANSI
 * modes) and the ESC-level sequences (`ESC =`, `ESC >`); the terminal core
 * keeps them here so renderers and input encoders can consult them.
 */
class TerminalModes {

    /** IRM (`CSI 4 h`): typed characters shift the rest of the line right. */
    var insert: Boolean = false

    /** LNM (`CSI 20 h`): a line feed also performs a carriage return. */
    var newLine: Boolean = false

    /** DECAWM (`?7`): characters wrap at the right margin. */
    var autoWrap: Boolean = true

    /** DECOM (`?6`): cursor addressing is relative to the scroll region. */
    var origin: Boolean = false

    /** DECSCNM (`?5`): the whole screen is drawn with swapped colors. */
    var reverseVideo: Boolean = false

    /** DECTCEM (`?25`): the cursor is shown. */
    var cursorVisible: Boolean = true

    /** ATT610 (`?12`): the cursor blinks. */
    var cursorBlink: Boolean = true

    /** DECCKM (`?1`): the cursor keys send application-mode sequences (`ESC O A`). */
    var applicationCursorKeys: Boolean = false

    /** DECKPAM (`ESC =`): the keypad sends application-mode sequences. */
    var applicationKeypad: Boolean = false

    /** DECBKM (`?67`): backspace sends `BS` (0x08) instead of `DEL` (0x7F). */
    var backspaceSendsBs: Boolean = false

    /** `?1004`: the application is told when the window gains or loses focus. */
    var focusReporting: Boolean = false

    /** `?1007`: the mouse wheel scrolls the alternate screen's application. */
    var alternateScroll: Boolean = true

    /** `?2004`: pasted text is wrapped in `ESC [ 200 ~` / `ESC [ 201 ~`. */
    var bracketedPaste: Boolean = false

    /** `?2026`: the application redraws atomically; renderers may skip frames. */
    var synchronizedOutput: Boolean = false

    /** The active mouse tracking level. */
    var mouseReporting: MouseReporting = MouseReporting.NONE

    /** The wire format of mouse reports. */
    var mouseEncoding: MouseEncoding = MouseEncoding.X10

    /** True when the application wants mouse reports at all. */
    val mouseTracking: Boolean get() = mouseReporting != MouseReporting.NONE

    internal fun reset() {
        insert = false
        newLine = false
        autoWrap = true
        origin = false
        reverseVideo = false
        cursorVisible = true
        cursorBlink = true
        applicationCursorKeys = false
        applicationKeypad = false
        backspaceSendsBs = false
        focusReporting = false
        alternateScroll = true
        bracketedPaste = false
        synchronizedOutput = false
        mouseReporting = MouseReporting.NONE
        mouseEncoding = MouseEncoding.X10
    }
}

/** Character sets that can be designated into G0/G1 (`ESC ( 0`, `ESC ) B`, ...). */
enum class TerminalCharset {
    ASCII,
    /** DEC Special Graphics: line drawing characters. */
    DEC_SPECIAL_GRAPHICS,
    /** UK national replacement set (`#` renders as `£`). */
    UK,
}

/** Maps DEC Special Graphics code points to their Unicode equivalents. */
internal object DecSpecialGraphics {

    /** Returns the Unicode code point that [cp] draws as, or [cp] itself. */
    fun map(cp: Int): Int = when (cp) {
        0x5F -> 0x00A0 // blank
        0x60 -> 0x25C6 // diamond
        0x61 -> 0x2592 // checker board
        0x62 -> 0x2409 // HT
        0x63 -> 0x240C // FF
        0x64 -> 0x240D // CR
        0x65 -> 0x240A // LF
        0x66 -> 0x00B0 // degree
        0x67 -> 0x00B1 // plus/minus
        0x68 -> 0x2424 // NL
        0x69 -> 0x240B // VT
        0x6A -> 0x2518 // lower right corner
        0x6B -> 0x2510 // upper right corner
        0x6C -> 0x250C // upper left corner
        0x6D -> 0x2514 // lower left corner
        0x6E -> 0x253C // crossing lines
        0x6F -> 0x23BA // horizontal line scan 1
        0x70 -> 0x23BB // horizontal line scan 3
        0x71 -> 0x2500 // horizontal line scan 5
        0x72 -> 0x23BC // horizontal line scan 7
        0x73 -> 0x23BD // horizontal line scan 9
        0x74 -> 0x251C // left tee
        0x75 -> 0x2524 // right tee
        0x76 -> 0x2534 // bottom tee
        0x77 -> 0x252C // top tee
        0x78 -> 0x2502 // vertical line
        0x79 -> 0x2264 // less than or equal
        0x7A -> 0x2265 // greater than or equal
        0x7B -> 0x03C0 // pi
        0x7C -> 0x2260 // not equal
        0x7D -> 0x00A3 // pound sterling
        0x7E -> 0x00B7 // middle dot
        else -> cp
    }
}
