package cn.enaium.terminal.imgui

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiContext
import cn.enaium.imgui.ImGuiMouseButton
import cn.enaium.imgui.ImVec2
import cn.enaium.terminal.core.Terminal
import cn.enaium.terminal.pty.PtyProcess
import cn.enaium.terminal.session.TerminalSession
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Mouse behaviour of the widget, driven through ImGui's own input queue (the
 * same way a backend feeds it): a plain click must not leave a one-cell
 * selection behind, a drag must select, and the next click must clear it.
 */
class ImGuiTerminalInputTest {

    private lateinit var context: ImGuiContext
    private lateinit var fonts: TerminalFonts
    private lateinit var session: TerminalSession
    private lateinit var widget: ImGuiTerminal

    @BeforeTest
    fun setUp() {
        context = ImGui.createContext()
        ImGui.getIO().displaySize = ImVec2(800f, 600f)
        ImGui.getIO().deltaTime = 1f / 60f
        val atlas = ImGui.getIO().fonts
        fonts = installTerminalFonts(atlas, TerminalFontSettings(sizePx = 14f, density = 1f))
        check(atlas.build()) { "font atlas build failed" }
        session = TerminalSession(Terminal(20, 3), RecordingPty())
        widget = ImGuiTerminal(session, fonts)
        // Two warm-up frames: the first lays the window out, the second gives
        // the widget a stable origin to place the synthetic mouse against.
        frame()
        frame()
    }

    @AfterTest
    fun tearDown() {
        session.close()
        ImGui.destroyContext(context)
    }

    @Test
    fun plainClickLeavesNoSelection() {
        widget.terminal.write("hello world")
        click(column = 3, row = 0)

        assertNull(widget.terminal.selection, "a click without a drag selects nothing")
        assertNull(widget.terminal.selectionText())
    }

    @Test
    fun dragSelectsAndReleaseKeepsIt() {
        widget.terminal.write("hello world")
        press(column = 0, row = 0)
        moveTo(column = 4, row = 0)
        release()

        val selection = assertNotNull(widget.terminal.selection, "the drag selected a range")
        assertEquals(0, selection.from.column)
        assertEquals(4, selection.to.column)
        assertEquals("hello", widget.terminal.selectionText())
    }

    @Test
    fun nextClickClearsTheSelection() {
        widget.terminal.write("hello world")
        press(column = 0, row = 0)
        moveTo(column = 4, row = 0)
        release()
        assertNotNull(widget.terminal.selection)

        // Let the double-click window expire, otherwise the click would be
        // taken as the second one and select a word.
        ImGui.getIO().deltaTime = 1f
        frame()

        click(column = 8, row = 0)
        assertNull(widget.terminal.selection, "clicking elsewhere clears the selection")
    }

    @Test
    fun secondClickSelectsTheWord() {
        widget.terminal.write("hello world")
        click(column = 2, row = 0)
        click(column = 2, row = 0)

        val selection = assertNotNull(widget.terminal.selection, "the double click selected a word")
        assertEquals(0, selection.from.column)
        assertEquals(4, selection.to.column)
        assertEquals("hello", widget.terminal.selectionText())
    }

    // ==================== helpers ====================

    private val metrics: TerminalFontMetrics get() = fonts.metrics

    /** The screen position the widget drew at in the last frame. */
    private var origin: ImVec2 = ImVec2(0f, 0f)

    /** Runs one frame with the mouse at cell ([column], [row]) and the left button held down. */
    private fun press(column: Int, row: Int) {
        moveMouseTo(column, row)
        ImGui.getIO().addMouseButtonEvent(ImGuiMouseButton.LEFT, true)
        frame()
    }

    /** Runs one frame with the mouse moved to ([column], [row]) while the button stays down. */
    private fun moveTo(column: Int, row: Int) {
        moveMouseTo(column, row)
        frame()
    }

    /** Runs one frame with the left button released. */
    private fun release() {
        ImGui.getIO().addMouseButtonEvent(ImGuiMouseButton.LEFT, false)
        frame()
    }

    /** Presses and releases in one frame, the way a real click arrives. */
    private fun click(column: Int, row: Int) {
        moveMouseTo(column, row)
        ImGui.getIO().addMouseButtonEvent(ImGuiMouseButton.LEFT, true)
        frame()
        ImGui.getIO().addMouseButtonEvent(ImGuiMouseButton.LEFT, false)
        frame()
    }

    private fun moveMouseTo(column: Int, row: Int) {
        ImGui.getIO().addMousePosEvent(
            origin.x + (column + 0.5f) * metrics.cellWidth,
            origin.y + (row + 0.5f) * metrics.cellHeight,
        )
    }

    private fun frame() {
        ImGui.newFrame()
        // A real host sizes the window (the examples fill the SDL window);
        // hovering and therefore all mouse handling depends on it.
        ImGui.setNextWindowPos(ImVec2(0f, 0f))
        ImGui.setNextWindowSize(ImVec2(400f, 200f))
        ImGui.begin("terminal", null, 0)
        // The widget draws from the window's cursor position; remember it so
        // the synthetic mouse coordinates land on the intended cell.
        origin = ImGui.getCursorScreenPos()
        widget.drawContent(
            ImGui.getWindowDrawList(),
            origin,
            ImVec2(20 * metrics.cellWidth, 3 * metrics.cellHeight),
        )
        ImGui.end()
        ImGui.render()
    }

    /** A pty that swallows input and reports end-of-stream. */
    private class RecordingPty : PtyProcess {
        override val pid: Int get() = 0
        override val devicePath: String get() = ""
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
        override fun write(data: ByteArray, offset: Int, length: Int) = Unit
        override fun resize(columns: Int, rows: Int) = Unit
        override fun isAlive(): Boolean = true
        override fun terminate(force: Boolean) = Unit
        override fun close() = Unit
    }
}
