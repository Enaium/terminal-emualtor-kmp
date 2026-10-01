package cn.enaium.terminal.core

import cn.enaium.terminal.unicode.Graphemes
import cn.enaium.terminal.unicode.Unicode
import kotlin.math.max
import kotlin.math.min

/**
 * A terminal screen: the grid, the cursor, the scroll region, the pen (current
 * colors/attributes), the DEC modes and the view state.
 *
 * The class contains no I/O and no parsing: bytes are turned into calls to the
 * `write*` / `cursor*` / `erase*` operations by `VTParser` (in
 * `terminal-parser`), and the whole state is meant to be mutated from a single
 * thread (the session's pump thread). Renderers read it from the UI thread
 * while holding no lock, which is why every operation is allocation-light and
 * never blocks.
 */
class Terminal(
    columns: Int = 80,
    rows: Int = 24,
    scrollbackLimit: Int = DEFAULT_SCROLLBACK_LIMIT,
) {

    /** The number of columns. */
    var columns: Int = columns.coerceAtLeast(1)
        private set

    /** The number of visible rows. */
    var rows: Int = rows.coerceAtLeast(1)
        private set

    private val ids = LineIds()

    /** The primary screen; it owns the scrollback. */
    val normalBuffer: TerminalBuffer = TerminalBuffer(false, scrollbackLimit, ids)

    /** The alternate screen used by full-screen applications. */
    val alternateBuffer: TerminalBuffer = TerminalBuffer(true, 0, ids)

    /** The screen that is currently displayed. */
    var buffer: TerminalBuffer = normalBuffer
        private set

    /** True while the alternate screen is active. */
    val isAlternateScreen: Boolean get() = buffer.isAlternate

    /** The DEC/xterm mode flags. */
    val modes = TerminalModes()

    /** The cursor row (0 = top of the screen). */
    var cursorRow: Int = 0
        private set

    /** The cursor column (0 = left). */
    var cursorColumn: Int = 0
        private set

    /** The cursor shape and blink state (DECSCUSR). */
    var cursorStyle: TerminalCursorStyle = TerminalCursorStyle.Default

    /** The first row of the scrolling region (DECSTBM). */
    var scrollTop: Int = 0
        private set

    /** The last row of the scrolling region (inclusive). */
    var scrollBottom: Int = rows - 1
        private set

    // ==================== Pen ====================

    /** The current foreground color. */
    var foreground: TerminalColor = TerminalColor.Default
        private set

    /** The current background color. */
    var background: TerminalColor = TerminalColor.Default
        private set

    /** The current underline color (SGR 58), [TerminalColor.Default] when unset. */
    var underlineColor: TerminalColor = TerminalColor.Default
        private set

    /** The current SGR attributes. */
    var attributes: TerminalAttributes = TerminalAttributes.None
        private set

    // ==================== Metadata ====================

    /** The window title (OSC 0/2). */
    var title: String? = null
        private set

    /** The icon name (OSC 0/1). */
    var iconName: String? = null
        private set

    /** The working directory reported by the shell (OSC 7), as a `file://` URI or path. */
    var workingDirectory: String? = null
        private set

    /** The number of BEL characters received. */
    var bellCount: Int = 0
        private set

    private val defaultColorOverrides = HashMap<Int, TerminalColor>()
    private var lastPrintedCodePoint = 0

    /** Invoked when the application rings the bell. */
    var bellHandler: (() -> Unit)? = null

    /** Invoked with the bytes the terminal must send back to the application (DA, DSR, ...). */
    var responseHandler: ((String) -> Unit)? = null

    /** Invoked when the application writes the clipboard (OSC 52). */
    var clipboardHandler: ((text: String, selection: String) -> Unit)? = null

    /** Invoked when the application asks for the clipboard contents (OSC 52 query). */
    var clipboardProvider: (() -> String?)? = null

    /** Bumped whenever the visible state changes. */
    var revision: Long = 0
        private set

    // ==================== View state ====================

    /** How many lines the view is scrolled back from the bottom (0 = live). */
    var scrollOffset: Int = 0
        private set

    /** True while the view shows history instead of the live screen. */
    val isScrolledBack: Boolean get() = scrollOffset > 0

    /** The number of lines currently in the scrollback. */
    val scrollbackSize: Int get() = buffer.scrollback.size

    /** The active selection, or null. */
    var selection: TerminalSelection? = null
        private set

    // ==================== Internals ====================

    private var tabStops = BooleanArray(columns)
    private var g0Charset = TerminalCharset.ASCII
    private var g1Charset = TerminalCharset.ASCII
    private var shiftOut = false
    private val hyperlinkTable = ArrayList<String?>(16).apply { add(null) }
    private var currentHyperlinkId = 0

    private var wrapPending = false
    private var lastCellLineId = -1L
    private var lastCellColumn = -1
    private var pendingZwj = false
    private var lastWasRegionalIndicator = false

    private var savedCursor: SavedCursor? = null

    /** Shell integration marks (OSC 133) keyed by absolute line id. */
    private val shellMarks = LinkedHashMap<Long, MutableList<ShellMark>>()

    init {
        normalBuffer.initialize(this.columns, this.rows)
        alternateBuffer.initialize(this.columns, this.rows)
        resetTabStops()
    }

    // =====================================================================
    // Text output
    // =====================================================================

    /**
     * Writes [text] as printable output at the cursor.
     *
     * Control characters and escape sequences are *not* interpreted: this is
     * the convenience path for tests and demos that want to put text on the
     * screen. Applications feed bytes through `VTParser` instead.
     */
    fun write(text: String) {
        val codepoints = text.toCodePointArray()
        for (i in codepoints.indices) writeCodePoint(codepoints[i])
    }

    /**
     * Writes one code point at the cursor, handling grapheme clusters (combining
     * marks, ZWJ sequences, variation selectors, flags), wide characters and
     * the deferred wrap at the right margin.
     */
    fun writeCodePoint(cp: Int) {
        if (cp == 0) return

        if (cp == Graphemes.ZWJ) {
            if (appendToLastCell(cp)) {
                pendingZwj = true
                lastWasRegionalIndicator = false
                return
            }
        }
        if (pendingZwj) {
            pendingZwj = false
            if (appendToLastCell(cp)) return
        }
        if (Unicode.isGraphemeExtend(cp)) {
            if (appendToLastCell(cp)) return
            // A combining mark with no base to attach to is dropped.
            if (Unicode.cellWidth(cp) == 0) return
        }
        if (Graphemes.isRegionalIndicator(cp)) {
            if (lastWasRegionalIndicator && appendToLastCell(cp)) {
                lastWasRegionalIndicator = false
                return
            }
            lastWasRegionalIndicator = true
        } else {
            lastWasRegionalIndicator = false
        }

        val mapped = translateCharset(cp)
        val width = if (Unicode.cellWidth(mapped) == 2) 2 else 1
        placeCodePoint(mapped, width)
    }

    /** True when the character set translation applies to the current slot. */
    private fun translateCharset(cp: Int): Int = when (activeCharset()) {
        TerminalCharset.DEC_SPECIAL_GRAPHICS -> if (cp in 0x5F..0x7E) DecSpecialGraphics.map(cp) else cp
        TerminalCharset.UK -> if (cp == '#'.code) 0x00A3 else cp
        TerminalCharset.ASCII -> cp
    }

    private fun activeCharset(): TerminalCharset = if (shiftOut) g1Charset else g0Charset

    private fun appendToLastCell(cp: Int): Boolean {
        if (lastCellLineId < 0) return false
        val line = buffer.findLineById(lastCellLineId) ?: return false
        val column = lastCellColumn
        if (column !in 0 until line.columns) return false
        if (line.isCellBlank(column)) return false
        line.appendGrapheme(column, cp)
        if (cp == Graphemes.VARIATION_SELECTOR_16 && line.widthAt(column) == 1 &&
            Unicode.isEmoji(line.codepointAt(column))
        ) {
            widenCell(line, column)
        }
        line.bump()
        bump()
        return true
    }

    /** Widens the (emoji) cell at [column] to two columns, shifting the rest of the line right. */
    private fun widenCell(line: TerminalLine, column: Int) {
        if (column + 1 >= line.columns) return
        val grapheme = line.graphemeAt(column)
        line.shiftRange(column + 1, line.columns, 1)
        line.setCell(
            column + 1,
            TerminalCell.WIDE_CONTINUATION,
            0,
            TerminalColor.toPacked(foreground),
            TerminalColor.toPacked(background),
            attributes.bits,
            currentHyperlinkId,
        )
        line.setCell(
            column,
            line.codepointAt(column),
            2,
            line.foreground[column],
            line.background[column],
            line.attributes[column],
            line.hyperlinks[column],
        )
        line.setGrapheme(column, grapheme)
    }

    private fun placeCodePoint(cp: Int, width: Int) {
        if (wrapPending) {
            wrapPending = false
            if (modes.autoWrap) wrapToNextLine()
        }
        if (width == 2 && cursorColumn >= columns - 1) {
            if (modes.autoWrap) {
                wrapToNextLine()
            } else {
                return
            }
        }
        if (modes.insert) {
            insertChars(width)
        }
        val line = buffer.lines[cursorRow]
        clearWideNeighbours(line, cursorColumn)
        line.setCell(
            cursorColumn,
            cp,
            width,
            TerminalColor.toPacked(foreground),
            TerminalColor.toPacked(background),
            attributes.bits,
            currentHyperlinkId,
        )
        if (width == 2) {
            clearWideNeighbours(line, cursorColumn + 1)
            line.setCell(
                cursorColumn + 1,
                TerminalCell.WIDE_CONTINUATION,
                0,
                TerminalColor.toPacked(foreground),
                TerminalColor.toPacked(background),
                attributes.bits,
                currentHyperlinkId,
            )
        }
        lastCellLineId = line.id
        lastCellColumn = cursorColumn
        lastPrintedCodePoint = cp
        cursorColumn += width
        if (cursorColumn >= columns) {
            cursorColumn = columns - 1
            wrapPending = true
        }
        bump()
    }

    /** Clears the orphaned half of a wide character that [column] cuts through. */
    private fun clearWideNeighbours(line: TerminalLine, column: Int) {
        if (column !in 0 until line.columns) return
        if (line.widthAt(column) == 0 && column > 0) {
            line.setCell(column - 1, 0, 1, 0, 0, 0, 0)
        }
        if (column + 1 < line.columns && line.widthAt(column + 1) == 0) {
            line.setCell(column + 1, 0, 1, 0, 0, 0, 0)
        }
    }

    private fun wrapToNextLine() {
        val line = buffer.lines[cursorRow]
        line.wrapped = true
        line.bump()
        if (cursorRow == scrollBottom) {
            scrollRegionUp(1)
        } else if (cursorRow < rows - 1) {
            cursorRow++
        }
        cursorColumn = 0
        lastCellLineId = -1
        bump()
    }

    // =====================================================================
    // Cursor movement
    // =====================================================================

    fun cursorUp(count: Int = 1) {
        val limit = if (modes.origin) scrollTop else 0
        setCursor(max(limit, cursorRow - count.coerceAtLeast(1)), cursorColumn)
    }

    fun cursorDown(count: Int = 1) {
        val limit = if (modes.origin) scrollBottom else rows - 1
        setCursor(min(limit, cursorRow + count.coerceAtLeast(1)), cursorColumn)
    }

    fun cursorForward(count: Int = 1) {
        setCursor(cursorRow, min(columns - 1, cursorColumn + count.coerceAtLeast(1)))
    }

    fun cursorBackward(count: Int = 1) {
        setCursor(cursorRow, max(0, cursorColumn - count.coerceAtLeast(1)))
    }

    fun cursorNextLine(count: Int = 1) {
        val limit = if (modes.origin) scrollBottom else rows - 1
        setCursor(min(limit, cursorRow + count.coerceAtLeast(1)), 0)
    }

    fun cursorPreviousLine(count: Int = 1) {
        val limit = if (modes.origin) scrollTop else 0
        setCursor(max(limit, cursorRow - count.coerceAtLeast(1)), 0)
    }

    fun cursorToColumn(column: Int) {
        setCursor(cursorRow, column - 1)
    }

    /** CUP/HVP: 1-based row/column, relative to the scroll region in origin mode. */
    fun cursorTo(row: Int, column: Int) {
        val targetRow = if (modes.origin) scrollTop + (row - 1) else row - 1
        setCursor(targetRow, column - 1)
    }

    /** VPA: 1-based row, column unchanged. */
    fun cursorToRow(row: Int) {
        val targetRow = if (modes.origin) scrollTop + (row - 1) else row - 1
        setCursor(targetRow, cursorColumn)
    }

    /** Moves the cursor, clamping to the screen and cancelling a pending wrap. */
    fun setCursor(row: Int, column: Int) {
        val limitTop = if (modes.origin) scrollTop else 0
        val limitBottom = if (modes.origin) scrollBottom else rows - 1
        cursorRow = row.coerceIn(limitTop.coerceAtMost(limitBottom), limitBottom)
        cursorColumn = column.coerceIn(0, columns - 1)
        wrapPending = false
        lastCellLineId = -1
        bump()
    }

    fun lineFeed() {
        wrapPending = false
        lastCellLineId = -1
        if (modes.newLine) cursorColumn = 0
        if (cursorRow == scrollBottom) {
            scrollRegionUp(1)
        } else if (cursorRow < rows - 1) {
            cursorRow++
        }
        bump()
    }

    /** RI: moves up, scrolling the region down at its top. */
    fun reverseLineFeed() {
        wrapPending = false
        lastCellLineId = -1
        if (cursorRow == scrollTop) {
            buffer.scrollDown(scrollTop, scrollBottom, 1)
        } else if (cursorRow > 0) {
            cursorRow--
        }
        bump()
    }

    fun carriageReturn() {
        cursorColumn = 0
        wrapPending = false
        lastCellLineId = -1
        bump()
    }

    fun backspace() {
        if (wrapPending) {
            wrapPending = false
        } else if (cursorColumn > 0) {
            cursorColumn--
        }
        lastCellLineId = -1
        bump()
    }

    /** HT: moves to the next tab stop (or the last column). */
    fun tab() {
        wrapPending = false
        var column = cursorColumn + 1
        while (column < columns - 1 && !tabStops[column]) column++
        cursorColumn = column.coerceAtMost(columns - 1)
        lastCellLineId = -1
        bump()
    }

    /** CBT: moves back [count] tab stops. */
    fun backTab(count: Int = 1) {
        var column = cursorColumn
        repeat(count.coerceAtLeast(1)) {
            var target = column - 1
            while (target > 0 && !tabStops[target]) target--
            column = target.coerceAtLeast(0)
        }
        setCursor(cursorRow, column)
    }

    /** HTS: sets a tab stop at the cursor column. */
    fun setTabStop() {
        tabStops[cursorColumn.coerceIn(0, columns - 1)] = true
    }

    /** TBC: clears the tab stop at the cursor (0) or every tab stop (3). */
    fun clearTabStop(mode: Int) {
        when (mode) {
            0 -> tabStops[cursorColumn.coerceIn(0, columns - 1)] = false
            3 -> tabStops.fill(false)
        }
    }

    private fun resetTabStops() {
        // The stop table follows the column count, so it has to be rebuilt
        // whenever the terminal is resized.
        if (tabStops.size != columns) tabStops = BooleanArray(columns)
        tabStops.fill(false)
        var column = 8
        while (column < columns) {
            tabStops[column] = true
            column += 8
        }
    }

    // =====================================================================
    // Scrolling and editing
    // =====================================================================

    /** SU: scrolls the region up by [count] lines. */
    fun scrollUp(count: Int = 1) {
        scrollRegionUp(count.coerceAtLeast(1))
    }

    /** SD: scrolls the region down by [count] lines. */
    fun scrollDown(count: Int = 1) {
        buffer.scrollDown(scrollTop, scrollBottom, count.coerceAtLeast(1))
        bump()
    }

    private fun scrollRegionUp(count: Int) {
        val intoScrollback = !buffer.isAlternate && scrollTop == 0 && scrollBottom == rows - 1
        buffer.scrollUp(scrollTop, scrollBottom, count, intoScrollback)
        if (intoScrollback && scrollOffset > 0) {
            // Keep the view anchored on the same content while output continues.
            scrollOffset = min(buffer.scrollback.size, scrollOffset + count)
        }
        bump()
    }

    /** IL: inserts [count] blank lines at the cursor row. */
    fun insertLines(count: Int = 1) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        buffer.insertLines(scrollTop, scrollBottom, cursorRow, count.coerceAtLeast(1))
        setCursor(cursorRow, 0)
        bump()
    }

    /** DL: deletes [count] lines at the cursor row. */
    fun deleteLines(count: Int = 1) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        buffer.deleteLines(scrollTop, scrollBottom, cursorRow, count.coerceAtLeast(1))
        setCursor(cursorRow, 0)
        bump()
    }

    /** ICH: inserts [count] blank cells at the cursor. */
    fun insertChars(count: Int = 1) {
        val line = buffer.lines[cursorRow]
        line.shiftRange(cursorColumn, columns, count.coerceAtLeast(1))
        line.fillRange(
            cursorColumn,
            (cursorColumn + count).coerceAtMost(columns),
            TerminalColor.toPacked(background),
            0,
        )
        line.normalizeWideCells()
        bump()
    }

    /** DCH: deletes [count] cells at the cursor. */
    fun deleteChars(count: Int = 1) {
        val line = buffer.lines[cursorRow]
        line.shiftRange(cursorColumn, columns, -count.coerceAtLeast(1))
        line.fillRange(
            (columns - count).coerceAtLeast(0),
            columns,
            TerminalColor.toPacked(background),
            0,
        )
        line.normalizeWideCells()
        bump()
    }

    /** ECH: overwrites [count] cells with blanks. */
    fun eraseChars(count: Int = 1) {
        val line = buffer.lines[cursorRow]
        line.fillRange(
            cursorColumn,
            (cursorColumn + count.coerceAtLeast(1)).coerceAtMost(columns),
            TerminalColor.toPacked(background),
            0,
        )
        bump()
    }

    /** EL: 0 = cursor to end, 1 = start to cursor, 2 = whole line. */
    fun eraseInLine(mode: Int = 0) {
        val line = buffer.lines[cursorRow]
        val backgroundPacked = TerminalColor.toPacked(background)
        when (mode) {
            0 -> line.fillRange(cursorColumn, columns, backgroundPacked, 0)
            1 -> line.fillRange(0, cursorColumn + 1, backgroundPacked, 0)
            2 -> line.fillRange(0, columns, backgroundPacked, 0)
        }
        bump()
    }

    /** ED: 0 = cursor to end, 1 = start to cursor, 2 = screen, 3 = scrollback. */
    fun eraseInDisplay(mode: Int = 0) {
        val backgroundPacked = TerminalColor.toPacked(background)
        when (mode) {
            0 -> {
                buffer.lines[cursorRow].fillRange(cursorColumn, columns, backgroundPacked, 0)
                for (row in cursorRow + 1 until rows) buffer.resetRow(row)
            }

            1 -> {
                buffer.lines[cursorRow].fillRange(0, cursorColumn + 1, backgroundPacked, 0)
                for (row in 0 until cursorRow) buffer.resetRow(row)
            }

            2 -> {
                for (row in 0 until rows) buffer.resetRow(row)
            }

            3 -> buffer.scrollback.clear()
        }
        bump()
    }

    /** DECSTBM: sets the scrolling region; null/0 means "the whole screen". */
    fun setScrollRegion(top: Int?, bottom: Int?) {
        val newTop = (top ?: 1).coerceAtLeast(1) - 1
        val newBottom = (bottom ?: rows).coerceAtMost(rows) - 1
        if (newTop >= newBottom) {
            scrollTop = 0
            scrollBottom = rows - 1
        } else {
            scrollTop = newTop
            scrollBottom = newBottom
        }
        cursorTo(1, 1)
        bump()
    }

    // =====================================================================
    // Pen / SGR
    // =====================================================================

    fun setForeground(color: TerminalColor) {
        foreground = color
    }

    fun setBackground(color: TerminalColor) {
        background = color
    }

    fun setUnderlineColor(color: TerminalColor) {
        underlineColor = color
    }

    fun setAttributes(value: TerminalAttributes) {
        attributes = value
    }

    /** SGR 0: back to the default pen. */
    fun resetPen() {
        foreground = TerminalColor.Default
        background = TerminalColor.Default
        underlineColor = TerminalColor.Default
        attributes = TerminalAttributes.None
    }

    // =====================================================================
    // Modes
    // =====================================================================

    /** CSI h / CSI l (ANSI modes). */
    fun setMode(mode: Int, enabled: Boolean) {
        when (mode) {
            4 -> modes.insert = enabled
            20 -> modes.newLine = enabled
        }
    }

    /** CSI ? h / CSI ? l (DEC private modes). */
    fun setPrivateMode(mode: Int, enabled: Boolean) {
        when (mode) {
            1 -> modes.applicationCursorKeys = enabled
            5 -> modes.reverseVideo = enabled
            6 -> {
                modes.origin = enabled
                cursorTo(1, 1)
            }

            7 -> {
                modes.autoWrap = enabled
                wrapPending = false
            }

            12 -> modes.cursorBlink = enabled
            25 -> modes.cursorVisible = enabled
            67 -> modes.backspaceSendsBs = enabled
            1000 -> modes.mouseReporting = if (enabled) MouseReporting.NORMAL else MouseReporting.NONE
            1002 -> modes.mouseReporting = if (enabled) MouseReporting.BUTTON else MouseReporting.NONE
            1003 -> modes.mouseReporting = if (enabled) MouseReporting.ANY else MouseReporting.NONE
            9 -> modes.mouseReporting = if (enabled) MouseReporting.X10 else MouseReporting.NONE
            1004 -> modes.focusReporting = enabled
            1005 -> if (enabled) modes.mouseEncoding = MouseEncoding.UTF8
            1006 -> if (enabled) modes.mouseEncoding = MouseEncoding.SGR
            1015 -> if (enabled) modes.mouseEncoding = MouseEncoding.URXVT
            1007 -> modes.alternateScroll = enabled
            1047 -> if (enabled) useAlternateScreen(clear = true) else useNormalScreen()
            1048 -> if (enabled) saveCursor() else restoreCursor()
            1049 -> if (enabled) {
                saveCursor()
                useAlternateScreen(clear = true)
            } else {
                useNormalScreen()
                restoreCursor()
            }

            2004 -> modes.bracketedPaste = enabled
            2026 -> modes.synchronizedOutput = enabled
        }
    }

    /** Switches to the alternate screen, optionally clearing it. */
    fun useAlternateScreen(clear: Boolean) {
        if (buffer.isAlternate) {
            if (clear) {
                alternateBuffer.clearAll()
                setCursor(0, 0)
            }
            return
        }
        buffer = alternateBuffer
        if (clear) {
            alternateBuffer.clearAll()
            setCursor(0, 0)
        }
        scrollOffset = 0
        bump()
    }

    /** Switches back to the normal screen. */
    fun useNormalScreen() {
        if (!buffer.isAlternate) return
        buffer = normalBuffer
        scrollOffset = 0
        setCursor(cursorRow.coerceAtMost(rows - 1), cursorColumn.coerceAtMost(columns - 1))
        bump()
    }

    /** DECSC: remembers the cursor, the pen and the character set. */
    fun saveCursor() {
        savedCursor = SavedCursor(
            row = cursorRow,
            column = cursorColumn,
            foreground = foreground,
            background = background,
            underlineColor = underlineColor,
            attributes = attributes,
            origin = modes.origin,
            autoWrap = modes.autoWrap,
            g0 = g0Charset,
            g1 = g1Charset,
            shiftOut = shiftOut,
            hyperlinkId = currentHyperlinkId,
        )
    }

    /** DECRC: restores what [saveCursor] remembered. */
    fun restoreCursor() {
        val saved = savedCursor ?: run {
            setCursor(0, 0)
            return
        }
        modes.origin = saved.origin
        modes.autoWrap = saved.autoWrap
        foreground = saved.foreground
        background = saved.background
        underlineColor = saved.underlineColor
        attributes = saved.attributes
        g0Charset = saved.g0
        g1Charset = saved.g1
        shiftOut = saved.shiftOut
        currentHyperlinkId = saved.hyperlinkId
        setCursor(saved.row, saved.column)
    }

    /** DECSCUSR: sets the cursor shape. */
    fun setCursorShape(param: Int) {
        cursorStyle = TerminalCursorStyle.fromDecscusr(param)
        if (param == 0) cursorStyle = cursorStyle.copy(blink = modes.cursorBlink)
        bump()
    }

    /** ESC ( / ESC ) / ESC * / ESC + : designates a character set. */
    fun designateCharset(slot: Int, charset: TerminalCharset) {
        when (slot) {
            0, 2 -> g0Charset = charset
            1, 3 -> g1Charset = charset
        }
    }

    /** SO (0x0E): selects G1. */
    fun shiftOut() {
        shiftOut = true
    }

    /** SI (0x0F): selects G0. */
    fun shiftIn() {
        shiftOut = false
    }

    // =====================================================================
    // OSC
    // =====================================================================

    fun setTitle(value: String) {
        title = value
        bump()
    }

    fun setIconName(value: String) {
        iconName = value
        bump()
    }

    /** OSC 7: the shell reports its working directory. */
    fun setWorkingDirectory(value: String) {
        workingDirectory = value
        bump()
    }

    /** OSC 8: sets (or clears, with null) the hyperlink attached to the following cells. */
    fun setHyperlink(uri: String?, id: String? = null) {
        if (uri.isNullOrEmpty()) {
            currentHyperlinkId = 0
            return
        }
        val key = if (id.isNullOrEmpty()) uri else "$id\u0000$uri"
        val existing = hyperlinkTable.indexOfFirst { it == key }
        currentHyperlinkId = if (existing >= 0) {
            existing
        } else {
            hyperlinkTable.add(key)
            hyperlinkTable.size - 1
        }
    }

    /** The hyperlink URI behind a cell's [TerminalLine.hyperlinkIdAt] index, or null. */
    fun hyperlinkAt(index: Int): String? {
        if (index <= 0 || index >= hyperlinkTable.size) return null
        val key = hyperlinkTable[index] ?: return null
        val separator = key.indexOf('\u0000')
        return if (separator >= 0) key.substring(separator + 1) else key
    }

    /** OSC 52: the application wrote the clipboard. */
    fun setClipboard(text: String, selection: String) {
        clipboardHandler?.invoke(text, selection)
    }

    /** OSC 52 query: the application asks for the clipboard contents. */
    fun requestClipboard(selection: String): String? = clipboardProvider?.invoke()

    /** Sends [text] back to the application (device responses). */
    fun respond(text: String) {
        responseHandler?.invoke(text)
    }

    // =====================================================================
    // Palette
    // =====================================================================

    /** The 256 color palette used to resolve indexed colors. */
    val palette: TerminalPalette = TerminalPalette()

    /** OSC 4: overrides a palette entry. */
    fun setPaletteColor(index: Int, color: TerminalColor) {
        when {
            color.isRgb -> palette.set(index, color.rgb)
            color.isIndexed -> palette.set(index, palette.rgb(color.index))
        }
        bump()
    }

    /** The packed `0xRRGGBB` value of palette entry [index]. */
    fun paletteRgb(index: Int): Int = palette.rgb(index)

    /** OSC 104: drops palette overrides (all of them when [index] is null). */
    fun resetPalette(index: Int? = null) {
        palette.reset(index)
        bump()
    }

    /** OSC 10/11/12: overrides the default foreground (10), background (11) or cursor (12) color. */
    fun setDefaultColor(slot: Int, color: TerminalColor) {
        defaultColorOverrides[slot] = color
        bump()
    }

    /** The overridden default color of [slot], or null. */
    fun defaultColor(slot: Int): TerminalColor? = defaultColorOverrides[slot]

    /** OSC 110/111/112: drops the override of [slot]. */
    fun resetDefaultColor(slot: Int) {
        defaultColorOverrides.remove(slot)
        bump()
    }

    /** True when the cell at [column] of the current row holds a printable character. */
    fun repeatLastCharacter(count: Int) {
        val cp = lastPrintedCodePoint
        if (cp == 0) return
        repeat(count.coerceIn(1, columns)) { writeCodePoint(cp) }
    }

    /** SGR 58 / 59: resets the underline color. */
    fun resetUnderlineColor() {
        underlineColor = TerminalColor.Default
    }

    /** BEL. */
    fun bell() {
        bellCount++
        bellHandler?.invoke()
    }

    // =====================================================================
    // Shell integration (OSC 133)
    // =====================================================================

    /** Records a shell integration mark at the cursor's line. */
    fun addShellMark(type: ShellMarkType, exitCode: Int = -1) {
        val id = buffer.lines[cursorRow].id
        shellMarks.getOrPut(id) { ArrayList(2) }.add(ShellMark(type, exitCode))
    }

    /** The shell integration marks of the line with [id]. */
    fun shellMarksAt(id: Long): List<ShellMark> = shellMarks[id] ?: emptyList()

    /** Every recorded shell integration mark, oldest line first. */
    fun shellMarks(): List<ShellMark> = shellMarks.values.flatten()

    // =====================================================================
    // Reset
    // =====================================================================

    /** RIS: a full reset. */
    fun reset() {
        modes.reset()
        resetPen()
        cursorStyle = TerminalCursorStyle.Default
        g0Charset = TerminalCharset.ASCII
        g1Charset = TerminalCharset.ASCII
        shiftOut = false
        currentHyperlinkId = 0
        hyperlinkTable.clear()
        hyperlinkTable.add(null)
        scrollTop = 0
        scrollBottom = rows - 1
        cursorRow = 0
        cursorColumn = 0
        wrapPending = false
        lastCellLineId = -1
        pendingZwj = false
        lastWasRegionalIndicator = false
        savedCursor = null
        normalBuffer.clearAll()
        alternateBuffer.clearAll()
        normalBuffer.scrollback.clear()
        buffer = normalBuffer
        scrollOffset = 0
        selection = null
        shellMarks.clear()
        palette.reset()
        defaultColorOverrides.clear()
        lastPrintedCodePoint = 0
        title = null
        iconName = null
        workingDirectory = null
        resetTabStops()
        bump()
    }

    /** DECSTR: a soft reset that keeps the screen contents. */
    fun softReset() {
        modes.reset()
        resetPen()
        cursorStyle = TerminalCursorStyle.Default
        g0Charset = TerminalCharset.ASCII
        g1Charset = TerminalCharset.ASCII
        shiftOut = false
        currentHyperlinkId = 0
        scrollTop = 0
        scrollBottom = rows - 1
        wrapPending = false
        savedCursor = null
        bump()
    }

    // =====================================================================
    // View
    // =====================================================================

    /**
     * The line displayed at screen [row], taking the scrollback offset into
     * account (row 0 is the top of the view).
     */
    fun lineAt(row: Int): TerminalLine {
        val safeRow = row.coerceIn(0, rows - 1)
        if (scrollOffset == 0) return buffer.lines[safeRow]
        val index = buffer.scrollback.size - scrollOffset + safeRow
        return if (index < buffer.scrollback.size) {
            buffer.scrollback.line(index)
        } else {
            buffer.lines[index - buffer.scrollback.size]
        }
    }

    /** The absolute line id displayed at screen [row]. */
    fun lineIdAt(row: Int): Long = lineAt(row).id

    /** The screen row showing the line with [id], or -1 when it is not visible. */
    fun rowOfLineId(id: Long): Int {
        for (row in 0 until rows) {
            if (lineAt(row).id == id) return row
        }
        return -1
    }

    /** Scrolls the view; positive [delta] moves towards older lines. */
    fun scrollView(delta: Int) {
        val maxOffset = buffer.scrollback.size
        val newOffset = (scrollOffset + delta).coerceIn(0, maxOffset)
        if (newOffset != scrollOffset) {
            scrollOffset = newOffset
            bump()
        }
    }

    /** Jumps back to the live screen. */
    fun scrollToBottom() {
        scrollView(-scrollOffset)
    }

    // =====================================================================
    // Selection
    // =====================================================================

    fun setSelection(start: TerminalPosition, end: TerminalPosition, mode: TerminalSelectionMode = TerminalSelectionMode.LINEAR) {
        selection = TerminalSelection(start, end, mode)
        bump()
    }

    fun clearSelection() {
        if (selection != null) {
            selection = null
            bump()
        }
    }

    /**
     * True when the cell at ([lineId], [column]) is inside the selection.
     *
     * The ends are inclusive, so a selection whose start equals its end covers
     * exactly one cell - "no selection" is [selection] being null, not empty.
     */
    fun isSelected(lineId: Long, column: Int): Boolean = selection?.contains(lineId, column) == true

    /** The column range of [lineId] covered by the selection, or null. */
    fun selectedColumns(lineId: Long): IntRange? = selection?.columnRange(lineId)

    /** Selects the word around ([lineId], [column]) (double click). */
    fun selectWord(lineId: Long, column: Int) {
        val line = buffer.findLineById(lineId) ?: return
        val col = column.coerceIn(0, line.columns - 1)
        val kind = wordKind(line.codepointAt(col))
        var from = col
        while (from > 0 && wordKind(line.codepointAt(from - 1)) == kind) from--
        var to = col
        while (to < line.columns - 1 && wordKind(line.codepointAt(to + 1)) == kind) to++
        setSelection(TerminalPosition(lineId, from), TerminalPosition(lineId, to))
    }

    /** Selects the whole line (triple click). */
    fun selectLine(lineId: Long) {
        val line = buffer.findLineById(lineId) ?: return
        setSelection(TerminalPosition(lineId, 0), TerminalPosition(lineId, line.columns - 1))
    }

    /** Selects everything currently retained (scrollback plus screen). */
    fun selectAll() {
        val first = buffer.oldestLineId()
        val last = buffer.lines[rows - 1].id
        setSelection(TerminalPosition(first, 0), TerminalPosition(last, columns - 1))
    }

    /**
     * The text of the current selection, or null when there is none.
     *
     * Soft-wrapped rows are joined without a newline; hard line breaks become
     * `\n`.
     */
    fun selectionText(): String? {
        val current = selection ?: return null
        val from = current.from
        val to = current.to
        val sb = StringBuilder()
        val blockMode = current.mode == TerminalSelectionMode.BLOCK
        var lineId = from.line
        var guard = 0
        while (lineId <= to.line && guard < 100_000) {
            guard++
            val line = buffer.findLineById(lineId)
            if (line != null) {
                val range = current.columnRange(lineId)
                val startColumn = (range?.first ?: 0).coerceIn(0, line.columns - 1)
                val endColumn = (range?.last ?: line.columns - 1).coerceIn(0, line.columns - 1)
                val text = line.text(startColumn, endColumn + 1, trimEnd = !blockMode)
                sb.append(text)
                if (blockMode || !line.wrapped) sb.append('\n')
            }
            lineId++
        }
        return sb.toString().trimEnd('\n')
    }

    private fun wordKind(cp: Int): Int = when {
        cp == 0 || cp == ' '.code -> 0
        cp == '_'.code || cp in 'a'.code..'z'.code || cp in 'A'.code..'Z'.code || cp in '0'.code..'9'.code -> 1
        cp > 0x7F && !Unicode.isWide(cp) -> 1
        cp > 0x7F -> 1
        else -> 2
    }

    // =====================================================================
    // Resize
    // =====================================================================

    /**
     * Resizes the screen. The normal screen reflows (logical lines are
     * re-wrapped), the alternate screen keeps its top-left corner.
     */
    fun resize(newColumns: Int, newRows: Int) {
        val targetColumns = newColumns.coerceAtLeast(1)
        val targetRows = newRows.coerceAtLeast(1)
        if (targetColumns == columns && targetRows == rows) return

        val cursorLineId = buffer.lines[cursorRow].id
        val normalCursor = normalBuffer.reflow(targetColumns, targetRows, cursorLineId, cursorColumn)
        alternateBuffer.resizeSimple(targetColumns, targetRows)
        columns = targetColumns
        rows = targetRows

        if (buffer.isAlternate) {
            cursorRow = cursorRow.coerceIn(0, rows - 1)
            cursorColumn = cursorColumn.coerceIn(0, columns - 1)
        } else if (normalCursor != null) {
            cursorRow = normalCursor[0].coerceIn(0, rows - 1)
            cursorColumn = normalCursor[1].coerceIn(0, columns - 1)
        } else {
            cursorRow = rows - 1
            cursorColumn = cursorColumn.coerceIn(0, columns - 1)
        }

        scrollTop = 0
        scrollBottom = rows - 1
        scrollOffset = scrollOffset.coerceIn(0, buffer.scrollback.size)
        resetTabStops()
        wrapPending = false
        lastCellLineId = -1
        bump()
    }

    /** Changes the scrollback limit of the normal screen. */
    fun setScrollbackLimit(limit: Int) {
        normalBuffer.scrollback.limit = limit
        scrollOffset = scrollOffset.coerceIn(0, buffer.scrollback.size)
    }

    private fun bump() {
        revision++
    }

    private class SavedCursor(
        val row: Int,
        val column: Int,
        val foreground: TerminalColor,
        val background: TerminalColor,
        val underlineColor: TerminalColor,
        val attributes: TerminalAttributes,
        val origin: Boolean,
        val autoWrap: Boolean,
        val g0: TerminalCharset,
        val g1: TerminalCharset,
        val shiftOut: Boolean,
        val hyperlinkId: Int,
    )

    companion object {
        /** The default scrollback size (lines). */
        const val DEFAULT_SCROLLBACK_LIMIT = 10_000
    }
}

/** A shell integration mark recorded by OSC 133. */
data class ShellMark(
    val type: ShellMarkType,
    /** The command's exit code for [ShellMarkType.COMMAND_FINISHED], -1 otherwise. */
    val exitCode: Int,
)

/** The OSC 133 mark types. */
enum class ShellMarkType {
    /** `A`: the shell started drawing its prompt. */
    PROMPT_START,

    /** `B`: the prompt is complete; the user may type. */
    PROMPT_END,

    /** `C`: the command started executing. */
    COMMAND_START,

    /** `D`: the command finished (an exit code may follow). */
    COMMAND_FINISHED,
}
