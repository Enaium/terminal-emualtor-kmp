package cn.enaium.terminal.imgui

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiContext
import cn.enaium.imgui.ImVec2
import cn.enaium.terminal.core.Terminal
import cn.enaium.terminal.pty.PtyProcess
import cn.enaium.terminal.session.TerminalSession
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Resizing the host window resizes the session and re-wraps the screen: the
 * widget derives the grid from the space it is drawn into, and the terminal
 * must keep the content intact while it does.
 */
class ImGuiTerminalResizeTest {

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
        session = TerminalSession(Terminal(20, 5), RecordingPty())
        widget = ImGuiTerminal(session, fonts)
    }

    @AfterTest
    fun tearDown() {
        session.close()
        ImGui.destroyContext(context)
    }

    @Test
    fun windowResizeResizesTheSessionAndKeepsTheContent() {
        // 24 cells: two rows at 20 columns, three rows at 10.
        widget.terminal.write("BEGIN" + "x".repeat(19))
        draw(20, 5)
        assertEquals(20, session.terminal.columns)
        assertEquals(5, session.terminal.rows)

        // Narrower: the line re-wraps into more rows, but they still fit the
        // screen, so nothing may be pushed into the scrollback.
        draw(10, 5)
        assertEquals(10, session.terminal.columns)
        assertEquals(0, session.terminal.scrollbackSize, "the re-wrapped line fits the screen")
        assertTrue(
            screen().contains("BEGIN"),
            "the start of the line is still on screen:\n${screen()}",
        )

        // Wider again: back to the original wrap.
        draw(20, 5)
        assertEquals(20, session.terminal.columns)
        assertTrue(session.terminal.lineAt(0).text(0, 20).startsWith("BEGIN"))
    }

    /** Draws the widget once into [columns] x [rows] cells of available space. */
    private fun draw(columns: Int, rows: Int) {
        val metrics = fonts.metrics
        ImGui.newFrame()
        ImGui.setNextWindowPos(ImVec2(0f, 0f))
        ImGui.setNextWindowSize(ImVec2(400f, 200f))
        ImGui.begin("terminal", null, 0)
        widget.drawContent(
            ImGui.getWindowDrawList(),
            ImGui.getCursorScreenPos(),
            ImVec2((columns + 0.5f) * metrics.cellWidth, (rows + 0.5f) * metrics.cellHeight),
        )
        ImGui.end()
        ImGui.render()
    }

    private fun screen(): String {
        val terminal = session.terminal
        return (0 until terminal.rows).joinToString("\n") {
            terminal.lineAt(it).text(0, terminal.columns)
        }
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
