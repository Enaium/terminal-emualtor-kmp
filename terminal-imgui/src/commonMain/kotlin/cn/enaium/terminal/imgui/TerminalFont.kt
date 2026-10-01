package cn.enaium.terminal.imgui

import cn.enaium.imgui.ImFont
import cn.enaium.imgui.ImFontAtlas
import cn.enaium.imgui.ImFontConfig
import cn.enaium.imgui.ImFontGlyphRanges
import cn.enaium.imgui.ImGui

/** The size of one character cell and where text sits inside it. */
data class TerminalFontMetrics(
    /** Width of one character cell in pixels. */
    val cellWidth: Float,
    /** Height of one row in pixels. */
    val cellHeight: Float,
    /** Offset from the top of the cell to the top of the text. */
    val textOffsetY: Float,
    /** The font size the cell was measured with. */
    val fontSize: Float,
)

/**
 * The font faces a terminal draws with.
 *
 * A terminal needs a *monospaced* face whose glyphs all advance by exactly
 * [TerminalFontMetrics.cellWidth]; the renderer positions every cell itself, so
 * a proportional font would visibly drift.
 *
 * The bold/italic faces are optional: without them the renderer fakes bold by
 * overdrawing and ignores italic. The fallback face is merged into every other
 * face so CJK, symbols and emoji do not render as tofu.
 */
data class TerminalFontSettings(
    /** TTF/OTF for regular text; null keeps ImGui's built-in font (Latin only). */
    val regularPath: String? = null,
    /** TTF/OTF for bold text, or null to overdraw the regular face. */
    val boldPath: String? = null,
    /** TTF/OTF for italic text, or null to draw italic text with the regular face. */
    val italicPath: String? = null,
    /** TTF/OTF for bold italic text. */
    val boldItalicPath: String? = null,
    /** TTF/OTF merged into every face for the glyphs they lack (CJK, emoji, symbols). */
    val fallbackPath: String? = null,
    /**
     * Faces merged into the regular face after [fallbackPath] — the platform's
     * extra symbol faces. One face rarely covers everything a shell prints (a
     * CJK face has no braille, a symbol face no ideographs) and ImGui draws
     * every code point none of the merged faces provides with its `?` glyph.
     */
    val extraFallbackPaths: List<String> = emptyList(),
    /** Font size in logical pixels (before [density]). */
    val sizePx: Float = 14f,
    /** Row height as a multiple of the font size. */
    val lineSpacing: Float = 1.3f,
    /**
     * The display's framebuffer scale: glyphs are baked at `sizePx * density`
     * physical pixels while the layout stays at `sizePx` logical pixels.
     *
     * Set `ImGuiIO.fontGlobalScale = 1f / density` as well so the UI is drawn
     * at the logical size (the examples do). Do *not* reach for
     * `ImFontConfig.rasterizerDensity` instead: in ImGui 1.92's static atlas
     * path it multiplies the font's own density again, which bakes the atlas
     * with the density *squared* - a Retina display then produces a 256 MiB
     * atlas that overflows and makes ImGui drop glyphs (they render as `?`).
     */
    val density: Float = 1f,
    /**
     * The code points to rasterize. The default ([TerminalGlyphRanges.terminal])
     * covers Latin, punctuation, arrows, box drawing, block elements, symbols,
     * kana and CJK; a face that does not contain a range simply contributes
     * nothing for it.
     *
     * Every code point in the range that the face *does* contain is baked into
     * the atlas up front, so a broad range with a CJK-capable face costs tens of
     * megabytes and - on native targets, where the binding copies the atlas
     * pixel by pixel - seconds of startup. Narrow it (or use
     * [fallbackGlyphRanges] for a fallback face) when that matters.
     */
    val glyphRanges: IntArray? = TerminalGlyphRanges.terminal,
    /**
     * The ranges used for [fallbackPath]. The default adds the symbol blocks,
     * kana, the most common Chinese characters, emoji and the private use area
     * (Nerd Font, Powerline) without the huge CJK/Hangul blocks, which keeps
     * the atlas small enough that ImGui never has to drop glyphs.
     */
    val fallbackGlyphRanges: IntArray? = TerminalGlyphRanges.fallback,
)

