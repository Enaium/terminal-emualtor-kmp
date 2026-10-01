package cn.enaium.terminal.sdl

import cn.enaium.sdl.SDLBlendMode
import cn.enaium.sdl.SDLColor
import cn.enaium.sdl.SDLFRect
import cn.enaium.sdl.SDLFloatPoint
import cn.enaium.sdl.SDLPixelFormat
import cn.enaium.sdl.SDLRect
import cn.enaium.sdl.SDLRenderer
import cn.enaium.sdl.SDLScaleMode
import cn.enaium.sdl.SDLSurface
import cn.enaium.sdl.SDLTexture
import cn.enaium.sdl.SDLTextureAccess
import cn.enaium.sdl.ttf.SDLTTF
import cn.enaium.sdl.ttf.SDLTTFFont
import kotlin.math.ceil

/**
 * A monospaced SDL_ttf font plus the glyph atlas the renderer samples.
 *
 * The atlas is one [SDLTexture] used as a render target. Glyphs are rasterized
 * once, on demand, with `SDLTTF.renderGlyphBlended` (white, so the vertex color
 * tints them per cell), copied into a free slot and cached by code point. The
 * whole grid is then drawn with a single `renderGeometry` call that samples
 * this texture; that is why glyphs are packed into one texture instead of one
 * texture per glyph.
 *
 * A [SDLRenderer] must be attached with [bind] before [glyphUV] can build the
 * atlas, because a render target can only be created through a renderer. The
 * atlas starts small and grows by doubling its rows; growth repacks the cached
 * glyphs from scratch, which keeps the (small, amortized) cost away from the
 * per-frame path.
 */
