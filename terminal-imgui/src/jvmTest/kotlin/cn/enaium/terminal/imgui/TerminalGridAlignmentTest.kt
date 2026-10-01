package cn.enaium.terminal.imgui

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImVec2
import cn.enaium.terminal.core.Terminal
import cn.enaium.terminal.parser.VTParser
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Glyphs must advance by exactly one cell.
 *
 * A glyph whose advance differs from the cell width (a symbol, an emoji, a
 * glyph taken from the merged fallback face) shifts everything after it in the
 * same text run, so the row drifts away from the cursor - which is positioned
 * by cell index, and therefore stays on the grid. Rendering the same text with
 * and without such a glyph in the middle makes the drift visible: the trailing
 * glyph has to land on the same cell in both rows.
 */
class TerminalGridAlignmentTest {

    @Test
    fun cellWidthMatchesTheAdvanceImGuiDrawsWith() {
        // A single glyph's advance is rounded (8.0 on a 2x display where ImGui
        // actually advances 7.5 per glyph when drawing a run); using it for the
        // grid drifts half a pixel per cell, which is a whole cell after a long
        // shell prompt.
        for (density in listOf(1f, 2f)) {
            val regular = firstExisting("/System/Library/Fonts/SFNSMono.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf")
            if (regular == null) {
                println("skipping: no system monospace font found")
                return
            }
            val context = ImGui.createContext()
            try {
                ImGui.getIO().displaySize = ImVec2(800f, 600f)
                ImGui.getIO().deltaTime = 1f / 60f
                ImGui.getIO().fontGlobalScale = 1f / density
                val atlas = ImGui.getIO().fonts
                val fonts = installTerminalFonts(
                    atlas,
                    TerminalFontSettings(regularPath = regular, sizePx = 14f, density = density),
                )
                check(atlas.build()) { "font atlas build failed" }
                // Both values are read *inside* a frame: ImGui picks the font
                // bake per frame, so a measurement taken outside one is not the
                // advance the renderer will use.
                ImGui.newFrame()
                ImGui.pushFont(fonts.regular)
                val drawn = ImGui.calcTextSize("M".repeat(64)).x / 64f
                ImGui.popFont()
                val cellWidth = fonts.metrics.cellWidth
                ImGui.render()
                println("density=$density cellWidth=$cellWidth drawnAdvance=$drawn")
                assertTrue(
                    abs(cellWidth - drawn) <= 0.01f,
                    "the grid must use the advance ImGui draws with: cellWidth=$cellWidth drawn=$drawn",
                )
            } finally {
                ImGui.destroyContext(context)
            }
        }
    }

    @Test
    fun aSymbolDoesNotShiftTheRestOfTheRow() {
        val regular = firstExisting("/System/Library/Fonts/SFNSMono.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf")
        if (regular == null) {
            println("skipping: no system monospace font found")
            return
        }
        val context = ImGui.createContext()
        try {
            ImGui.getIO().displaySize = ImVec2(800f, 600f)
            ImGui.getIO().deltaTime = 1f / 60f
            val atlas = ImGui.getIO().fonts
            val fonts = installTerminalFonts(
                atlas,
                TerminalFontSettings(
                    regularPath = regular,
                    fallbackPath = firstExisting(
                        "/System/Library/Fonts/Hiragino Sans GB.ttc",
                        "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
                    ),
                    sizePx = 14f,
                    density = 1f,
                ),
            )
            check(atlas.build()) { "font atlas build failed" }

            // Row 0 is the reference, row 1 replaces the third cell with a
            // symbol (and a wide character, which is drawn on its own too).
            val terminal = Terminal(columns = 8, rows = 3)
            val parser = VTParser(terminal)
            parser.feed("abcd")
            parser.feed("\u001B[2;1H")
            parser.feed("ab\u2605d")
            parser.feed("\u001B[3;1H")
            parser.feed("ab\u4E2Dd")
            val frame = renderFrame(terminal, fonts)

            val foreground = imColor(0xD4D4D4)
            val cellHeight = fonts.metrics.cellHeight
            val rows = HashMap<Int, MutableList<Float>>()
            for (index in frame.colors.indices) {
                if (frame.colors[index] != foreground) continue
                val row = ((frame.positions[index * 2 + 1] - frame.origin.y) / cellHeight).toInt()
                rows.getOrPut(row) { ArrayList() }.add(frame.positions[index * 2])
            }
            println("cellWidth=${fonts.metrics.cellWidth} rows=$rows")

            val reference = rows[0]?.maxOrNull() ?: error("row 0 was not drawn")
            // Row 1 has a one-cell symbol where row 0 has `c`, so its `d` is on
            // the same column. Row 2 has a *wide* character, which takes two
            // cells, so its `d` is exactly one cell further.
            val expectations = mapOf(1 to reference, 2 to reference + fonts.metrics.cellWidth)
            for ((row, expected) in expectations) {
                val actual = rows[row]?.maxOrNull() ?: error("row $row was not drawn")
                assertTrue(
                    abs(actual - expected) <= 0.5f,
                    "row $row: the text after a symbol or wide glyph must stay on its cell " +
                        "(expected=$expected actual=$actual)",
                )
            }
        } finally {
            ImGui.destroyContext(context)
        }
    }

    private class Frame(val colors: List<Int>, val positions: List<Float>, val origin: ImVec2)

    private fun renderFrame(terminal: Terminal, fonts: TerminalFonts): Frame {
        val renderer = TerminalRenderer(fonts)
        var origin = ImVec2(0f, 0f)
        repeat(2) {
            ImGui.newFrame()
            // A sized window: an auto-sized one clips the rows below its
            // content region.
            ImGui.setNextWindowPos(ImVec2(0f, 0f))
            ImGui.setNextWindowSize(ImVec2(400f, 200f))
            ImGui.begin("t", null, 0)
            origin = ImGui.getCursorScreenPos()
            renderer.draw(ImGui.getWindowDrawList(), origin, terminal, blinkOn = false)
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
        return Frame(colors, positions, origin)
    }

    private fun firstExisting(vararg paths: String): String? = paths.firstOrNull { File(it).isFile }
}
