package cn.enaium.terminal.imgui

import cn.enaium.imgui.ImDrawList
import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImVec2
import cn.enaium.imgui.ImVec4
import cn.enaium.terminal.core.Terminal
import cn.enaium.terminal.core.TerminalAttributes
import cn.enaium.terminal.core.TerminalCell
import cn.enaium.terminal.core.TerminalColor
import cn.enaium.terminal.core.TerminalCursorShape
import cn.enaium.terminal.core.TerminalLine

/**
 * Draws a [Terminal] into an ImGui draw list.
 *
 * The renderer batches the grid into as few primitives as possible: one
 * rectangle for the background, one filled rectangle per run of cells sharing
 * a background color, and one `DrawText` per run of cells sharing a style.
 * Wide characters are drawn individually (a monospaced glyph is twice as wide
 * as its cell, so it cannot ride along in a run) and blanks are never drawn.
 */
class TerminalRenderer(
    val fonts: TerminalFonts,
    var theme: TerminalTheme = TerminalTheme.Default,
) {

    /** The rectangle of the text cursor as drawn last (for IME candidates). */
    var cursorRect: ImVec4 = ImVec4(0f, 0f, 0f, 0f)
        private set

    /** The size the last frame occupied. */
    var drawnSize: ImVec2 = ImVec2(0f, 0f)
        private set

    private val run = StringBuilder(256)

    /**
     * Draws [terminal] with its top-left corner at [origin].
     *
     * [blinkOn] is the current phase of the cursor blink (the caller derives it
     * from the terminal's blink mode and a timer).
     */
    fun draw(drawList: ImDrawList, origin: ImVec2, terminal: Terminal, blinkOn: Boolean) {
        val metrics = fonts.metrics
        val cellWidth = metrics.cellWidth
        val cellHeight = metrics.cellHeight
        val columns = terminal.columns
        val rows = terminal.rows
        val width = columns * cellWidth
        val height = rows * cellHeight
        drawnSize = ImVec2(width, height)

        val defaultBackground = backgroundFor(terminal, TerminalColor.Default)
        val defaultForeground = foregroundFor(terminal, TerminalColor.Default)
        drawList.DrawRectFilled(origin, ImVec2(origin.x + width, origin.y + height), imColor(defaultBackground))

        for (row in 0 until rows) {
            val line = terminal.lineAt(row)
            val y = origin.y + row * cellHeight

            drawBackgrounds(drawList, origin, line, row, y, terminal, defaultBackground, cellWidth, cellHeight)
            drawSelection(drawList, origin, line, y, terminal, cellWidth, cellHeight)

            var column = 0
            while (column < columns) {
                val codepoint = line.codepointAt(column)
                if (codepoint == 0 || codepoint == TerminalCell.WIDE_CONTINUATION) {
                    column++
                    continue
                }
                val attributes = line.attributesAt(column)
                if (attributes.hidden || (attributes.blink && !blinkOn)) {
                    column += line.widthAt(column).coerceAtLeast(1)
                    continue
                }
                val foreground = cellForeground(terminal, line, column)
                val hyperlink = line.hyperlinkIdAt(column)

                run.setLength(0)
                var end = column
                while (end < columns) {
                    val candidate = line.codepointAt(end)
                    if (candidate == 0 || candidate == TerminalCell.WIDE_CONTINUATION) break
                    if (line.widthAt(end) != 1) break
                    // A run is only grid-safe while every glyph in it advances
                    // by exactly one cell, which a monospace face guarantees for
                    // ASCII. Anything else - a symbol, an emoji, a glyph taken
                    // from the merged fallback face - is drawn on its own cell:
                    // its advance may differ, and ImGui would shift the rest of
                    // the run with it, leaving the text one cell away from the
                    // cursor (which is positioned by cell index).
                    if (candidate !in ASCII_PRINTABLE) break
                    if (line.attributesAt(end).bits != attributes.bits) break
                    if (line.hyperlinkIdAt(end) != hyperlink) break
                    if (cellForeground(terminal, line, end) != foreground) break
                    line.appendTextTo(run, end)
                    end++
                }

                if (end == column) {
                    // A glyph that cannot ride along in a run - a wide
                    // character, a symbol, an emoji - is drawn on its own cell
                    // so the rest of the row keeps its alignment.
                    run.setLength(0)
                    line.appendTextTo(run, column)
                    val cellX = origin.x + column * cellWidth
                    val cellWidths = line.widthAt(column).coerceAtLeast(1)
                    drawRun(drawList, cellX, y + metrics.textOffsetY, run.toString(), foreground, attributes)
                    drawDecorations(
                        drawList,
                        cellX,
                        y,
                        cellWidths * cellWidth,
                        cellHeight,
                        foreground,
                        attributes,
                        hyperlink != 0,
                    )
                    column += cellWidths
                    continue
                }

                val x = origin.x + column * cellWidth
                drawRun(drawList, x, y + metrics.textOffsetY, run.toString(), foreground, attributes)
                drawDecorations(
                    drawList,
                    x,
                    y,
                    (end - column) * cellWidth,
                    cellHeight,
                    foreground,
                    attributes,
                    hyperlink != 0,
                )
                column = end
            }
        }

        drawCursor(drawList, origin, terminal, blinkOn, defaultBackground)
        if (terminal.isScrolledBack) drawScrollbar(drawList, origin, terminal, width, height)
    }

    private fun drawBackgrounds(
        drawList: ImDrawList,
        origin: ImVec2,
        line: TerminalLine,
        row: Int,
        y: Float,
        terminal: Terminal,
        defaultBackground: Int,
        cellWidth: Float,
        cellHeight: Float,
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
            drawList.DrawRectFilled(
                ImVec2(origin.x + column * cellWidth, y),
                ImVec2(origin.x + end * cellWidth, y + cellHeight),
                imColor(background),
            )
            column = end
        }
    }

    private fun drawSelection(
        drawList: ImDrawList,
        origin: ImVec2,
        line: TerminalLine,
        y: Float,
        terminal: Terminal,
        cellWidth: Float,
        cellHeight: Float,
    ) {
        val range = terminal.selectedColumns(line.id) ?: return
        val from = range.first.coerceIn(0, line.columns - 1)
        val to = range.last.coerceIn(0, line.columns - 1)
        if (to < from) return
        drawList.DrawRectFilled(
            ImVec2(origin.x + from * cellWidth, y),
            ImVec2(origin.x + (to + 1) * cellWidth, y + cellHeight),
            imColor(theme.selectionBackground, alpha = 0x80),
        )
    }

    private fun drawRun(
        drawList: ImDrawList,
        x: Float,
        y: Float,
        text: String,
        foreground: Int,
        attributes: TerminalAttributes,
    ) {
        val face = fonts.face(attributes.bold, attributes.italic)
        val push = face !== fonts.regular
        if (push) ImGui.pushFont(face)
        try {
            val color = imColor(foreground)
            drawList.DrawText(ImVec2(x, y), text, color)
            if (fonts.needsFakeBold(attributes.bold)) {
                // No bold face installed: thicken the glyphs with a sub-pixel overdraw.
                drawList.DrawText(ImVec2(x + 0.5f, y), text, color)
            }
        } finally {
            if (push) ImGui.popFont()
        }
    }

    private fun drawDecorations(
        drawList: ImDrawList,
        x: Float,
        y: Float,
        width: Float,
        cellHeight: Float,
        foreground: Int,
        attributes: TerminalAttributes,
        hyperlink: Boolean,
    ) {
        val color = imColor(foreground)
        if (attributes.anyUnderline) {
            val underlineY = y + cellHeight - 1f
            drawList.DrawLine(ImVec2(x, underlineY), ImVec2(x + width, underlineY), color, 1f)
            if (attributes.doubleUnderline) {
                drawList.DrawLine(ImVec2(x, underlineY - 2f), ImVec2(x + width, underlineY - 2f), color, 1f)
            }
        }
        if (attributes.strikethrough) {
            val strikeY = y + cellHeight / 2f
            drawList.DrawLine(ImVec2(x, strikeY), ImVec2(x + width, strikeY), color, 1f)
        }
        if (hyperlink) {
            val linkY = y + cellHeight - 1f
            drawList.DrawLine(ImVec2(x, linkY), ImVec2(x + width, linkY), color, 1f)
        }
    }

    private fun drawCursor(
        drawList: ImDrawList,
        origin: ImVec2,
        terminal: Terminal,
        blinkOn: Boolean,
        defaultBackground: Int,
    ) {
        val metrics = fonts.metrics
        val cellWidth = metrics.cellWidth
        val cellHeight = metrics.cellHeight
        if (!terminal.modes.cursorVisible || terminal.isScrolledBack || !blinkOn) {
            cursorRect = ImVec4(0f, 0f, 0f, 0f)
            return
        }
        val row = terminal.cursorRow.coerceIn(0, terminal.rows - 1)
        val column = terminal.cursorColumn.coerceIn(0, terminal.columns - 1)
        val line = terminal.lineAt(row)
        val wide = line.widthAt(column) == 2
        val x = origin.x + column * cellWidth
        val y = origin.y + row * cellHeight
        val cursorWidth = if (wide) cellWidth * 2 else cellWidth
        cursorRect = ImVec4(x, y, x + cursorWidth, y + cellHeight)
        when (terminal.cursorStyle.shape) {
            TerminalCursorShape.BLOCK -> {
                drawList.DrawRectFilled(ImVec2(x, y), ImVec2(x + cursorWidth, y + cellHeight), imColor(theme.cursor))
                if (line.codepointAt(column) != 0) {
                    run.setLength(0)
                    line.appendTextTo(run, column)
                    drawRun(
                        drawList,
                        x,
                        y + metrics.textOffsetY,
                        run.toString(),
                        theme.cursorText,
                        TerminalAttributes.None,
                    )
                }
            }

            TerminalCursorShape.BAR -> drawList.DrawRectFilled(
                ImVec2(x, y),
                ImVec2(x + 2f, y + cellHeight),
                imColor(theme.cursor),
            )

            TerminalCursorShape.UNDERLINE -> drawList.DrawRectFilled(
                ImVec2(x, y + cellHeight - 2f),
                ImVec2(x + cursorWidth, y + cellHeight),
                imColor(theme.cursor),
            )
        }
    }

    private fun drawScrollbar(drawList: ImDrawList, origin: ImVec2, terminal: Terminal, width: Float, height: Float) {
        val total = terminal.scrollbackSize + terminal.rows
        if (total <= 0) return
        val thumbHeight = (terminal.rows.toFloat() / total * height).coerceAtLeast(16f)
        val viewTop = (terminal.scrollbackSize - terminal.scrollOffset).toFloat() / total * height
        val x = origin.x + width - 3f
        drawList.DrawRectFilled(
            ImVec2(x, origin.y + viewTop),
            ImVec2(x + 3f, origin.y + viewTop + thumbHeight),
            imColor(theme.scrollbar),
        )
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
}

/** The code points a text run may contain: their glyphs share the cell advance. */
private val ASCII_PRINTABLE = 0x20..0x7E
