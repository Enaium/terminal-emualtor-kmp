package cn.enaium.terminal.imgui

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiContext
import cn.enaium.imgui.ImVec2
import cn.enaium.terminal.core.Terminal
import cn.enaium.terminal.parser.VTParser
import cn.enaium.terminal.core.TerminalColor
import cn.enaium.terminal.core.TerminalPosition
import cn.enaium.terminal.core.TerminalSelectionMode
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Headless render tests: an ImGui context is created without a window backend,
 * a frame is built into a draw list, and the resulting vertices are inspected.
 * This is what proves the terminal actually draws text, backgrounds, the
 * cursor and selections (a screenshot cannot be asserted on).
 */
class TerminalRendererTest {

    private lateinit var context: ImGuiContext
    private lateinit var fonts: TerminalFonts
    private lateinit var renderer: TerminalRenderer

    @BeforeTest
    fun setUp() {
        context = ImGui.createContext()
        ImGui.getIO().displaySize = ImVec2(800f, 600f)
        ImGui.getIO().deltaTime = 1f / 60f
        val atlas = ImGui.getIO().fonts
        fonts = installTerminalFonts(atlas, TerminalFontSettings(sizePx = 14f, density = 1f))
        check(atlas.build()) { "font atlas build failed" }
        // Distinct cursor/selection colors so the tests can tell the primitives
        // apart (the default cursor color equals the default foreground).
        renderer = TerminalRenderer(
            fonts,
            TerminalTheme(cursor = 0x00FF00, cursorText = 0x101014, selectionBackground = 0xFF00FF),
        )
    }

    @AfterTest
    fun tearDown() {
        ImGui.destroyContext(context)
    }

    @Test
    fun drawsBackgroundAndText() {
        val terminal = Terminal(columns = 20, rows = 3)
        terminal.write("hello")
        val frame = renderFrame(terminal, blinkOn = true)

        assertTrue(frame.count(BACKGROUND) > 0, "background vertices")
        assertTrue(frame.count(FOREGROUND) >= 4, "glyph vertices")
    }

    @Test
    fun backgroundRunsAreMerged() {
        val terminal = Terminal(columns = 40, rows = 2)
        // A red background over the first ten cells: one run, hence one quad.
        VTParser(terminal).feed("\u001B[41m          \u001B[0m")
        val frame = renderFrame(terminal, blinkOn = false)

        assertEquals(4, frame.count(RED), "one merged background quad")
    }

    @Test
    fun cursorDisappearsWhenBlinkingOff() {
        val terminal = Terminal(columns = 20, rows = 3)
        terminal.write("hello")
        val on = renderFrame(terminal, blinkOn = true)
        val off = renderFrame(terminal, blinkOn = false)

        // The block cursor sits on an empty cell: it contributes exactly one quad.
        assertEquals(4, on.count(CURSOR) - off.count(CURSOR), "cursor block vertices")
    }

    @Test
    fun cursorShapeChangesTheDrawnRectangle() {
        val terminal = Terminal(columns = 20, rows = 3)
        terminal.write("hi")
        terminal.cursorTo(1, 1)

        terminal.setCursorShape(1) // blinking block
        val block = renderFrame(terminal, blinkOn = true)
        terminal.setCursorShape(3) // blinking bar
        val bar = renderFrame(terminal, blinkOn = true)

        val blockWidth = block.widthOf(CURSOR)
        val barWidth = bar.widthOf(CURSOR)
        assertTrue(barWidth <= 2.5f, "bar cursor is thin, was $barWidth")
        assertTrue(blockWidth >= fonts.metrics.cellWidth - 0.5f, "block cursor spans a cell, was $blockWidth")
    }

    @Test
    fun selectionIsHighlighted() {
        val terminal = Terminal(columns = 20, rows = 3)
        terminal.write("select me")
        val plain = renderFrame(terminal, blinkOn = true)

        val lineId = terminal.lineIdAt(0)
        terminal.setSelection(TerminalPosition(lineId, 0), TerminalPosition(lineId, 5), TerminalSelectionMode.LINEAR)
        val selected = renderFrame(terminal, blinkOn = true)

        assertTrue(selected.count(SELECTION) > plain.count(SELECTION), "selection quad")
        assertEquals(4, selected.count(SELECTION) - plain.count(SELECTION), "one selection quad")
    }

    @Test
    fun reverseVideoSwapsDefaultColors() {
        val terminal = Terminal(columns = 20, rows = 2)
        terminal.write("x")
        val normal = renderFrame(terminal, blinkOn = false)
        terminal.setPrivateMode(5, true)
        val reversed = renderFrame(terminal, blinkOn = false)

        // With DECSCNM the grid is no longer painted in the dark default
        // background: both the fill and the glyphs use the inverted colors.
        assertTrue(normal.count(BACKGROUND) > 0, "grid painted in the default background")
        assertEquals(0, reversed.count(BACKGROUND), "grid no longer painted in the default background")
        assertTrue(reversed.colors.isNotEmpty(), "the screen is still drawn")
    }

    @Test
    fun wideCharactersOccupyTwoCells() {
        val terminal = Terminal(columns = 20, rows = 2)
        terminal.write("中")
        assertEquals(2, terminal.lineAt(0).widthAt(0))
        // The cursor advances past both cells of the wide character.
        assertEquals(2, terminal.cursorColumn)
    }

    @Test
    fun reflowedScreenStillRenders() {
        val terminal = Terminal(columns = 40, rows = 4)
        terminal.write("a".repeat(80))
        terminal.resize(20, 4)
        val frame = renderFrame(terminal, blinkOn = true)
        assertTrue(frame.colors.isNotEmpty(), "vertices after reflow")
    }

    // ==================== helpers ====================

    /** The colors and x positions of every vertex the terminal produced. */
    private class Frame(val colors: List<Int>, val positions: List<Float>) {
        fun count(color: Int): Int = colors.count { it == color }

        /** The horizontal extent of the vertices drawn in [color]. */
        fun widthOf(color: Int): Float {
            val xs = colors.indices.filter { colors[it] == color }.map { positions[it * 2] }
            if (xs.isEmpty()) return 0f
            return xs.max() - xs.min()
        }
    }

    private fun renderFrame(terminal: Terminal, blinkOn: Boolean): Frame {
        // The first frame after a context/window is created is not laid out
        // yet (Begin reports an invisible window), so draw a warm-up frame and
        // inspect the second one - exactly what a real frame loop does.
        repeat(2) {
            ImGui.newFrame()
            ImGui.begin("terminal", null, 0)
            val origin = ImGui.getCursorScreenPos()
            renderer.draw(ImGui.getWindowDrawList(), origin, terminal, blinkOn)
            ImGui.end()
            ImGui.render()
        }

        val data = ImGui.getDrawData()
        val colors = ArrayList<Int>()
        val positions = ArrayList<Float>()
        for (listIndex in 0 until data.cmdListsCount) {
            val list = data.cmdList(listIndex)
            val vertices = list.copyVtx(0, list.vtxCount)
            for (index in 0 until list.vtxCount) {
                colors.add(vertices.colors[index])
                positions.add(vertices.positions[index * 2])
                positions.add(vertices.positions[index * 2 + 1])
            }
        }
        return Frame(colors, positions)
    }

    private companion object {
        val BACKGROUND = imColor(0x101014)
        val FOREGROUND = imColor(0xD4D4D4)
        val RED = imColor(0xCD0000)
        val CURSOR = imColor(0x00FF00)
        val SELECTION = imColor(0xFF00FF, alpha = 0x80)
    }
}
