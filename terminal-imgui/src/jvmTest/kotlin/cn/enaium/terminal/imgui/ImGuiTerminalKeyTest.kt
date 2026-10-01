package cn.enaium.terminal.imgui

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiKey
import cn.enaium.imgui.ImVec2
import cn.enaium.terminal.core.Terminal
import cn.enaium.terminal.pty.PtyProcess
import cn.enaium.terminal.session.TerminalSession
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Keyboard input, driven through ImGui's own key queue the way a backend does.
 *
 * Modifiers are read from the physical keys: imgui 1.92 documents `ImGuiMod_*`
 * as key-chord flags, and `IsKeyDown(ImGuiMod_Ctrl)` stays false while Ctrl is
 * held — which used to drop every Ctrl combination, so Ctrl+C never reached
 * the pty.
 */
class ImGuiTerminalKeyTest {

    private val written = ArrayList<Int>()

    @Test
    fun ctrlCombinationsUseThePhysicalKeys() {
        val context = ImGui.createContext()
        try {
            val io = ImGui.getIO()
            io.displaySize = ImVec2(800f, 600f)
            io.deltaTime = 1f / 60f
            val fonts = installTerminalFonts(io.fonts, TerminalFontSettings(sizePx = 14f, density = 1f))
            check(io.fonts.build())
            val session = TerminalSession(Terminal(20, 3), RecordingPty(written))
            val widget = ImGuiTerminal(session, fonts)

            frame(widget, fonts)

            // What a backend sends for Ctrl+C: the physical key and the mod flag.
            io.addKeyEvent(ImGuiKey.LEFT_CTRL, true)
            io.addKeyEvent(ImGuiKey.MOD_CTRL, true)
            io.addKeyEvent(ImGuiKey.C, true)
            frame(widget, fonts)
            assertEquals(listOf(0x03), written, "Ctrl+C must send the interrupt character")

            written.clear()
            io.addKeyEvent(ImGuiKey.C, false)
            io.addKeyEvent(ImGuiKey.D, true)
            frame(widget, fonts)
            assertEquals(listOf(0x04), written, "Ctrl+D must send end-of-transmission")

            session.close()
        } finally {
            ImGui.destroyContext(context)
        }
    }

    private fun frame(widget: ImGuiTerminal, fonts: TerminalFonts) {
        ImGui.newFrame()
        println(
            "DIAG focused=${widget.isFocused} lctrl=${ImGui.isKeyDown(ImGuiKey.LEFT_CTRL)} " +
                "rctrl=${ImGui.isKeyDown(ImGuiKey.RIGHT_CTRL)} modCtrl=${ImGui.isKeyDown(ImGuiKey.MOD_CTRL)} " +
                "cPressed=${ImGui.isKeyPressed(ImGuiKey.C, repeat = true)}",
        )
        ImGui.begin("terminal")
        widget.drawContent(
            ImGui.getWindowDrawList(),
            ImGui.getCursorScreenPos(),
            ImVec2(20 * fonts.metrics.cellWidth, 3 * fonts.metrics.cellHeight),
        )
        ImGui.end()
        ImGui.render()
    }

    /** A pty that records what the widget writes and reports end-of-stream. */
    private class RecordingPty(private val written: MutableList<Int>) : PtyProcess {
        override val pid: Int get() = 0
        override val devicePath: String get() = ""
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
        override fun write(data: ByteArray, offset: Int, length: Int) {
            for (i in offset until offset + length) written += data[i].toInt() and 0xFF
        }

        override fun resize(columns: Int, rows: Int) = Unit
        override fun isAlive(): Boolean = true
        override fun terminate(force: Boolean) = Unit
        override fun close() = Unit
    }
}
