package cn.enaium.terminal.sdl

import cn.enaium.sdl.SDL
import cn.enaium.sdl.SDLEvent
import cn.enaium.sdl.SDLKeycode
import cn.enaium.sdl.SDLKeymod
import cn.enaium.sdl.SDLMouseButton
import cn.enaium.sdl.SDLMouseButtonMask
import cn.enaium.sdl.SDLRenderer
import cn.enaium.sdl.SDLWindow
import cn.enaium.sdl.SDLWindowEventType
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
 * The SDL terminal widget: renders a [TerminalSession]'s screen and forwards
 * SDL events to the application.
 *
 * ```kotlin
 * val terminal = SdlTerminal(session, window, renderer, SdlTerminalFont(fontPath, 14f))
 * while (running) {
 *     while (true) {
 *         val event = SDL.pollEvent() ?: break
 *         terminal.handleEvent(event)
 *     }
 *     session.pump()
 *     terminal.render()
 *     renderer.present()
 * }
 * ```
 *
 * The grid is laid out in the renderer's *physical* pixel space (the space SDL
 * draws in): the column/row count comes from `renderer.outputSize`, while the
 * mouse coordinates SDL reports are logical points and are scaled by
 * `outputSize / window.size` before they are turned into a cell. Rasterize the
 * font for that scale by passing [pixelScale] as [SdlTerminalFont.density].
 */
