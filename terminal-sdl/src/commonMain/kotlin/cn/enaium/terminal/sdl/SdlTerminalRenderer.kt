package cn.enaium.terminal.sdl

import cn.enaium.sdl.SDLBlendMode
import cn.enaium.sdl.SDLColor
import cn.enaium.sdl.SDLFRect
import cn.enaium.sdl.SDLFloatPoint
import cn.enaium.sdl.SDLRect
import cn.enaium.sdl.SDLRenderer
import cn.enaium.sdl.SDLVertex
import cn.enaium.terminal.core.Terminal
import cn.enaium.terminal.core.TerminalAttributes
import cn.enaium.terminal.core.TerminalCell
import cn.enaium.terminal.core.TerminalColor
import cn.enaium.terminal.core.TerminalCursorShape
import cn.enaium.terminal.core.TerminalLine

/**
 * Draws a [Terminal] with SDL's 2D renderer.
 *
 * The frame is split into cheap phases so the expensive work happens once:
 *
 *  1. one `fillRect` for the default background,
 *  2. one `fillRect` per run of cells sharing a background color (the default
 *     background is skipped because phase 1 already painted it),
 *  3. the selection highlight,
 *  4. a block cursor, if any,
 *  5. every glyph as a single batched `renderGeometry` call sampling the font
 *     atlas (a quad per non-blank, non-continuation cell),
 *  6. underlines / strikethrough / hyperlink underlines as thin rectangles,
 *  7. bar/underline cursors and the scrollback thumb.
 *
 * Batching the glyphs into one call is what keeps a full redraw cheap: SDL
 * renders thousands of quads in a single draw call instead of one per
 * character.
 */
