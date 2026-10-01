package cn.enaium.terminal.parser

import cn.enaium.terminal.core.Terminal

/** Writes [lines] top to bottom (control characters are the parser's job). */
internal fun Terminal.writeLines(vararg lines: String) {
    lines.forEachIndexed { row, text ->
        setCursor(row, 0)
        write(text)
    }
}