class SdlTerminalFont(
    /** Path of the TTF/OTF file. */
    val path: String,
    /** Font size in logical points. */
    val sizePx: Float,
    /**
     * Framebuffer scale (physical pixels per logical point), e.g. 2.0 on a
     * Retina display. The face is rasterized at `sizePx * density` and every
     * metric below is in *physical pixels*, which is also the unit the SDL
     * renderer draws in - without it, glyphs would be upscaled and blurry on a
     * high-DPI display.
     */
    val density: Float = 1f,
    /**
     * TTF/OTF whose glyphs are used for the code points [path]'s face lacks
     * (CJK, symbols, Nerd Font icons), or null for none. SDL_ttf resolves the
     * fallback per glyph, so unlike ImGui's atlas this costs nothing up front.
     */
    val fallbackPath: String? = null,
) : AutoCloseable {

    /** The underlying SDL_ttf font, opened at `sizePx * density`. */
    val font: SDLTTFFont = SDLTTF.openFont(path, sizePx * density)

    private val fallback: SDLTTFFont? =
        fallbackPath?.let { SDLTTF.openFont(it, sizePx * density) }?.also { font.addFallbackFont(it) }

    /** Width of one character cell in physical pixels, measured from `"M"`. */
    val cellWidth: Float

    /** Height of one row in physical pixels. */
    val cellHeight: Float

    /** Offset from the baseline to the top of the font, in physical pixels. */
    val ascent: Float

    /** The atlas texture, or null until a renderer is bound and a glyph is asked for. */
    val atlasTexture: SDLTexture? get() = atlas

    private var boundRenderer: SDLRenderer? = null
    private var atlas: SDLTexture? = null
    private var atlasRows = 0
    private var packColumn = 0
    private var packRow = 0
    private val cellWidthPx: Int
    private val cellHeightPx: Int

    /** Cached atlas rectangles, in insertion order so growth can repack them. */
    private val glyphs = LinkedHashMap<Int, Glyph>()

    /** Code points the font has no glyph for, so the lookup is not retried. */
    private val missing = HashSet<Int>()

    init {
        val measured = font.getStringSize("M")
        cellWidth = measured?.x?.toFloat()?.takeIf { it > 0f } ?: (sizePx * density * 0.6f)
        cellHeight = font.height.toFloat().takeIf { it > 0f } ?: (sizePx * density * 1.2f)
        ascent = font.ascent.toFloat()
        cellWidthPx = ceil(cellWidth).toInt().coerceAtLeast(1)
        cellHeightPx = ceil(cellHeight).toInt().coerceAtLeast(1)
    }

    /**
     * Attaches the renderer that owns the atlas.
     *
     * Binding a different renderer discards the atlas and the cache, because a
     * texture belongs to exactly one renderer.
     */
    fun bind(renderer: SDLRenderer) {
        if (boundRenderer === renderer && atlas != null) return
        closeAtlas()
        glyphs.clear()
        missing.clear()
        boundRenderer = renderer
        createAtlas(INITIAL_ROWS)
    }

    /**
     * The atlas source rectangle for [codePoint], or null when the font has no
     * glyph for it (the caller draws nothing for that cell).
     */
    fun glyphUV(codePoint: Int): SDLFRect? = glyph(codePoint)?.uv

    /**
     * The natural size of [codePoint]'s glyph in pixels, or null when there is
     * none. Call [glyphUV] first; the size is only known once the glyph has
     * been rasterized.
     */
    fun glyphSize(codePoint: Int): SDLFloatPoint? =
        glyphs[codePoint]?.let { SDLFloatPoint(it.width, it.height) }

    private fun glyph(codePoint: Int): Glyph? {
        glyphs[codePoint]?.let { return it }
        if (codePoint in missing) return null
        if (boundRenderer == null) return null
        if (atlas == null) createAtlas(INITIAL_ROWS)
        if (!hasGlyph(codePoint)) {
            missing += codePoint
            return null
        }
        val surface = SDLTTF.renderGlyphBlended(font, codePoint, WHITE)
            ?: run {
                missing += codePoint
                return null
            }
        try {
            if (surface.width <= 0 || surface.height <= 0) {
                missing += codePoint
                return null
            }
            var attempts = 0
            while (true) {
                val slot = allocate(surface.width)
                if (slot != null) {
                    drawGlyph(surface, slot.x, slot.y)
                    val entry = Glyph(
                        uv = SDLFRect(
                            slot.x.toFloat() / atlasWidthPx,
                            slot.y.toFloat() / atlasHeightPx,
                            surface.width.toFloat() / atlasWidthPx,
                            surface.height.toFloat() / atlasHeightPx,
                        ),
                        width = surface.width.toFloat(),
                        height = surface.height.toFloat(),
                    )
                    glyphs[codePoint] = entry
                    return entry
                }
                if (attempts++ >= MAX_GROWTH_ATTEMPTS) {
                    missing += codePoint
                    return null
                }
                grow()
            }
        } finally {
            surface.close()
        }
    }

    /** Reserves a horizontal run of cells; null when the atlas is full. */
    private fun allocate(glyphWidth: Int): Slot? {
        val span = ((glyphWidth + cellWidthPx - 1) / cellWidthPx).coerceIn(1, ATLAS_COLUMNS)
        if (packColumn + span > ATLAS_COLUMNS) {
            packColumn = 0
            packRow++
        }
        if (packRow >= atlasRows) return null
        val slot = Slot(packColumn * cellWidthPx, packRow * cellHeightPx)
        packColumn += span
        return slot
    }

    /** Doubles the atlas height and repacks every cached glyph. */
    private fun grow() {
        val cached = glyphs.keys.toList()
        glyphs.clear()
        val rows = (atlasRows * 2).coerceAtLeast(INITIAL_ROWS)
        closeAtlas()
        createAtlas(rows)
        for (codePoint in cached) glyph(codePoint)
    }

    private fun createAtlas(rows: Int) {
        val target = boundRenderer ?: return
        atlasRows = rows
        packColumn = 0
        packRow = 0
        val width = ATLAS_COLUMNS * cellWidthPx
        val height = rows * cellHeightPx
        val texture = target.createTexture(
            SDLPixelFormat.ARGB8888,
            SDLTextureAccess.TARGET,
            width,
            height,
        )
        // The atlas is sampled by renderGeometry, so its own pixels must blend
        // over the background; the default for a fresh texture is NONE.
        texture.blendMode = SDLBlendMode.BLEND
        // Glyphs are sampled 1:1 (a quad covers exactly the rasterized glyph),
        // so nearest filtering is both crisper and immune to the neighbouring
        // slots bleeding into the sampled rectangle.
        texture.scaleMode = SDLScaleMode.NEAREST
        clearAtlas(target, texture, width, height)
        atlas = texture
    }

    /**
     * Fills a fresh atlas with transparent black.
     *
     * A render target starts out with *undefined* pixels, and the slots a glyph
     * does not fill are never written: without this, sampling at a glyph's edge
     * (or any future filtering) would pick up whatever was in that memory.
     */
    private fun clearAtlas(target: SDLRenderer, texture: SDLTexture, width: Int, height: Int) {
        val previousTarget = target.target
        val previousBlend = target.blendMode
        target.target = texture
        target.blendMode = SDLBlendMode.NONE
        target.drawColor = SDLColor(0, 0, 0, 0)
        target.fillRect(SDLRect(0, 0, width, height))
        target.target = previousTarget
        target.blendMode = previousBlend
    }

    private fun drawGlyph(surface: SDLSurface, x: Int, y: Int) {
        val target = boundRenderer ?: return
        val targetTexture = atlas ?: return
        // SDL_ttf's surfaces are not the platform SDLSurface implementation, so
        // `createTextureFromSurface` rejects them; upload the pixels directly.
        val texture = target.createTexture(surface.format, SDLTextureAccess.STATIC, surface.width, surface.height)
        try {
            texture.update(null, surface.pixels, surface.pitch)
            val previous = target.target
            target.target = targetTexture
            target.renderTexture(
                texture,
                null,
                SDLFRect(x.toFloat(), y.toFloat(), surface.width.toFloat(), surface.height.toFloat()),
            )
            target.target = previous
        } finally {
            texture.close()
        }
    }

    private fun closeAtlas() {
        atlas?.close()
        atlas = null
        atlasRows = 0
        packColumn = 0
        packRow = 0
    }

    private val atlasWidthPx: Float get() = (ATLAS_COLUMNS * cellWidthPx).toFloat()
    private val atlasHeightPx: Float get() = (atlasRows * cellHeightPx).toFloat()

    /** Releases the atlas and the font. */
    override fun close() {
        closeAtlas()
        glyphs.clear()
        missing.clear()
        boundRenderer = null
        fallback?.close()
        font.close()
    }

    /** True when the face or its fallback can draw [codePoint]. */
    private fun hasGlyph(codePoint: Int): Boolean =
        font.hasGlyph(codePoint) || fallback?.hasGlyph(codePoint) == true

    private class Glyph(val uv: SDLFRect, val width: Float, val height: Float)

    private class Slot(val x: Int, val y: Int)

    private companion object {
        /** Cells per atlas row. */
        const val ATLAS_COLUMNS = 32

        /** Rows the atlas starts with. */
        const val INITIAL_ROWS = 8

        /** How many times a single glyph may force the atlas to grow. */
        const val MAX_GROWTH_ATTEMPTS = 8

        val WHITE = SDLColor(255, 255, 255, 255)
    }
}
