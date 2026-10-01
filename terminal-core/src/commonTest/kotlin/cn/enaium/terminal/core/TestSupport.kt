package cn.enaium.terminal.core

/**
 * Writes [text] at the start of [row]. `Terminal.write` only handles printable
 * code points (control characters are the parser's job), so multi-line test
 * fixtures position the cursor explicitly instead of embedding `\r\n`.
 */
internal fun Terminal.writeLine(row: Int, text: String) {
    setCursor(row, 0)
    write(text)
}

/** Writes [lines] top to bottom. */
internal fun Terminal.writeLines(vararg lines: String) {
    lines.forEachIndexed { row, text -> writeLine(row, text) }
}
