package cn.enaium.terminal.imgui

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImVec2
import cn.enaium.terminal.pty.PtyConfig
import cn.enaium.terminal.session.TerminalSession
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End to end: a real zsh drives the session, the imgui renderer draws it, and
 * the cursor must sit on the cell right after the text.
 */
class ZshCursorRenderTest {

    @Test
    fun cursorSitsRightAfterTheTypedText() = assertCursorAlignment(density = 1f)

    @Test
    fun cursorSitsRightAfterTheTypedTextOnHighDpiDisplays() = assertCursorAlignment(density = 2f)

    private fun assertCursorAlignment(density: Float) {
        val regular = firstExisting("/System/Library/Fonts/SFNSMono.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf")
        val zsh = firstExisting("/bin/zsh")
        if (regular == null || zsh == null) {
            println("skipping: needs a monospace font and zsh")
            return
        }
        val context = ImGui.createContext()
        val session = TerminalSession.spawn(
            PtyConfig(command = listOf(zsh, "-f"), columns = 40, rows = 4, environment = mapOf("PROMPT" to "> ")),
        )
        try {
            ImGui.getIO().displaySize = ImVec2(800f, 600f)
            ImGui.getIO().deltaTime = 1f / 60f
            // A Retina display: bake for the physical pixel grid and scale the
            // UI back down, exactly like the examples do.
            ImGui.getIO().fontGlobalScale = 1f / density
            val atlas = ImGui.getIO().fonts
            val fonts = installTerminalFonts(atlas, TerminalFontSettings(regularPath = regular, sizePx = 14f, density = density))
            check(atlas.build()) { "font atlas build failed" }
            val renderer = TerminalRenderer(fonts, TerminalTheme(cursor = 0x00FF00))

            var waited = 0
            while (waited < 3000 && session.terminal.cursorColumn < 1) {
                session.pump(); Thread.sleep(20); waited += 20
            }
            session.sendText("abc")
            waited = 0
            while (waited < 600) { session.pump(); Thread.sleep(20); waited += 20 }

            val terminal = session.terminal
            var origin = ImVec2(0f, 0f)
            repeat(2) {
                ImGui.newFrame()
                ImGui.setNextWindowPos(ImVec2(0f, 0f))
                ImGui.setNextWindowSize(ImVec2(400f, 200f))
                ImGui.begin("t", null, 0)
                origin = ImGui.getCursorScreenPos()
                renderer.draw(ImGui.getWindowDrawList(), origin, terminal, blinkOn = true)
                ImGui.end()
                ImGui.render()
            }
            val data = ImGui.getDrawData()
            var cursorX = -1f
            var textEnd = -1f
            for (listIndex in 0 until data.cmdListsCount) {
                val list = data.cmdList(listIndex)
                val vertices = list.copyVtx(0, list.vtxCount)
                for (index in 0 until list.vtxCount) {
                    val color = vertices.colors[index]
                    val x = vertices.positions[index * 2]
                    if (color == imColor(0x00FF00)) {
                        cursorX = if (cursorX < 0) x else minOf(cursorX, x)
                    } else if (color == imColor(0xD4D4D4)) {
                        textEnd = maxOf(textEnd, x)
                    }
                }
            }
            val cell = fonts.metrics.cellWidth
            println("density=$density cell=$cell cursorX=$cursorX textEnd=$textEnd stateColumn=${terminal.cursorColumn}")
            assertTrue(cursorX > 0, "the cursor was drawn")
            // The cursor block starts where the last glyph's cell ends: its
            // left edge minus the last glyph's right edge must be smaller than
            // half a cell (the text is drawn on the same grid).
            val gap = cursorX - textEnd
            assertTrue(
                gap in -0.5f..(cell * 0.5f),
                "the cursor must sit on the cell after the text: gap=$gap cell=$cell",
            )
        } finally {
            session.close()
            ImGui.destroyContext(context)
        }
    }

    private fun firstExisting(vararg paths: String): String? = paths.firstOrNull { File(it).isFile }
}
