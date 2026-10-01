package cn.enaium.terminal.imgui

import cn.enaium.imgui.ImDrawList
import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiKey
import cn.enaium.imgui.ImGuiMouseButton
import cn.enaium.imgui.ImVec2
import cn.enaium.terminal.core.Terminal
import cn.enaium.terminal.core.TerminalKey
import cn.enaium.terminal.core.TerminalMouseAction
import cn.enaium.terminal.core.TerminalMouseButton
import cn.enaium.terminal.core.TerminalPosition
import cn.enaium.terminal.core.TerminalSelectionMode
import cn.enaium.terminal.session.TerminalSession
import kotlin.math.abs
import kotlin.math.max

/**
 * The Dear ImGui terminal widget: draws a [TerminalSession]'s screen and
 * forwards keyboard, mouse and clipboard input to the application.
 *
 * ```kotlin
 * val terminal = ImGuiTerminal(session, fonts)
 * // every frame, after ImGui.newFrame():
 * terminal.drawWindow("Terminal")
 * ```
 *
 * Two input channels come from the host rather than from ImGui's own queues
 * (the bindings do not expose `io.InputQueueCharacters` / `io.MouseWheel`):
 * [textInput] for typed characters and [handleWheel] for the mouse wheel.
 * Both should be forwarded from the platform event loop; see the examples.
 */
