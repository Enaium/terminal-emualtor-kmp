package cn.enaium.terminal.imgui

import cn.enaium.imgui.ImFontConfig
import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImVec2
import cn.enaium.terminal.core.Terminal
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Glyph coverage.
 *
 * ImGui draws a code point that is missing from the atlas with the font's
 * *fallback glyph* (the `?`), so a too narrow [TerminalFontSettings.glyphRanges]
 * - or an atlas that overflowed - shows up as question marks. Comparing the
 * texture coordinates a character draws with against the ones `?` draws with
 * tells the two apart without a screenshot.
 */
class TerminalGlyphCoverageTest {

    @Test
    fun charactersAreDrawnWithTheirOwnGlyph() = assertEveryClassHasItsOwnGlyph(density = 1f)

    @Test
    fun charactersAreDrawnWithTheirOwnGlyphOnHighDpiDisplays() {
        // density 2 is a Retina display: the face is rasterized at twice the
        // logical size, which used to overflow the atlas and make ImGui drop
        // glyphs (everything rendered as `?`).
        assertEveryClassHasItsOwnGlyph(density = 2f)
    }

    @Test
    fun theExampleConfigurationCoversEveryClass() {
        // Exactly what the examples build: a terminal face, a merged CJK
        // fallback and ImGui's own default face, at the density of a Retina
        // display.
        assertEveryClassHasItsOwnGlyph(density = 2f, withImGuiDefaultFont = true)
    }

    private fun assertEveryClassHasItsOwnGlyph(density: Float, withImGuiDefaultFont: Boolean = false) {
        val regular = firstExisting(
            "/System/Library/Fonts/SFNSMono.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf",
            "C:\\Windows\\Fonts\\consola.ttf",
        )
        if (regular == null) {
            println("skipping: no system monospace font found")
            return
        }
        val context = ImGui.createContext()
        try {
            ImGui.getIO().displaySize = ImVec2(800f, 600f)
            ImGui.getIO().deltaTime = 1f / 60f
            // What a high-DPI host does: bake for the physical pixel grid and
            // scale the UI back down (see TerminalFontSettings.density).
            ImGui.getIO().fontGlobalScale = 1f / density
            val atlas = ImGui.getIO().fonts
            val fonts = installTerminalFonts(
                atlas,
                TerminalFontSettings(
                    regularPath = regular,
                    fallbackPath = firstExisting(
                        "/System/Library/Fonts/Hiragino Sans GB.ttc",
                        "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
                        "C:\\Windows\\Fonts\\msyh.ttc",
                    ),
                    sizePx = 14f,
                    density = density,
                ),
            )
            if (withImGuiDefaultFont) {
                // What the examples add for ImGui's own widgets.
                atlas.addFontDefault(ImFontConfig(sizePixels = 13f * density))
            }
            check(atlas.build()) { "font atlas build failed" }
            val texture = atlas.getTexDataAsRGBA32()
            println(
                "density=$density defaultFont=$withImGuiDefaultFont -> " +
                    "atlas ${texture.width}x${texture.height} ${texture.width * texture.height * 4 / 1024} KiB",
            )
            // A glyph that does not fit into the atlas is dropped silently and
            // renders as `?`, so the atlas must stay within bounds.
            assertTrue(
                texture.width <= 4096 && texture.height <= 4096,
                "the atlas must not overflow, was ${texture.width}x${texture.height}",
            )
            val renderer = TerminalRenderer(fonts)

            val missingGlyph = textureCoordinates("?", renderer)
            assertTrue(missingGlyph.isNotEmpty(), "the fallback glyph itself is drawn")

            val samples = mapOf(
                "box drawing" to '─',
                "block elements" to '█',
                "arrows" to '←',
                "geometric shapes" to '●',
                "math" to '±',
                "symbols" to '★',
                "CJK" to '中',
            )
            for ((name, character) in samples) {
                val coordinates = textureCoordinates(character.toString(), renderer)
                assertTrue(coordinates.isNotEmpty(), "$name ('$character') is drawn")
                assertTrue(
                    coordinates != missingGlyph,
                    "$name ('$character') must use its own glyph, not the '?' fallback (density $density)",
                )
            }
        } finally {
            ImGui.destroyContext(context)
        }
    }

    /** The atlas texture coordinates of the single glyph [text] draws with. */
    private fun textureCoordinates(text: String, renderer: TerminalRenderer): Set<Float> {
        val terminal = Terminal(columns = 4, rows = 1)
        terminal.write(text)
        // Two frames: the first one lays the window out (Begin reports an
        // invisible window), the second produces the draw data to inspect.
        repeat(2) {
            ImGui.newFrame()
            ImGui.begin("terminal", null, 0)
            renderer.draw(ImGui.getWindowDrawList(), ImGui.getCursorScreenPos(), terminal, blinkOn = false)
            ImGui.end()
            ImGui.render()
        }

        val data = ImGui.getDrawData()
        val coordinates = LinkedHashSet<Float>()
        for (listIndex in 0 until data.cmdListsCount) {
            val list = data.cmdList(listIndex)
            val vertices = list.copyVtx(0, list.vtxCount)
            for (index in 0 until list.vtxCount * 2) {
                coordinates.add(vertices.uvs[index])
            }
        }
        return coordinates
    }

    private fun firstExisting(vararg paths: String): String? = paths.firstOrNull { File(it).isFile }
}