class SdlTerminal(
    /** The session whose terminal is displayed. */
    val session: TerminalSession,
    /** The window the events come from and the grid is sized to. */
    val window: SDLWindow,
    /** The renderer the frame is drawn with. */
    val renderer: SDLRenderer,
    /** The font the terminal draws with. */
    val font: SdlTerminalFont,
    theme: SdlTerminalTheme = SdlTerminalTheme.Default,
    /** Widget tuning. */
    val options: Options = Options(),
) : AutoCloseable {

    /** Widget tuning. */
    class Options(
        /** Lines scrolled per mouse wheel notch. */
        val wheelScrollLines: Int = 3,
        /** Half period of the cursor blink in seconds. */
        val blinkPeriodSeconds: Double = 0.53,
        /** Copy the selection to the clipboard as soon as the mouse is released. */
        val copyOnSelect: Boolean = false,
        /** Ctrl+C copies when there is a selection instead of reaching the application. */
        val ctrlCCopiesSelection: Boolean = true,
    )

    /** The terminal state. */
    val terminal: Terminal get() = session.terminal

    /** The renderer (exposed so hosts can swap the theme at runtime). */
    val terminalRenderer: SdlTerminalRenderer = SdlTerminalRenderer(font, renderer, theme)

    /** The number of columns the last frame laid out. */
    var columns: Int = 80
        private set

    /** The number of rows the last frame laid out. */
    var rows: Int = 24
        private set

    /** True while the window has the keyboard focus. */
    var isFocused: Boolean = false
        private set

    private var selecting = false
    private var selectionAnchor: TerminalPosition? = null
    private var selectionMode = TerminalSelectionMode.LINEAR
    private var lastClickTime = 0.0
    private var clickCount = 0

    /** Resizes the terminal to the window and draws one frame. */
    fun render() {
        val cellWidth = font.cellWidth
        val cellHeight = font.cellHeight
        // Layout happens in *physical* pixels because that is the space SDL's
        // renderer draws in: on a Retina display the framebuffer is twice the
        // window size, and drawing logical units would fill only the top-left
        // quarter (the SDL 2D renderer does not apply the DPI scale itself).
        val output = renderer.outputSize
        val targetColumns = max(2, (output.x / cellWidth).toInt())
        val targetRows = max(2, (output.y / cellHeight).toInt())
        if (targetColumns != columns || targetRows != rows) {
            columns = targetColumns
            rows = targetRows
            session.resize(targetColumns, targetRows)
        }
        terminalRenderer.draw(terminal, cursorBlinkOn())
    }

    /**
     * Feeds one SDL event into the terminal, returning true when the event was
     * consumed by the widget.
     */
    fun handleEvent(event: SDLEvent): Boolean = when (event) {
        is SDLEvent.Key -> handleKey(event)
        is SDLEvent.TextInput -> {
            session.sendText(event.text)
            true
        }

        is SDLEvent.MouseMotion -> handleMouseMotion(event)
        is SDLEvent.MouseButton -> handleMouseButton(event)
        is SDLEvent.MouseWheel -> handleWheel(event)
        is SDLEvent.Window -> handleWindow(event)
        else -> false
    }

    /** Copies the current selection, returning true when there was one. */
    fun copySelection(): Boolean {
        val text = terminal.selectionText() ?: return false
        if (text.isEmpty()) return false
        SDL.setClipboardText(text)
        return true
    }

    /** Pastes the clipboard into the terminal. */
    fun pasteClipboard() {
        val text = SDL.getClipboardText() ?: return
        if (text.isEmpty()) return
        session.sendPaste(text)
    }

    /** Releases the font and its atlas. */
    override fun close() {
        font.close()
    }

    // =====================================================================
    // Keyboard
    // =====================================================================

    private fun handleKey(event: SDLEvent.Key): Boolean {
        if (!event.down) return false
        val ctrl = event.modifiers and SDLKeymod.CTRL != 0
        val alt = event.modifiers and SDLKeymod.ALT != 0
        val shift = event.modifiers and SDLKeymod.SHIFT != 0

        if (ctrl && shift && event.keycode == SDLKeycode.C) {
            copySelection()
            return true
        }
        if (ctrl && shift && event.keycode == SDLKeycode.V) {
            pasteClipboard()
            return true
        }
        if (options.ctrlCCopiesSelection && ctrl && !shift &&
            event.keycode == SDLKeycode.C && terminal.selection != null
        ) {
            copySelection()
            terminal.clearSelection()
            return true
        }

        val special = specialKey(event.keycode)
        if (special != null) {
            session.sendKey(special, ctrl, alt, shift)
            return true
        }

        // Plain characters arrive through TextInput; only control/alt chords
        // produce no text event and must be derived from the key code.
        if (ctrl || alt) {
            val character = printableKey(event.keycode)
            if (character != null) {
                session.sendKey(TerminalKey.Character(character), ctrl, alt, shift)
                return true
            }
        }
        return false
    }

    private fun printableKey(keycode: Int): Char? = when {
        keycode in SDLKeycode.A..SDLKeycode.Z -> 'a' + (keycode - SDLKeycode.A)
        keycode in SDLKeycode.KEY_0_START..SDLKeycode.KEY_0_END -> '0' + (keycode - SDLKeycode.KEY_0_START)
        keycode == SDLKeycode.SPACE -> ' '
        else -> null
    }

    // =====================================================================
    // Mouse
    // =====================================================================

    private fun handleMouseMotion(event: SDLEvent.MouseMotion): Boolean {
        val cell = cellAt(event.x, event.y)
        val modifiers = modifiers()
        val tracking = terminal.modes.mouseTracking && !modifiers.shift
        if (tracking) {
            if (SDL.mouseState.buttons and SDLMouseButtonMask.LEFT != 0) {
                session.sendMouse(
                    TerminalMouseButton.LEFT,
                    TerminalMouseAction.MOVE,
                    cell.column,
                    cell.row,
                    modifiers.ctrl,
                    modifiers.alt,
                    modifiers.shift,
                )
                return true
            }
            return false
        }
        val anchor = selectionAnchor
        if (selecting && anchor != null) {
            val position = TerminalPosition(terminal.lineIdAt(cell.row), cell.column)
            if (position != anchor) {
                terminal.setSelection(anchor, position, selectionMode)
            }
            return true
        }
        return false
    }

    private fun handleMouseButton(event: SDLEvent.MouseButton): Boolean {
        val cell = cellAt(event.x, event.y)
        val modifiers = modifiers()
        // Shift bypasses mouse reporting, which is how a user selects text in
        // applications that grab the mouse (vim, tmux, htop).
        val tracking = terminal.modes.mouseTracking && !modifiers.shift
        val button = mouseButton(event.button)

        if (tracking && button != null) {
            session.sendMouse(
                button,
                if (event.down) TerminalMouseAction.PRESS else TerminalMouseAction.RELEASE,
                cell.column,
                cell.row,
                modifiers.ctrl,
                modifiers.alt,
                modifiers.shift,
            )
            return true
        }

        if (event.button == SDLMouseButton.LEFT) {
            if (event.down) {
                val clicks = if (event.clicks > 0) event.clicks else registerClick()
                val lineId = terminal.lineIdAt(cell.row)
                when (clicks) {
                    2 -> {
                        terminal.selectWord(lineId, cell.column)
                        selectionAnchor = null
                        selecting = false
                    }

                    3 -> {
                        terminal.selectLine(lineId)
                        selectionAnchor = null
                        selecting = false
                    }

                    else -> {
                        terminal.clearSelection()
                        val position = TerminalPosition(lineId, cell.column)
                        selectionAnchor = position
                        selectionMode = if (modifiers.alt) TerminalSelectionMode.BLOCK else TerminalSelectionMode.LINEAR
                        // The highlight only appears once the pointer moves:
                        // a plain click must not leave a one-cell selection.
                        selecting = true
                    }
                }
            } else {
                selecting = false
                if (options.copyOnSelect) copySelection()
            }
            return true
        }

        if (event.down && event.button == SDLMouseButton.MIDDLE) {
            pasteClipboard()
            return true
        }
        if (event.down && event.button == SDLMouseButton.RIGHT) {
            if (terminal.selection != null) copySelection() else pasteClipboard()
            return true
        }
        return false
    }

    private fun handleWheel(event: SDLEvent.MouseWheel): Boolean {
        val delta = event.y
        if (delta == 0f) return false
        val cell = cellAt(event.x, event.y)
        val modifiers = modifiers()
        val lines = (delta * options.wheelScrollLines).toInt()
            .let { if (it == 0) (if (delta > 0) 1 else -1) else it }
        if (terminal.modes.mouseTracking && !modifiers.shift) {
            repeat(abs(lines)) {
                session.sendWheel(
                    up = delta > 0,
                    column = cell.column,
                    row = cell.row,
                    ctrl = modifiers.ctrl,
                    alt = modifiers.alt,
                    shift = modifiers.shift,
                )
            }
        } else if (terminal.isAlternateScreen && terminal.modes.alternateScroll) {
            // `?1007` (alternate scroll, on by default): full-screen
            // applications that did not enable mouse reporting still expect the
            // wheel as cursor keys - that is how `less` and `vim` scroll.
            val key = if (delta > 0) TerminalKey.Up else TerminalKey.Down
            repeat(abs(lines)) { session.sendKey(key) }
        } else {
            terminal.scrollView(lines)
        }
        return true
    }

    private fun mouseButton(button: Int): TerminalMouseButton? = when (button) {
        SDLMouseButton.LEFT -> TerminalMouseButton.LEFT
        SDLMouseButton.MIDDLE -> TerminalMouseButton.MIDDLE
        SDLMouseButton.RIGHT -> TerminalMouseButton.RIGHT
        SDLMouseButton.X1 -> TerminalMouseButton.BUTTON_8
        SDLMouseButton.X2 -> TerminalMouseButton.BUTTON_9
        else -> null
    }

    private fun registerClick(): Int {
        val now = SDL.getTicks().toDouble() / 1000.0
        clickCount = if (now - lastClickTime < 0.5) clickCount + 1 else 1
        if (clickCount > 3) clickCount = 1
        lastClickTime = now
        return clickCount
    }

    // =====================================================================
    // Window
    // =====================================================================

    private fun handleWindow(event: SDLEvent.Window): Boolean = when (event.type) {
        SDLWindowEventType.FOCUS_GAINED -> {
            isFocused = true
            session.sendFocus(true)
            true
        }

        SDLWindowEventType.FOCUS_LOST -> {
            isFocused = false
            session.sendFocus(false)
            true
        }

        else -> false
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private fun cellAt(x: Float, y: Float): Cell {
        // SDL mouse coordinates are logical window points; the grid is laid out
        // in framebuffer pixels, so the point has to be scaled up first.
        val scale = outputScale()
        val column = (x * scale / font.cellWidth).toInt().coerceIn(0, max(0, terminal.columns - 1))
        val row = (y * scale / font.cellHeight).toInt().coerceIn(0, max(0, terminal.rows - 1))
        return Cell(column, row)
    }

    /** Physical pixels the renderer draws per logical window point. */
    private fun outputScale(): Float {
        val logical = window.size
        if (logical.x <= 0 || logical.y <= 0) return 1f
        return (renderer.outputSize.x.toFloat() / logical.x).coerceAtLeast(0.01f)
    }

    private fun modifiers(): Modifiers {
        val state = SDL.modState
        return Modifiers(
            ctrl = state and SDLKeymod.CTRL != 0,
            alt = state and SDLKeymod.ALT != 0,
            shift = state and SDLKeymod.SHIFT != 0,
        )
    }

    private fun cursorBlinkOn(): Boolean {
        if (!terminal.modes.cursorBlink || !terminal.cursorStyle.blink) return true
        val halfPeriod = options.blinkPeriodSeconds
        val now = SDL.getTicks().toDouble() / 1000.0
        return (now % (halfPeriod * 2)) < halfPeriod
    }

    private fun specialKey(keycode: Int): TerminalKey? = when (keycode) {
        SDLKeycode.RETURN, SDLKeycode.KP_ENTER -> TerminalKey.Enter
        SDLKeycode.BACKSPACE -> TerminalKey.Backspace
        SDLKeycode.TAB -> TerminalKey.Tab
        SDLKeycode.ESCAPE -> TerminalKey.Escape
        SDLKeycode.DELETE -> TerminalKey.Delete
        SDLKeycode.INSERT -> TerminalKey.Insert
        SDLKeycode.HOME -> TerminalKey.Home
        SDLKeycode.END -> TerminalKey.End
        SDLKeycode.PAGEUP -> TerminalKey.PageUp
        SDLKeycode.PAGEDOWN -> TerminalKey.PageDown
        SDLKeycode.UP -> TerminalKey.Up
        SDLKeycode.DOWN -> TerminalKey.Down
        SDLKeycode.LEFT -> TerminalKey.Left
        SDLKeycode.RIGHT -> TerminalKey.Right
        in SDLKeycode.F1..(SDLKeycode.F1 + 11) -> TerminalKey.Function(keycode - SDLKeycode.F1 + 1)
        else -> null
    }

    private class Cell(val column: Int, val row: Int)

    private class Modifiers(val ctrl: Boolean, val alt: Boolean, val shift: Boolean)
}