class ImGuiTerminal(
    /** The session whose terminal is displayed. */
    val session: TerminalSession,
    /** The font faces the terminal draws with. */
    val fonts: TerminalFonts,
    /** The colors the terminal draws with. */
    theme: TerminalTheme = TerminalTheme.Default,
    val options: Options = Options(),
) {

    /** Widget tuning. */
    class Options(
        /** Lines scrolled per mouse wheel notch. */
        val wheelScrollLines: Int = 3,
        /** Half period of the cursor blink in seconds. */
        val blinkPeriodSeconds: Double = 0.53,
        /** Copy the selection to the clipboard as soon as the mouse is released. */
        val copyOnSelect: Boolean = false,
        /** Ctrl+Shift+C / Ctrl+Shift+V are always active; Ctrl+C copies when there is a selection. */
        val ctrlCCopiesSelection: Boolean = true,
    )

    /** The terminal state. */
    val terminal: Terminal get() = session.terminal

    /** The renderer (exposed so hosts can swap the theme at runtime). */
    val renderer: TerminalRenderer = TerminalRenderer(fonts, theme)

    /** True when the terminal window/child has the keyboard focus. */
    var isFocused: Boolean = false
        private set

    /** True when the terminal should receive text events from the host. */
    val wantsTextInput: Boolean get() = isFocused

    /** The number of columns the last frame laid out. */
    var columns: Int = 80
        private set

    /** The number of rows the last frame laid out. */
    var rows: Int = 24
        private set

    private var origin: ImVec2 = ImVec2(0f, 0f)
    private var hovered: Boolean = false
    private var selecting: Boolean = false
    private var selectionAnchor: TerminalPosition? = null
    private var lastClickTime: Double = 0.0
    private var clickCount: Int = 0

    /**
     * Draws a window that fills its content with the terminal. The caller
     * controls size and position through the usual `setNextWindow*` calls.
     */
    fun drawWindow(title: String, flags: Int = 0) {
        if (ImGui.begin(title, flags = flags)) {
            val drawList = ImGui.getWindowDrawList()
            val start = ImGui.getCursorScreenPos()
            val available = ImGui.getContentRegionAvail()
            drawContent(drawList, start, available)
        }
        ImGui.end()
    }

    /** Draws the terminal into a child region of the current window. */
    fun drawChild(id: String, size: ImVec2 = ImVec2(0f, 0f), flags: Int = 0) {
        if (ImGui.beginChild(id, size, windowFlags = flags)) {
            val drawList = ImGui.getWindowDrawList()
            val start = ImGui.getCursorScreenPos()
            val available = ImGui.getContentRegionAvail()
            drawContent(drawList, start, available)
        }
        ImGui.endChild()
    }

    /**
     * Draws the terminal at [start] inside the current window and handles the
     * input that belongs to that rectangle.
     */
    fun drawContent(drawList: ImDrawList, start: ImVec2, available: ImVec2) {
        val metrics = fonts.metrics
        val targetColumns = max(2, (available.x / metrics.cellWidth).toInt())
        val targetRows = max(2, (available.y / metrics.cellHeight).toInt())
        if (targetColumns != columns || targetRows != rows) {
            columns = targetColumns
            rows = targetRows
            session.resize(targetColumns, targetRows)
        }

        origin = start
        renderer.draw(drawList, start, terminal, cursorBlinkOn())

        // Reserve the drawn area in the ImGui layout: without an item the
        // window would collapse to zero size, which also breaks hovering.
        ImGui.dummy(ImVec2(columns * metrics.cellWidth, rows * metrics.cellHeight))

        isFocused = ImGui.isWindowFocused()
        hovered = ImGui.isWindowHovered()
        if (isFocused) handleKeys()
        if (hovered) handleMouse(metrics) else if (!selecting) selectionAnchor = null
    }

    /**
     * Forwards typed text (including IME results) from the host event loop.
     * Ignored while the terminal does not have the focus.
     */
    fun textInput(text: String) {
        if (!isFocused || text.isEmpty()) return
        session.sendText(text)
    }

    /**
     * Forwards a mouse wheel event from the host event loop.
     *
     * When the application tracks the mouse the wheel is reported to it (that
     * is how `less`, `vim` and `tmux` scroll), otherwise the terminal scrolls
     * its own scrollback.
     */
    fun handleWheel(delta: Float) {
        if (delta == 0f) return
        val metrics = fonts.metrics
        val lines = (delta * options.wheelScrollLines).toInt().let { if (it == 0) (if (delta > 0) 1 else -1) else it }
        val mouse = ImGui.getMousePos()
        val column = ((mouse.x - origin.x) / metrics.cellWidth).toInt().coerceIn(0, max(0, terminal.columns - 1))
        val row = ((mouse.y - origin.y) / metrics.cellHeight).toInt().coerceIn(0, max(0, terminal.rows - 1))
        val shift = ImGui.isKeyDown(ImGuiKey.MOD_SHIFT)
        val alt = ImGui.isKeyDown(ImGuiKey.MOD_ALT)
        val ctrl = ImGui.isKeyDown(ImGuiKey.MOD_CTRL)
        if (terminal.modes.mouseTracking && !shift) {
            repeat(abs(lines)) {
                session.sendWheel(up = delta > 0, column = column, row = row, ctrl = ctrl, alt = alt, shift = shift)
            }
            return
        }
        if (terminal.isAlternateScreen && terminal.modes.alternateScroll) {
            // `?1007` (alternate scroll, on by default): full-screen
            // applications that did not enable mouse reporting still expect
            // the wheel as cursor keys - that is how `less` and `vim` scroll.
            val key = if (delta > 0) TerminalKey.Up else TerminalKey.Down
            repeat(abs(lines)) { session.sendKey(key) }
            return
        }
        terminal.scrollView(lines)
    }

    /** Copies the current selection, returning true when there was one. */
    fun copySelection(): Boolean {
        val text = terminal.selectionText() ?: return false
        if (text.isEmpty()) return false
        ImGui.setClipboardText(text)
        return true
    }

    /** Pastes the clipboard into the terminal. */
    fun pasteClipboard() {
        val text = ImGui.getClipboardText() ?: return
        if (text.isEmpty()) return
        session.sendPaste(text)
    }

    private fun cursorBlinkOn(): Boolean {
        if (!terminal.modes.cursorBlink || !terminal.cursorStyle.blink) return true
        val halfPeriod = options.blinkPeriodSeconds
        return (ImGui.getTime() % (halfPeriod * 2)) < halfPeriod
    }

    // =====================================================================
    // Keyboard
    // =====================================================================

    private fun handleKeys() {
        val ctrl = ImGui.isKeyDown(ImGuiKey.MOD_CTRL)
        val alt = ImGui.isKeyDown(ImGuiKey.MOD_ALT)
        val shift = ImGui.isKeyDown(ImGuiKey.MOD_SHIFT)
        if (handleClipboardShortcuts(ctrl, shift)) return

        for (index in NAVIGATION_KEYS.indices) {
            val (imguiKey, terminalKey) = NAVIGATION_KEYS[index]
            if (ImGui.isKeyPressed(imguiKey, repeat = true)) {
                session.sendKey(terminalKey, ctrl, alt, shift)
            }
        }
        if (ImGui.isKeyPressed(ImGuiKey.BACKSPACE, repeat = true)) session.sendKey(TerminalKey.Backspace, ctrl, alt, shift)
        if (ImGui.isKeyPressed(ImGuiKey.DELETE, repeat = true)) session.sendKey(TerminalKey.Delete, ctrl, alt, shift)
        if (ImGui.isKeyPressed(ImGuiKey.ENTER) || ImGui.isKeyPressed(ImGuiKey.KEYPAD_ENTER)) {
            session.sendKey(TerminalKey.Enter, ctrl, alt, shift)
        }
        if (ImGui.isKeyPressed(ImGuiKey.TAB)) session.sendKey(TerminalKey.Tab, ctrl, alt, shift)
        if (ImGui.isKeyPressed(ImGuiKey.ESCAPE)) session.sendKey(TerminalKey.Escape, ctrl, alt, shift)
        for (number in 1..12) {
            if (ImGui.isKeyPressed(ImGuiKey.F1 + number - 1)) {
                session.sendKey(TerminalKey.Function(number), ctrl, alt, shift)
            }
        }

        // Control combinations do not produce text events, so they are derived
        // from the key state; plain characters arrive through textInput().
        if (ctrl || alt) {
            for (letter in 0..25) {
                if (ImGui.isKeyPressed(ImGuiKey.A + letter, repeat = true)) {
                    session.sendKey(TerminalKey.Character(('a' + letter)), ctrl, alt, shift)
                }
            }
            for (digit in 0..9) {
                if (ImGui.isKeyPressed(ImGuiKey.KEY_0 + digit, repeat = true)) {
                    session.sendKey(TerminalKey.Character(('0' + digit)), ctrl, alt, shift)
                }
            }
            if (ImGui.isKeyPressed(ImGuiKey.SPACE, repeat = true)) {
                session.sendKey(TerminalKey.Character(' '), ctrl, alt, shift)
            }
        }
    }

    /** Returns true when a clipboard shortcut consumed the frame's keys. */
    private fun handleClipboardShortcuts(ctrl: Boolean, shift: Boolean): Boolean {
        if (!ctrl) return false
        if (shift && ImGui.isKeyPressed(ImGuiKey.C)) {
            copySelection()
            return true
        }
        if (shift && ImGui.isKeyPressed(ImGuiKey.V)) {
            pasteClipboard()
            return true
        }
        if (options.ctrlCCopiesSelection && !shift && terminal.selection != null && ImGui.isKeyPressed(ImGuiKey.C)) {
            copySelection()
            terminal.clearSelection()
            return true
        }
        return false
    }

    // =====================================================================
    // Mouse
    // =====================================================================

    private fun handleMouse(metrics: TerminalFontMetrics) {
        val mouse = ImGui.getMousePos()
        val column = ((mouse.x - origin.x) / metrics.cellWidth).toInt().coerceIn(0, max(0, terminal.columns - 1))
        val row = ((mouse.y - origin.y) / metrics.cellHeight).toInt().coerceIn(0, max(0, terminal.rows - 1))
        val ctrl = ImGui.isKeyDown(ImGuiKey.MOD_CTRL)
        val alt = ImGui.isKeyDown(ImGuiKey.MOD_ALT)
        val shift = ImGui.isKeyDown(ImGuiKey.MOD_SHIFT)
        // Shift bypasses mouse reporting, which is how a user selects text in
        // applications that grab the mouse (vim, tmux, htop).
        val tracking = terminal.modes.mouseTracking && !shift

        if (ImGui.isMouseClicked(ImGuiMouseButton.LEFT)) {
            if (tracking) {
                session.sendMouse(TerminalMouseButton.LEFT, TerminalMouseAction.PRESS, column, row, ctrl, alt, shift)
            } else {
                registerClick()
                val position = TerminalPosition(terminal.lineIdAt(row), column)
                when (clickCount) {
                    2 -> {
                        terminal.selectWord(position.line, column)
                        selectionAnchor = null
                    }

                    3 -> {
                        terminal.selectLine(position.line)
                        selectionAnchor = null
                    }

                    else -> {
                        // A plain click only *arms* a selection: the highlight
                        // appears once the pointer actually moves, so clicking
                        // never leaves a one-cell selection behind.
                        terminal.clearSelection()
                        selectionAnchor = position
                        selecting = true
                    }
                }
            }
        }

        if (ImGui.isMouseReleased(ImGuiMouseButton.LEFT)) {
            if (tracking) {
                session.sendMouse(TerminalMouseButton.LEFT, TerminalMouseAction.RELEASE, column, row, ctrl, alt, shift)
            } else {
                selecting = false
                if (options.copyOnSelect) copySelection()
            }
        }

        if (selecting && ImGui.isMouseDown(ImGuiMouseButton.LEFT)) {
            val anchor = selectionAnchor
            if (anchor != null) {
                val position = TerminalPosition(terminal.lineIdAt(row), column)
                if (position != anchor) {
                    terminal.setSelection(anchor, position, selectionMode(alt))
                }
            }
        }

        if (tracking && ImGui.isMouseDragging(ImGuiMouseButton.LEFT)) {
            session.sendMouse(TerminalMouseButton.LEFT, TerminalMouseAction.MOVE, column, row, ctrl, alt, shift)
        }

        if (ImGui.isMouseClicked(ImGuiMouseButton.MIDDLE)) {
            if (tracking) {
                session.sendMouse(TerminalMouseButton.MIDDLE, TerminalMouseAction.PRESS, column, row, ctrl, alt, shift)
            } else {
                pasteClipboard()
            }
        }
        if (ImGui.isMouseReleased(ImGuiMouseButton.MIDDLE) && tracking) {
            session.sendMouse(TerminalMouseButton.MIDDLE, TerminalMouseAction.RELEASE, column, row, ctrl, alt, shift)
        }

        if (ImGui.isMouseClicked(ImGuiMouseButton.RIGHT)) {
            if (tracking) {
                session.sendMouse(TerminalMouseButton.RIGHT, TerminalMouseAction.PRESS, column, row, ctrl, alt, shift)
            } else if (terminal.selection != null) {
                copySelection()
            } else {
                pasteClipboard()
            }
        }
        if (ImGui.isMouseReleased(ImGuiMouseButton.RIGHT) && tracking) {
            session.sendMouse(TerminalMouseButton.RIGHT, TerminalMouseAction.RELEASE, column, row, ctrl, alt, shift)
        }
    }

    private fun selectionMode(alt: Boolean): TerminalSelectionMode =
        if (alt) TerminalSelectionMode.BLOCK else TerminalSelectionMode.LINEAR

    /** Tracks double and triple clicks (within half a second of each other). */
    private fun registerClick() {
        val now = ImGui.getTime()
        clickCount = if (now - lastClickTime < 0.5) clickCount + 1 else 1
        if (clickCount > 3) clickCount = 1
        lastClickTime = now
    }

    private companion object {
        val NAVIGATION_KEYS: List<Pair<Int, TerminalKey>> = listOf(
            ImGuiKey.UP_ARROW to TerminalKey.Up,
            ImGuiKey.DOWN_ARROW to TerminalKey.Down,
            ImGuiKey.LEFT_ARROW to TerminalKey.Left,
            ImGuiKey.RIGHT_ARROW to TerminalKey.Right,
            ImGuiKey.HOME to TerminalKey.Home,
            ImGuiKey.END to TerminalKey.End,
            ImGuiKey.PAGE_UP to TerminalKey.PageUp,
            ImGuiKey.PAGE_DOWN to TerminalKey.PageDown,
            ImGuiKey.INSERT to TerminalKey.Insert,
        )
    }
}
