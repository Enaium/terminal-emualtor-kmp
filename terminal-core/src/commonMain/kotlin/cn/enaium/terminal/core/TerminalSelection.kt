package cn.enaium.terminal.core

/** How a selection extends over the screen. */
enum class TerminalSelectionMode {
    /** From the start cell to the end cell, line by line (what a drag selects). */
    LINEAR,

    /** A rectangle between the start and end columns (Alt+drag in most terminals). */
    BLOCK,
}

/**
 * A position inside the buffer.
 *
 * [line] is the *absolute line id* ([TerminalLine.id]), not a screen row: it
 * keeps pointing at the same content while the view scrolls or new output
 * arrives, and it is never reused for a different line.
 */
data class TerminalPosition(
    val line: Long,
    val column: Int,
) : Comparable<TerminalPosition> {
    override fun compareTo(other: TerminalPosition): Int =
        if (line != other.line) line.compareTo(other.line) else column.compareTo(other.column)
}

/** A range of cells selected by the user. */
data class TerminalSelection(
    val start: TerminalPosition,
    val end: TerminalPosition,
    val mode: TerminalSelectionMode = TerminalSelectionMode.LINEAR,
) {

    /** The earlier position of the range. */
    val from: TerminalPosition get() = if (start <= end) start else end

    /** The later position of the range. */
    val to: TerminalPosition get() = if (start <= end) end else start

    /** True when [line] / [column] fall inside this selection. */
    fun contains(line: Long, column: Int): Boolean = when (mode) {
        TerminalSelectionMode.LINEAR -> {
            if (line < from.line || line > to.line) {
                false
            } else if (from.line == to.line) {
                column >= from.column && column <= to.column
            } else if (line == from.line) {
                column >= from.column
            } else if (line == to.line) {
                column <= to.column
            } else {
                true
            }
        }

        TerminalSelectionMode.BLOCK -> {
            val firstColumn = minOf(start.column, end.column)
            val lastColumn = maxOf(start.column, end.column)
            line in from.line..to.line && column in firstColumn..lastColumn
        }
    }

    /** The column range selected on [line], or null when the line is not selected. */
    fun columnRange(line: Long): IntRange? = when (mode) {
        TerminalSelectionMode.LINEAR -> {
            if (line < from.line || line > to.line) {
                null
            } else if (from.line == to.line) {
                from.column..to.column
            } else if (line == from.line) {
                from.column..Int.MAX_VALUE
            } else if (line == to.line) {
                Int.MIN_VALUE..to.column
            } else {
                Int.MIN_VALUE..Int.MAX_VALUE
            }
        }

        TerminalSelectionMode.BLOCK -> {
            if (line < from.line || line > to.line) null
            else minOf(start.column, end.column)..maxOf(start.column, end.column)
        }
    }

    /**
     * True when the selection covers exactly one cell (its ends are equal).
     *
     * The ends are inclusive, so this is a real selection of one character -
     * "nothing is selected" is [Terminal.selection] being null.
     */
    val isSingleCell: Boolean get() = start == end
}