/** The faces installed by [installTerminalFonts]. */
class TerminalFonts internal constructor(
    /** The regular face; the cell metrics are measured from it. */
    val regular: ImFont,
    val bold: ImFont?,
    val italic: ImFont?,
    val boldItalic: ImFont?,
    private val lineSpacing: Float,
) {

    /**
     * The cell metrics, measured from [regular].
     *
     * Measured (and re-measured) inside the *current* ImGui frame: ImGui picks
     * the font bake per frame, and the very same face reports a different
     * advance outside one (7.0 instead of 7.5 on a 2x display). A grid built on
     * the wrong value drifts half a pixel per cell, which is a whole cell after
     * a long shell prompt - the text ends up beside the cursor, which is
     * positioned by cell index.
     */
    val metrics: TerminalFontMetrics
        get() {
            val frame = ImGui.getFrameCount()
            val cached = metricsCache
            if (cached != null && metricsFrame == frame) return cached
            val measured = measure()
            metricsCache = measured
            metricsFrame = frame
            return measured
        }

    private var metricsFrame = -1
    private var metricsCache: TerminalFontMetrics? = null

    private fun measure(): TerminalFontMetrics {
        ImGui.pushFont(regular)
        try {
            val fontSize = ImGui.getFontSize().coerceAtLeast(1f)
            // Measured over a *run*, not a single glyph: with pixel snapping and
            // a fractional framebuffer scale ImGui rounds a lone glyph's advance
            // (8.0 at a 2x display where it actually advances 7.5 per glyph when
            // drawing). A single-glyph measurement therefore drifts half a pixel
            // per cell, which accumulates into a full cell over a long shell
            // prompt - the text ends up one character away from the cursor.
            val sampleCount = 64
            val cellWidth = (ImGui.calcTextSize("M".repeat(sampleCount)).x / sampleCount).coerceAtLeast(1f)
            val cellHeight = (fontSize * lineSpacing).coerceAtLeast(fontSize)
            val textOffsetY = (cellHeight - fontSize) / 2f
            return TerminalFontMetrics(cellWidth, cellHeight, textOffsetY, fontSize)
        } finally {
            ImGui.popFont()
        }
    }

    /** The face to draw with, falling back to the regular one. */
    fun face(wantBold: Boolean, wantItalic: Boolean): ImFont = when {
        wantBold && wantItalic -> boldItalic ?: bold ?: italic ?: regular
        wantBold -> bold ?: regular
        wantItalic -> italic ?: regular
        else -> regular
    }

    /** True when bold text must be faked by overdrawing (no bold face installed). */
    fun needsFakeBold(wantBold: Boolean): Boolean = wantBold && bold == null

    /** True when italic text has no dedicated face. */
    fun needsFakeItalic(wantItalic: Boolean): Boolean = wantItalic && italic == null
}

/**
 * Adds the terminal's faces to [atlas].
 *
 * Call it while the host is still setting up, *before* `atlas.build()`: a face
 * cannot join the atlas afterwards. [TerminalFontSettings.density] should be
 * the framebuffer scale so glyphs are rasterized for the real pixel grid.
 */
fun installTerminalFonts(atlas: ImFontAtlas, settings: TerminalFontSettings): TerminalFonts {

    val regular = atlas.addFace(settings.regularPath, settings)
    val bold = settings.boldPath?.let { atlas.addFace(it, settings) }
    val italic = settings.italicPath?.let { atlas.addFace(it, settings) }
    val boldItalic = settings.boldItalicPath?.let { atlas.addFace(it, settings) }
    return TerminalFonts(regular, bold, italic, boldItalic, settings.lineSpacing)
}

private fun ImFontAtlas.addFace(path: String?, settings: TerminalFontSettings): ImFont {
    val config = ImFontConfig(
        // Baked at the *physical* size; the caller scales the UI down with
        // ImGuiIO.fontGlobalScale (see TerminalFontSettings.density).
        sizePixels = settings.sizePx * settings.density,
        pixelSnapH = true,
        // Glyphs are snapped to the pixel grid and drawn 1:1, so oversampling
        // would only multiply the atlas size.
        oversampleH = 1,
        oversampleV = 1,
        glyphRanges = settings.glyphRanges,
    )
    val font = if (path == null) addFontDefault(config) else addFontFromFileTTF(path, config)
    // Merging is what makes a fallback work: a face added without mergeMode
    // would only be used when explicitly pushed. mergeMode merges into the font
    // added *before* it, so the extras have to follow the first fallback here —
    // added after another face they would land in that face instead of in
    // [TerminalFonts.regular], which is the one the renderer draws with.
    for (path in listOfNotNull(settings.fallbackPath) + settings.extraFallbackPaths) {
        addFontFromFileTTF(
            path,
            ImFontConfig(
                sizePixels = settings.sizePx * settings.density,
                pixelSnapH = true,
                oversampleH = 1,
                oversampleV = 1,
                mergeMode = true,
                glyphRanges = settings.fallbackGlyphRanges ?: settings.glyphRanges,
            ),
        )
    }
    return font
}