class SdlTerminalRenderer(
    /** The font atlas the glyphs come from. */
    val font: SdlTerminalFont,
    /** The renderer to draw into. */
    val renderer: SDLRenderer,
    /** The colors to draw with; replaceable at runtime. */
    var theme: SdlTerminalTheme = SdlTerminalTheme.Default,
) {

    private val vertices = ArrayList<SDLVertex>(4096)
    private var indices = IntArray(6144)
    private var indexCount = 0
    private val decorations = ArrayList<Decoration>(64)
    private val colorCache = HashMap<Int, SDLColor>()

    /**
     * Draws [terminal] filling the renderer's output.
     *
     * [blinkOn] is the current phase of the cursor blink; the caller derives it
     * from the terminal's blink mode and a timer.
     */
    fun draw(terminal: Terminal, blinkOn: Boolean) {
        font.bind(renderer)

        val cellWidth = font.cellWidth
        val cellHeight = font.cellHeight
        val columns = terminal.columns
        val rows = terminal.rows
        // The grid rarely tiles the target exactly (a partial row/column is
        // left over); the whole target is painted with the default background
        // so the widget never leaves a seam of the host's clear color.
        val output = renderer.outputSize
        val width = output.x
        val height = output.y
        val defaultBackground = backgroundFor(terminal, TerminalColor.Default)
        val cursor = visibleCursor(terminal, blinkOn)

        renderer.blendMode = SDLBlendMode.NONE
        renderer.drawColor = color(defaultBackground)
        renderer.fillRect(SDLRect(0, 0, width, height))

        for (row in 0 until rows) {
            val line = terminal.lineAt(row)
            val y = (row * cellHeight).toInt()
            drawBackgrounds(terminal, line, y, cellWidth, cellHeight, defaultBackground)
        }

        renderer.blendMode = SDLBlendMode.BLEND
        for (row in 0 until rows) {
            val line = terminal.lineAt(row)
            drawSelection(terminal, line, (row * cellHeight).toInt(), cellWidth, cellHeight)
        }
        if (cursor != null && cursor.shape == TerminalCursorShape.BLOCK) {
            renderer.drawColor = color(theme.cursor)
            renderer.fillRect(
                SDLRect(
                    (cursor.column * cellWidth).toInt(),
                    (cursor.row * cellHeight).toInt(),
                    ((if (cursor.wide) 2 else 1) * cellWidth).toInt(),
                    cellHeight.toInt(),
                ),
            )
        }

        drawGlyphs(terminal, blinkOn, cursor, cellWidth, cellHeight)
        drawDecorations(cellWidth, cellHeight)
        drawCursorShape(cursor, cellWidth, cellHeight)
        if (terminal.isScrolledBack) drawScrollbar(terminal, width, height)

        renderer.blendMode = SDLBlendMode.NONE
    }

    private fun drawBackgrounds(
        terminal: Terminal,
        line: TerminalLine,
        y: Int,
        cellWidth: Float,
        cellHeight: Float,
        defaultBackground: Int,
    ) {
        var column = 0
        while (column < line.columns) {
            val background = cellBackground(terminal, line, column)
            if (background == defaultBackground) {
                column++
                continue
            }
            var end = column + 1
            while (end < line.columns && cellBackground(terminal, line, end) == background) end++
            renderer.drawColor = color(background)
            renderer.fillRect(
                SDLRect((column * cellWidth).toInt(), y, ((end - column) * cellWidth).toInt(), cellHeight.toInt()),
            )
            column = end
        }
    }

    private fun drawSelection(
        terminal: Terminal,
        line: TerminalLine,
        y: Int,
        cellWidth: Float,
        cellHeight: Float,
    ) {
        val range = terminal.selectedColumns(line.id) ?: return
        val from = range.first.coerceIn(0, line.columns - 1)
        val to = range.last.coerceIn(0, line.columns - 1)
        if (to < from) return
        renderer.drawColor = color(theme.selectionBackground, SELECTION_ALPHA)
        renderer.fillRect(
            SDLRect((from * cellWidth).toInt(), y, ((to - from + 1) * cellWidth).toInt(), cellHeight.toInt()),
        )
    }

    private fun drawGlyphs(
        terminal: Terminal,
        blinkOn: Boolean,
        cursor: Cursor?,
        cellWidth: Float,
        cellHeight: Float,
    ) {
        vertices.clear()
        indexCount = 0
        decorations.clear()

        val rows = terminal.rows
        val columns = terminal.columns
        for (row in 0 until rows) {
            val line = terminal.lineAt(row)
            val y = row * cellHeight
            for (column in 0 until columns) {
                val codePoint = line.codepointAt(column)
                if (codePoint == 0 || codePoint == TerminalCell.WIDE_CONTINUATION) continue
                val attributes = line.attributesAt(column)
                if (attributes.hidden || (attributes.blink && !blinkOn)) continue

                val widthCells = line.widthAt(column).coerceAtLeast(1)
                val foreground = cellForeground(terminal, line, column)
                val hyperlink = line.hyperlinkIdAt(column) != 0
                if (attributes.anyUnderline || attributes.strikethrough || hyperlink) {
                    addDecoration(
                        x = column * cellWidth,
                        y = y,
                        width = widthCells * cellWidth,
                        rgb = foreground,
                        attributes = attributes,
                        hyperlink = hyperlink,
                    )
                }

                val uv = font.glyphUV(codePoint) ?: continue
                val size = font.glyphSize(codePoint)
                val glyphWidth = size?.x ?: (widthCells * cellWidth)
                val glyphHeight = size?.y ?: cellHeight
                val vertexColor = if (cursor != null &&
                    cursor.shape == TerminalCursorShape.BLOCK &&
                    cursor.row == row && cursor.column == column
                ) {
                    color(theme.cursorText)
                } else {
                    color(foreground)
                }

                val x = column * cellWidth
                val base = vertices.size
                vertices += SDLVertex(SDLFloatPoint(x, y), vertexColor, SDLFloatPoint(uv.x, uv.y))
                vertices += SDLVertex(SDLFloatPoint(x + glyphWidth, y), vertexColor, SDLFloatPoint(uv.x + uv.width, uv.y))
                vertices += SDLVertex(
                    SDLFloatPoint(x + glyphWidth, y + glyphHeight),
                    vertexColor,
                    SDLFloatPoint(uv.x + uv.width, uv.y + uv.height),
                )
                vertices += SDLVertex(SDLFloatPoint(x, y + glyphHeight), vertexColor, SDLFloatPoint(uv.x, uv.y + uv.height))
                addIndex(base)
                addIndex(base + 1)
                addIndex(base + 2)
                addIndex(base)
                addIndex(base + 2)
                addIndex(base + 3)
            }
        }

        val atlas = font.atlasTexture
        if (atlas != null && vertices.isNotEmpty()) {
            renderer.blendMode = SDLBlendMode.BLEND
            renderer.renderGeometry(atlas, vertices, indices.copyOf(indexCount))
        }
    }

    private fun addIndex(value: Int) {
        if (indexCount == indices.size) indices = indices.copyOf(indices.size * 2)
        indices[indexCount++] = value
    }

    private fun addDecoration(
        x: Float,
        y: Float,
        width: Float,
        rgb: Int,
        attributes: TerminalAttributes,
        hyperlink: Boolean,
    ) {
        val bits = attributes.bits and DECORATION_MASK
        val last = decorations.lastOrNull()
        if (last != null && last.y == y && last.rgb == rgb && last.bits == bits &&
            last.hyperlink == hyperlink && last.x + last.width == x
        ) {
            last.width += width
            return
        }
        decorations += Decoration(x, y, width, rgb, bits, hyperlink)
    }

    private fun drawDecorations(cellWidth: Float, cellHeight: Float) {
        renderer.blendMode = SDLBlendMode.NONE
        for (decoration in decorations) {
            val attributes = TerminalAttributes(decoration.bits)
            renderer.drawColor = color(decoration.rgb)
            val x = decoration.x.toInt()
            val w = decoration.width.toInt()
            val bottom = (decoration.y + cellHeight).toInt()
            if (attributes.anyUnderline) {
                renderer.fillRect(SDLRect(x, bottom - 1, w, 1))
                if (attributes.doubleUnderline) renderer.fillRect(SDLRect(x, bottom - 3, w, 1))
            }
            if (attributes.strikethrough) {
                renderer.fillRect(SDLRect(x, (decoration.y + cellHeight / 2f).toInt(), w, 1))
            }
            if (decoration.hyperlink) renderer.fillRect(SDLRect(x, bottom - 1, w, 1))
        }
    }

    private fun drawCursorShape(cursor: Cursor?, cellWidth: Float, cellHeight: Float) {
        if (cursor == null) return
        val x = (cursor.column * cellWidth).toInt()
        val y = (cursor.row * cellHeight).toInt()
        val cursorWidth = ((if (cursor.wide) 2 else 1) * cellWidth).toInt()
        renderer.blendMode = SDLBlendMode.NONE
        renderer.drawColor = color(theme.cursor)
        when (cursor.shape) {
            TerminalCursorShape.BLOCK -> Unit // drawn under the glyph
            TerminalCursorShape.BAR -> renderer.fillRect(SDLRect(x, y, 2, cellHeight.toInt()))
            TerminalCursorShape.UNDERLINE -> renderer.fillRect(SDLRect(x, y + cellHeight.toInt() - 2, cursorWidth, 2))
        }
    }

    private fun drawScrollbar(terminal: Terminal, width: Int, height: Int) {
        val total = terminal.scrollbackSize + terminal.rows
        if (total <= 0) return
        val thumbHeight = (terminal.rows.toFloat() / total * height).toInt().coerceAtLeast(16)
        val viewTop = ((terminal.scrollbackSize - terminal.scrollOffset).toFloat() / total * height).toInt()
        renderer.blendMode = SDLBlendMode.NONE
        renderer.drawColor = color(theme.scrollbar)
        renderer.fillRect(SDLRect(width - 3, viewTop, 3, thumbHeight))
    }

    private fun visibleCursor(terminal: Terminal, blinkOn: Boolean): Cursor? {
        if (!terminal.modes.cursorVisible || terminal.isScrolledBack || !blinkOn) return null
        val row = terminal.cursorRow.coerceIn(0, terminal.rows - 1)
        val column = terminal.cursorColumn.coerceIn(0, terminal.columns - 1)
        val wide = terminal.lineAt(row).widthAt(column) == 2
        return Cursor(row, column, wide, terminal.cursorStyle.shape)
    }

    private fun foregroundFor(terminal: Terminal, color: TerminalColor): Int {
        val resolved = theme.resolveForeground(terminal, color)
        return if (terminal.modes.reverseVideo) resolved xor 0xFFFFFF else resolved
    }

    private fun backgroundFor(terminal: Terminal, color: TerminalColor): Int {
        val resolved = theme.resolveBackground(terminal, color)
        return if (terminal.modes.reverseVideo) resolved xor 0xFFFFFF else resolved
    }

    private fun cellForeground(terminal: Terminal, line: TerminalLine, column: Int): Int {
        val attributes = line.attributesAt(column)
        return if (attributes.inverse) {
            backgroundFor(terminal, line.backgroundAt(column))
        } else {
            foregroundFor(terminal, line.foregroundAt(column))
        }
    }

    private fun cellBackground(terminal: Terminal, line: TerminalLine, column: Int): Int {
        val attributes = line.attributesAt(column)
        return if (attributes.inverse) {
            foregroundFor(terminal, line.foregroundAt(column))
        } else {
            backgroundFor(terminal, line.backgroundAt(column))
        }
    }

    /** Looks colors up in a small cache so a frame does not allocate one per cell. */
    private fun color(rgb: Int, alpha: Int = 255): SDLColor {
        val key = (rgb shl 8) or alpha
        return colorCache.getOrPut(key) { sdlColor(rgb, alpha) }
    }

    private class Decoration(
        val x: Float,
        val y: Float,
        var width: Float,
        val rgb: Int,
        val bits: Int,
        val hyperlink: Boolean,
    )

    private class Cursor(val row: Int, val column: Int, val wide: Boolean, val shape: TerminalCursorShape)

    private companion object {
        const val SELECTION_ALPHA = 0x80
        val DECORATION_MASK = TerminalAttributes.UNDERLINE_MASK or TerminalAttributes.STRIKETHROUGH
    }
}
